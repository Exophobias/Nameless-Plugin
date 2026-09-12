package com.namelessmc.plugin.common;

import com.google.gson.JsonObject;
import com.namelessmc.plugin.common.audiences.*;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.logger.JulLogger;
import net.kyori.adventure.audience.Audience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual sender/scheduler path, replacing only the final HTTP exchange. */
class PublicationHeartbeatTest {
    @TempDir Path temp;

    @Test void pendingCleanSnapshotDrainsThenOnlyHeartbeatRunsUntilExactRelease() throws Exception {
        QueueScheduler raw = new QueueScheduler();
        NamelessPlugin plugin = plugin(raw);
        Sender sender = new Sender(plugin);
        sender.load();
        sender.run();
        assertEquals(1, sender.captures);
        UUID token = UUID.randomUUID();
        var ready = plugin.publicationPause().acquire("heartbeat_test", token).toCompletableFuture();
        assertFalse(ready.isDone(), "queued snapshot still owns its activity");
        raw.next();
        ready.join();
        assertEquals("clean", sender.sent.get(0).get("testProvider").getAsString());
        sender.value = "fixture-must-not-leak";
        sender.run();
        raw.next();
        assertEquals(1, sender.captures, "paused heartbeat cannot invoke a custom provider");
        assertFalse(sender.sent.get(1).toString().contains("fixture"));
        assertFalse(sender.sent.get(1).has("testProvider"));
        assertEquals(50, sender.sent.get(1).get("max_players").getAsInt());
        assertEquals("Patriam", sender.sent.get(1).get("motd").getAsString());
        assertEquals(0, sender.sent.get(1).getAsJsonObject("players").size());
        assertTrue(plugin.publicationPause().release("heartbeat_test", token));
        sender.value = "restored";
        sender.run();
        raw.next();
        assertEquals("restored", sender.sent.get(2).get("testProvider").getAsString());
        assertEquals(2, sender.captures);
    }

    @Test void senderReloadDuringPauseKeepsHeartbeatAndDoesNotReopenProviders() throws Exception {
        QueueScheduler raw = new QueueScheduler();
        NamelessPlugin plugin = plugin(raw);
        Sender sender = new Sender(plugin);
        sender.load();
        UUID token = UUID.randomUUID();
        plugin.publicationPause().acquire("reload_heartbeat", token).toCompletableFuture().join();
        sender.unload();
        sender.load();
        sender.run();
        raw.next();
        assertEquals(0, sender.captures);
        assertEquals(1, sender.sent.size());
        assertFalse(sender.sent.get(0).has("testProvider"));
        assertEquals("PAUSED", plugin.publicationPause().status().get("state"));
    }

    @Test void metadataBootstrapIsStillQueuedDuringPauseButGameTasksAreNot() throws Exception {
        QueueScheduler raw = new QueueScheduler();
        NamelessPlugin plugin = plugin(raw);
        plugin.config().main().node("group-sync", "enabled").set(true);
        plugin.publicationPause().acquire("bootstrap_test", UUID.randomUUID()).toCompletableFuture().join();
        plugin.groupSync().load();
        plugin.scheduler().runAsync(() -> fail("fixture-sensitive bootstrap"));
        assertEquals(1, raw.queue.size());
        raw.next();
        assertEquals("0", plugin.publicationPause().status().get("inFlight"));
    }

    @Test void queuedGroupBootstrapCannotReenableSyncAfterDisabledReloadDuringPause() throws Exception {
        QueueScheduler raw = new QueueScheduler();
        NamelessPlugin plugin = plugin(raw);
        plugin.config().main().node("group-sync", "enabled").set(true);
        UUID token = UUID.randomUUID();
        plugin.publicationPause().acquire("group_reload", token).toCompletableFuture().join();
        GroupSync groups = new GroupSync(plugin) {
            @Override boolean supportsGroupSync() { return true; }
        };
        groups.load();
        assertEquals(1, raw.queue.size());
        groups.unload();
        plugin.config().main().node("group-sync", "enabled").set(false);
        groups.load();
        raw.next();
        assertTrue(plugin.publicationPause().release("group_reload", token));
        assertEquals(0, raw.timers, "An old bootstrap must not publish after pause release");
    }

    @Test void groupBootstrapFinishingAcrossUnloadCannotInstallRetiredTimer() throws Exception {
        QueueScheduler raw = new QueueScheduler();
        NamelessPlugin plugin = plugin(raw);
        plugin.config().main().node("group-sync", "enabled").set(true);
        GroupSync groups = new GroupSync(plugin) {
            @Override boolean supportsGroupSync() { unload(); return true; }
        };
        groups.load();
        raw.next();
        assertEquals(0, raw.timers, "Recheck generation after the asynchronous website read");
    }

    @Test void newerGroupLoadInstallsExactlyOneTimerAfterOldBootstrapCompletes() throws Exception {
        QueueScheduler raw = new QueueScheduler();
        NamelessPlugin plugin = plugin(raw);
        plugin.config().main().node("group-sync", "enabled").set(true);
        GroupSync groups = new GroupSync(plugin) {
            @Override boolean supportsGroupSync() { return true; }
        };
        groups.load();
        groups.unload();
        groups.load();
        raw.next();
        raw.next();
        assertEquals(1, raw.timers);
    }

    private NamelessPlugin plugin(QueueScheduler raw) throws Exception {
        NamelessPlugin plugin = new NamelessPlugin(temp, raw,
                config -> new JulLogger(config, Logger.getLogger("heartbeat-test")), null, "test", "test") {
            @Override public AbstractPermissions permissions() {
                return new AbstractPermissions() {
                    public boolean isUsable() { return true; }
                    public Set<String> getGroups() { return Set.of("members"); }
                    public Set<String> getPlayerGroups(NamelessPlayer player) { return Set.of("members"); }
                    public void load() { }
                    public void unload() { }
                };
            }
        };
        plugin.config().load();
        plugin.config().main().node("api", "server-id").set(2);
        plugin.config().main().node("server-data-sender", "enabled").set(true);
        plugin.config().main().node("server-data-sender", "interval").set("PT30S");
        plugin.setAudienceProvider(new AbstractAudienceProvider() {
            public NamelessConsole console() { throw new AssertionError("No console needed"); }
            public Audience broadcast() { return Audience.empty(); }
            public NamelessPlayer player(UUID id) { return null; }
            public NamelessPlayer playerByUsername(String name) { return null; }
            public Collection<NamelessPlayer> onlinePlayers() { return List.of(); }
        });
        return plugin;
    }

    private static final class Sender extends AbstractDataSender {
        int captures;
        String value = "clean";
        final List<JsonObject> sent = new ArrayList<>();
        Sender(NamelessPlugin plugin) { super(plugin); }
        protected void registerCustomProviders() {
            registerGlobalInfoProvider(json -> { captures++; json.addProperty("testProvider", value); });
        }
        protected int heartbeatCapacity() { return 50; }
        protected String heartbeatMotd() { return "Patriam"; }
        protected void send(JsonObject data) { sent.add(data.deepCopy()); }
    }

    private static final class QueueScheduler extends AbstractScheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();
        int timers;
        void next() { queue.remove().run(); }
        public void runAsync(Runnable task) { queue.add(task); }
        public void runSync(Runnable task) { queue.add(task); }
        public AbstractScheduledTask runTimer(Runnable task, Duration interval) {
            timers++;
            return new AbstractScheduledTask() { public void cancel() { } };
        }
        public AbstractScheduledTask runDelayed(Runnable task, Duration delay) {
            queue.add(task);
            return new AbstractScheduledTask() { public void cancel() { } };
        }
    }
}
