package com.namelessmc.plugin.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.event.NamelessJoinEvent;
import com.namelessmc.plugin.common.event.NamelessPlayerQuitEvent;
import net.kyori.adventure.text.Component;
import net.kyori.event.EventSubscription;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Forum-controlled reminders. All HTTP work uses the publication-isolated asynchronous scheduler. */
class LinkReminderTask implements Reloadable, Runnable {
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(1);
    private static final long MAX_RESPONSE_AGE = Duration.ofMinutes(2).toNanos();
    private static final int BATCH_SIZE = 100;
    private final NamelessPlugin plugin;
    private volatile @Nullable Run active;
    private @Nullable AbstractScheduledTask task;
    private @Nullable EventSubscription join;
    private @Nullable EventSubscription quit;

    LinkReminderTask(NamelessPlugin plugin) { this.plugin = plugin; }

    @Override public synchronized void load() {
        unload();
        Run run = new Run();
        active = run;
        synchronized (run) {
            for (UUID uuid : online()) run.players.put(uuid, new Session(now()));
        }
        join = plugin.events().subscribe(NamelessJoinEvent.class, event -> {
            synchronized (run) {
                if (active == run) run.players.put(event.player().uuid(), new Session(now()));
            }
        });
        quit = plugin.events().subscribe(NamelessPlayerQuitEvent.class, event -> {
            synchronized (run) { run.players.remove(event.uuid()); }
        });
        task = plugin.scheduler().runTimer(this, POLL_INTERVAL);
    }

    @Override public synchronized void unload() {
        active = null; // Late network and queued sync callbacks cannot publish after reload/disable.
        if (task != null) { task.cancel(); task = null; }
        if (join != null) { join.unsubscribe(); join = null; }
        if (quit != null) { quit.unsubscribe(); quit = null; }
    }

    @Override public void run() {
        Run run = active;
        if (run == null || run.inFlight.get()) return;
        Map<UUID, Session> snapshot = new LinkedHashMap<>();
        synchronized (run) {
            Set<UUID> online = new HashSet<>(online());
            run.players.keySet().retainAll(online);
            for (UUID uuid : online) {
                Session session = run.players.computeIfAbsent(uuid, ignored -> new Session(now()));
                snapshot.put(uuid, session);
            }
        }
        if (snapshot.isEmpty()) return;
        // Set inFlight inside admitted work: a publication pause may discard a scheduled callback.
        plugin.scheduler().runAsync(() -> {
            if (active != run || !run.inFlight.compareAndSet(false, true)) return;
            final long sequence;
            synchronized (run) { sequence = ++run.sequence; }
            try {
                List<UUID> uuids = new ArrayList<>(snapshot.keySet());
                for (int offset = 0; offset < uuids.size() && active == run; offset += BATCH_SIZE) {
                    Set<UUID> batch = new HashSet<>(uuids.subList(offset, Math.min(offset + BATCH_SIZE, uuids.size())));
                    long started = now();
                    LinkReminderResponse response = fetch(batch);
                    if (response == null) break;
                    plugin.scheduler().runSync(() -> apply(run, sequence, snapshot, response, started));
                }
            } catch (NamelessException | IllegalArgumentException | IllegalStateException failure) {
                // Optional/absent module, invalid settings and outages all mean no reminder.
                // Avoid logging response bodies, account identifiers or connection credentials.
                plugin.logger().fine("Link reminders unavailable; retrying on the next check");
            } finally {
                run.inFlight.set(false);
            }
        });
    }

    private void apply(Run run, long sequence, Map<UUID, Session> snapshot,
                       LinkReminderResponse response, long started) {
        synchronized (run) {
            if (active != run || sequence != run.sequence || now() - started > MAX_RESPONSE_AGE) return;
            for (Map.Entry<UUID, LinkReminderResponse.State> entry : response.players.entrySet()) {
                UUID uuid = entry.getKey();
                Session session = run.players.get(uuid);
                if (session == null || session != snapshot.get(uuid)) continue;
                LinkReminderResponse.State state = entry.getValue();
                long now = now();
                if (!response.enabled || state == LinkReminderResponse.State.COMPLETE) {
                    session.lastReminder = now;
                } else if ((state == LinkReminderResponse.State.UNLINKED
                        || state == LinkReminderResponse.State.DISCORD_MISSING)
                        && now - session.lastReminder >= response.intervalNanos) {
                    if (send(uuid, response.message(state))) session.lastReminder = now;
                }
            }
        }
    }

    @Nullable LinkReminderResponse fetch(Set<UUID> uuids) throws NamelessException {
        NamelessAPI api = plugin.apiProvider().api();
        if (api == null) return null;
        JsonObject request = new JsonObject();
        request.addProperty("protocol_version", 1);
        JsonArray players = new JsonArray();
        for (UUID uuid : uuids) players.add(uuid.toString());
        request.add("uuids", players);
        return LinkReminderResponse.parse(api.requests().post("minecraft/link-reminders", request), uuids);
    }

    long now() { return System.nanoTime(); }

    Collection<UUID> online() {
        List<UUID> players = new ArrayList<>();
        for (NamelessPlayer player : plugin.audiences().onlinePlayers()) players.add(player.uuid());
        return players;
    }

    boolean send(UUID uuid, Component message) {
        NamelessPlayer player = plugin.audiences().player(uuid);
        if (player == null) return false;
        player.sendMessage(message);
        return true;
    }

    private static final class Run {
        final Map<UUID, Session> players = new HashMap<>();
        final AtomicBoolean inFlight = new AtomicBoolean();
        long sequence;
    }

    private static final class Session {
        long lastReminder;
        Session(long now) { lastReminder = now; }
    }
}
