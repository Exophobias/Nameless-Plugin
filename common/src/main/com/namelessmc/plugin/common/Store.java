package com.namelessmc.plugin.common;

import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.exception.ApiError;
import com.namelessmc.java_api.exception.ApiException;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.java_api.modules.store.PendingCommandsResponse;
import com.namelessmc.java_api.modules.store.PendingCommandsResponse.Cursor;
import com.namelessmc.java_api.modules.store.PendingCommandsResponse.Page;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Store implements Reloadable {

    // This is a fixed runtime budget, not a new operator configuration value.
    private static final int COMMANDS_PER_TICK = 8;
    private static final Duration NEXT_COMMAND_BATCH_DELAY = Duration.ofMillis(50);
    private static final long MAX_ACK_RETRY_SECONDS = 300;

    private final NamelessPlugin plugin;
    private final AtomicBoolean sweepInFlight = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean active;
    private volatile @Nullable AbstractScheduledTask commandTask;

    public Store(NamelessPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void unload() {
        this.active = false;
        this.generation.incrementAndGet();
        AbstractScheduledTask task = this.commandTask;
        this.commandTask = null;
        if (task != null) {
            task.cancel();
        }
        this.sweepInFlight.set(false);
    }

    @Override
    public void load() {
        ConfigurationNode commandExecutor = this.plugin.config().modules().node("store", "command-executor");
        if (!commandExecutor.node("enabled").getBoolean()) {
            return;
        }
        Duration interval = ConfigurationHandler.getDuration(commandExecutor.node("interval"));
        if (interval == null) {
            this.plugin.logger().warning("Invalid interval for store module command executor");
            return;
        }

        this.active = true;
        this.commandTask = this.plugin.scheduler().runTimer(this::retrievePendingCommands, interval);
        if (this.commandTask == null) {
            this.active = false;
        }
    }

    public void retrievePendingCommands() {
        if (!this.active || !this.sweepInFlight.compareAndSet(false, true)) {
            return;
        }
        final long currentGeneration = this.generation.get();
        final int connectionId = this.plugin.config().modules().node("store", "connection-id").getInt();
        this.plugin.scheduler().runAsync(() -> {
            if (!this.isCurrent(currentGeneration)) {
                return;
            }
            NamelessAPI api = this.plugin.apiProvider().api();
            if (api == null) {
                this.plugin.logger().fine("Store: API is not available");
                this.finishSweep(currentGeneration);
                return;
            }
            this.fetchPage(currentGeneration, connectionId, api, null, 0);
        });
    }

    /** Fetches one bounded page off the game thread. A legacy server still returns one full response. */
    private void fetchPage(long currentGeneration, int connectionId, NamelessAPI api,
                           @Nullable Cursor cursor, int highWaterId) {
        if (!this.isCurrent(currentGeneration)) {
            return;
        }
        try {
            PendingCommandsResponse response = cursor == null
                    ? api.store().pendingCommandsPage(connectionId)
                    : api.store().pendingCommandsPage(connectionId, cursor, highWaterId);
            Page page = response.page();
            if (cursor != null && (page == null || page.highWaterId() != highWaterId)) {
                throw new IllegalStateException("Store command page lost its sweep cursor");
            }
            PageWork work = new PageWork(currentGeneration, connectionId, api, response);
            this.plugin.scheduler().runSync(() -> this.processPage(work));
        } catch (NamelessException error) {
            if (error instanceof ApiException
                    && ((ApiException) error).apiError() == ApiError.STORE_CONNECTION_NOT_FOUND) {
                this.plugin.logger().warning("Unable to retrieve Store commands: invalid connection id.");
            } else {
                this.plugin.logger().logException(error);
            }
            this.finishSweep(currentGeneration);
        } catch (RuntimeException error) {
            this.plugin.logger().logException(error);
            this.finishSweep(currentGeneration);
        }
    }

    /** Called on the game thread; each invocation inspects at most COMMANDS_PER_TICK rows. */
    private void processPage(PageWork work) {
        if (!this.isCurrent(work.generation)) {
            return;
        }
        if (work.commands.isEmpty()) {
            this.finishPage(work);
            return;
        }
        this.processBudget(work);
    }

    private void processBudget(PageWork work) {
        if (!this.isCurrent(work.generation)) {
            return;
        }
        List<PendingCommandsResponse.PendingCommand> completed = new ArrayList<>(COMMANDS_PER_TICK);
        boolean stopAfterAck = false;
        int inspected = 0;
        while (work.nextIndex < work.commands.size() && inspected < COMMANDS_PER_TICK) {
            CommandWork command = work.commands.get(work.nextIndex++);
            ++inspected;
            try {
                if (this.executeCommand(work.response.shouldUseUuids(), command)) {
                    completed.add(command.command);
                }
            } catch (RuntimeException error) {
                // Dispatch may have had an effect before throwing. Stop rather than retrying it here.
                this.plugin.logger().warning("Store command execution has an uncertain outcome; polling is suspended until reload.");
                this.plugin.logger().logException(error);
                stopAfterAck = true;
                break;
            }
        }

        if (completed.isEmpty()) {
            if (stopAfterAck) {
                this.suspendAfterExecutionFailure(work.generation);
            } else {
                this.advanceAfterBatch(work);
            }
            return;
        }

        final boolean stop = stopAfterAck;
        this.plugin.scheduler().runAsync(() -> this.acknowledge(work, completed, 0, stop));
    }

    private boolean executeCommand(boolean useUuids, CommandWork work) {
        PendingCommandsResponse.PendingCommandsCustomer customer = work.customer;
        PendingCommandsResponse.PendingCommand pendingCommand = work.command;
        NamelessPlayer player;
        if (useUuids) {
            UUID customerUuid;
            try {
                customerUuid = customer.identifierAsUuid();
            } catch (IllegalArgumentException error) {
                this.plugin.logger().warning("Skipped Store command: customer UUID is invalid.");
                return false;
            }
            if (customerUuid == null) {
                this.plugin.logger().warning("Skipped Store command: customer UUID is missing.");
                return false;
            }
            player = this.plugin.audiences().player(customerUuid);
        } else {
            player = this.plugin.audiences().playerByUsername(customer.username());
        }

        String command = pendingCommand.command();
        if (pendingCommand.isOnlineRequired() && player == null) {
            this.plugin.logger().fine("Skipped Store command: player needs to be online.");
            return false;
        }

        String uuid = player != null
                ? NamelessAPI.javaUuidToWebsiteUuid(player.uuid()) : customer.identifier();
        String username = player != null ? player.username() : customer.username();
        if (command.contains("{uuid}")) {
            if (uuid == null) {
                this.plugin.logger().warning("Skipped Store command: UUID placeholder is unknown.");
                return false;
            }
            command = command.replace("{uuid}", uuid);
        }
        command = command.replace("{username}", username);

        this.plugin.logger().info("Running command: " + command);
        this.plugin.audiences().console().dispatchCommand(command);
        return true;
    }

    /** Retry only the ACK on failure; never rerun this process's completed commands. */
    private void acknowledge(PageWork work,
                             List<PendingCommandsResponse.PendingCommand> completed,
                             int attempt, boolean stopAfterAck) {
        if (!this.isCurrent(work.generation)) {
            return;
        }
        try {
            work.api.store().markCommandsExecuted(completed);
            this.plugin.scheduler().runSync(() -> {
                if (stopAfterAck) {
                    this.suspendAfterExecutionFailure(work.generation);
                } else {
                    this.advanceAfterBatch(work);
                }
            });
        } catch (Exception error) {
            this.plugin.logger().warning("Store command acknowledgement failed; retrying the same IDs without re-execution.");
            this.plugin.logger().logException(error);
            long delay = Math.min(MAX_ACK_RETRY_SECONDS, 5L << Math.min(attempt, 6));
            AbstractScheduledTask retry = this.plugin.scheduler().runDelayed(
                    () -> this.plugin.scheduler().runAsync(
                            () -> this.acknowledge(work, completed, attempt + 1, stopAfterAck)),
                    Duration.ofSeconds(delay));
            if (retry == null && this.isCurrent(work.generation)) {
                this.plugin.logger().warning("Unable to schedule Store acknowledgement retry; polling remains suspended.");
                this.suspendAfterExecutionFailure(work.generation);
            }
        }
    }

    private void advanceAfterBatch(PageWork work) {
        if (!this.isCurrent(work.generation)) {
            return;
        }
        if (work.nextIndex < work.commands.size()) {
            // Some proxies run runSync immediately; a delayed batch bounds work there too.
            AbstractScheduledTask next = this.plugin.scheduler().runDelayed(
                    () -> this.processBudget(work), NEXT_COMMAND_BATCH_DELAY);
            if (next == null && this.isCurrent(work.generation)) {
                this.plugin.logger().warning("Unable to schedule the next Store command batch.");
                this.suspendAfterExecutionFailure(work.generation);
            }
        } else {
            this.finishPage(work);
        }
    }

    private void finishPage(PageWork work) {
        if (!this.isCurrent(work.generation)) {
            return;
        }
        Page page = work.response.page();
        if (page == null || !page.hasMore()) {
            this.finishSweep(work.generation);
            return;
        }
        Cursor cursor = page.nextCursor();
        if (cursor == null) {
            this.plugin.logger().warning("Store page ended without a continuation cursor.");
            this.finishSweep(work.generation);
            return;
        }
        AbstractScheduledTask next = this.plugin.scheduler().runDelayed(
                () -> this.plugin.scheduler().runAsync(() -> this.fetchPage(
                        work.generation, work.connectionId, work.api, cursor, page.highWaterId())),
                NEXT_COMMAND_BATCH_DELAY);
        if (next == null && this.isCurrent(work.generation)) {
            this.plugin.logger().warning("Unable to schedule the next Store command page.");
            this.suspendAfterExecutionFailure(work.generation);
        }
    }

    private void suspendAfterExecutionFailure(long currentGeneration) {
        if (!this.isCurrent(currentGeneration)) {
            return;
        }
        this.active = false;
        this.generation.incrementAndGet();
        AbstractScheduledTask task = this.commandTask;
        this.commandTask = null;
        if (task != null) {
            task.cancel();
        }
        this.sweepInFlight.set(false);
    }

    private boolean isCurrent(long currentGeneration) {
        return this.active && this.generation.get() == currentGeneration;
    }

    private void finishSweep(long currentGeneration) {
        if (this.isCurrent(currentGeneration)) {
            this.sweepInFlight.set(false);
        }
    }

    private static final class CommandWork {
        private final PendingCommandsResponse.PendingCommandsCustomer customer;
        private final PendingCommandsResponse.PendingCommand command;

        private CommandWork(PendingCommandsResponse.PendingCommandsCustomer customer,
                            PendingCommandsResponse.PendingCommand command) {
            this.customer = customer;
            this.command = command;
        }
    }

    private static final class PageWork {
        private final long generation;
        private final int connectionId;
        private final NamelessAPI api;
        private final PendingCommandsResponse response;
        private final List<CommandWork> commands = new ArrayList<>();
        private int nextIndex;

        private PageWork(long generation, int connectionId, NamelessAPI api,
                         PendingCommandsResponse response) {
            this.generation = generation;
            this.connectionId = connectionId;
            this.api = api;
            this.response = response;
            for (PendingCommandsResponse.PendingCommandsCustomer customer : response.customers()) {
                for (PendingCommandsResponse.PendingCommand command : customer.pendingCommands()) {
                    this.commands.add(new CommandWork(customer, command));
                }
            }
        }
    }
}
