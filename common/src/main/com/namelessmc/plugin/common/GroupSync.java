package com.namelessmc.plugin.common;

import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessUser;
import com.namelessmc.java_api.integrations.DetailedIntegrationData;
import com.namelessmc.java_api.integrations.StandardIntegrationTypes;
import com.namelessmc.java_api.exception.ApiError;
import com.namelessmc.java_api.exception.ApiException;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GroupSync implements Reloadable {

    /**
     * Current group set for players, updated for online players
     */
    private final Map<UUID, Set<String>> playerGroups = new HashMap<>();
    private final Set<UUID> pendingPlayers = new HashSet<>();

    private final NamelessPlugin plugin;

    private @Nullable AbstractScheduledTask task = null;
    private int serverId;
    private long generation;

    GroupSync(final NamelessPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public synchronized void unload() {
        generation++;
        playerGroups.clear();
        pendingPlayers.clear();
        if (this.task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public synchronized void load() {
        unload();
        final long admittedGeneration = generation;
        ConfigurationNode config = this.plugin.config().main();
        if (!config.node("group-sync", "enabled").getBoolean()) {
            this.plugin.logger().fine("New group sync disabled");
            return;
        }

        this.plugin.logger().fine("Enabling new group sync system");

        this.serverId = config.node("api", "server-id").getInt(0);

        if (this.serverId <= 0) {
            this.plugin.logger().warning("Group sync is enabled but server-id is missing or zero. Group sync will not work until server-id is configured in main.yaml.");
            return;
        }

        final AbstractPermissions permissions = this.plugin.permissions();

        if (permissions == null) {
            this.plugin.logger().warning("Group sync is enabled, but no permissions adapter is active. Is a supported permissions system installed, like LuckPerms or Vault?");
            return;
        }

		// This bootstrap reads website version metadata only. It must still create the gated
		// repeating timer when the plugin starts/reloads while a durable pause is held.
		this.plugin.heartbeatScheduler().runAsync(() -> {
            synchronized (this) {
                if (generation != admittedGeneration) return;
            }
            if (!supportsGroupSync()) return;
            synchronized (this) {
                // Metadata I/O may finish after unload or a newer configuration load. Keep
                // invalidation and installation under one lock so retired work cannot revive it.
                if (generation != admittedGeneration) return;
                this.task = this.plugin.scheduler().runTimer(this::syncGroups, Duration.ofSeconds(10));
            }
        });
    }

    /** Metadata-only boundary; fixtures never need to call this website read directly. */
    boolean supportsGroupSync() {
        try {
            final NamelessAPI api = this.plugin.apiProvider().api();
            if (api == null) return false;
            if (api.website().parsedVersion().minor() < 2) {
                this.plugin.logger().warning("Website version is older than v2.2.0+, refusing to enable new group sync system");
                return false;
            }
            return true;
        } catch (final NamelessException e) {
            this.plugin.logger().logException(e);
            return false;
        }
    }

    synchronized void syncGroups() {
        final AbstractPermissions permissions = this.plugin.permissions();
        if (permissions == null) {
            throw new IllegalStateException("Permissions adapter cannot be null, or this task shouldn't have been registered");
        }

        final long admittedGeneration = generation;
        final int admittedServerId = serverId;
        final Map<UUID, Set<String>> groupsToSend = new HashMap<>();
        final Map<UUID, Set<String>> previousGroups = new HashMap<>();
        for (NamelessPlayer player : this.plugin.audiences().onlinePlayers()) {
            if (pendingPlayers.contains(player.uuid())) continue;
            final Set<String> current = permissions.getPlayerGroups(player);
            if (current == null) continue;
            final Set<String> next = Set.copyOf(current);
            final Set<String> previous = playerGroups.get(player.uuid());
            if (next.equals(previous)) continue;
            groupsToSend.put(player.uuid(), next);
            previousGroups.put(player.uuid(), previous == null ? Set.of() : previous);
        }
        if (groupsToSend.isEmpty()) return;
        pendingPlayers.addAll(groupsToSend.keySet());

        this.plugin.scheduler().runAsync(() -> {
            final NamelessAPI api = this.plugin.apiProvider().api();
            for (Map.Entry<UUID, Set<String>> entry : groupsToSend.entrySet()) {
                synchronized (this) {
                    if (generation != admittedGeneration) return;
                }
                boolean sent = false;
                try {
                    if (api != null) {
                        final Set<String> added = new HashSet<>(entry.getValue());
                        added.removeAll(previousGroups.get(entry.getKey()));
                        final Set<String> removed = new HashSet<>(previousGroups.get(entry.getKey()));
                        removed.removeAll(entry.getValue());
                        final NamelessUser user = api.userByMinecraftUuidLazy(entry.getKey());
                        final DetailedIntegrationData minecraft = user.integrations().get(StandardIntegrationTypes.MINECRAFT);
                        // Preserve the old bulk endpoint's active-account and verified-link floor.
                        // The newer endpoint and its integration transformer do not check these.
                        if (!user.isVerified() || minecraft == null || !minecraft.isVerified()
                                || !entry.getKey().equals(user.minecraftUuid())) continue;
                        user.updateMinecraftGroups(admittedServerId,
                                added.toArray(new String[0]), removed.toArray(new String[0]));
                        sent = true;
                    }
                } catch (NamelessException e) {
                    if (e instanceof ApiException && ((ApiException) e).apiError() == ApiError.NAMELESS_CANNOT_FIND_USER) {
                        continue; // Unlinked players must not prevent other players' deltas.
                    }
                    this.plugin.logger().warning("An error occurred while sending player groups to the website, for group sync. The plugin will try again later.");
                    if (e instanceof ApiException && ((ApiException) e).apiError() == ApiError.CORE_INVALID_SERVER_ID) {
                        this.plugin.logger().warning("The server id configured in main.yaml is incorrect, or no correct group sync server is selected in StaffCP > Integrations > Minecraft > Minecraft Servers.");
                    } else {
                        this.plugin.logger().logException(e);
                    }
                } finally {
                    synchronized (this) {
                        if (generation == admittedGeneration) {
                            pendingPlayers.remove(entry.getKey());
                            // Retain the acknowledged baseline on failure so a failed removal
                            // is retried as a removal, including when another player succeeded.
                            if (sent) playerGroups.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        });
    }

    /**
     * Force groups to be sent again for this player
     * @param player
     */
    public synchronized void resetGroups(NamelessPlayer player) {
        this.playerGroups.remove(player.uuid());
    }

}
