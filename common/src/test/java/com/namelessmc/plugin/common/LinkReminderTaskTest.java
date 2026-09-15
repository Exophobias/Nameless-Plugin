package com.namelessmc.plugin.common;

import com.google.gson.JsonObject;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.event.NamelessJoinEvent;
import com.namelessmc.plugin.common.event.NamelessPlayerQuitEvent;
import com.namelessmc.plugin.common.logger.JulLogger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class LinkReminderTaskTest {
    @TempDir Path temp;

    @Test void waitsThirtyMinutesPerSessionAndSendsOnlyOnMainScheduler() {
        Fixture f = fixture();
        f.poll(29); assertTrue(f.task.sent.isEmpty());
        f.poll(30); assertEquals(1, f.task.sent.size());
        f.poll(59); assertEquals(1, f.task.sent.size());
        f.poll(60); assertEquals(2, f.task.sent.size());
        assertEquals(Duration.ofMinutes(1), f.scheduler.interval);
    }

    @Test void currentForumSettingsControlIntervalMessagesAndDisable() {
        Fixture f = fixture();
        f.task.minutes = 5;
        f.task.state = "discord_missing";
        f.poll(5);
        assertEquals(Component.text("Discord <click:run_command:'/op me'>literal</click>"),
                f.task.sent.get(0).children().get(0).color(null));
        assertEquals("https://forums.patriam.cc/user/connections/",
                f.task.sent.get(0).children().get(1).clickEvent().value());
        assertNull(f.task.sent.get(0).children().get(0).clickEvent());
        f.task.enabled = false; f.poll(10); assertEquals(1, f.task.sent.size());
        f.task.enabled = true; f.poll(14); assertEquals(1, f.task.sent.size());
        f.poll(15); assertEquals(2, f.task.sent.size());
    }

    @Test void completedLinksStopRemindersAndUnknownOrOutageCannotAccusePlayer() {
        Fixture f = fixture();
        f.task.state = "complete"; f.poll(30);
        f.task.state = "unknown"; f.poll(60);
        f.task.state = "unlinked"; f.task.fail = true; f.poll(61);
        assertTrue(f.task.sent.isEmpty());
        f.task.fail = false; f.poll(62); assertEquals(1, f.task.sent.size());
        f.task.state = "complete"; f.poll(92); assertEquals(1, f.task.sent.size());
    }

    @Test void delayedReplyCannotCrossDisconnectRejoinOrReload() {
        Fixture f = fixture();
        f.task.time = Duration.ofMinutes(30).toNanos(); f.task.run(); f.scheduler.next();
        f.plugin.events().post(new NamelessPlayerQuitEvent(f.uuid));
        f.plugin.events().post(new NamelessJoinEvent(new NamelessPlayer(f.plugin.config(), Audience.empty(), f.uuid, "test") {
            public boolean hasPermission(Permission permission) { return false; }
        }));
        f.scheduler.drain(); assertTrue(f.task.sent.isEmpty());
        f.poll(59); assertTrue(f.task.sent.isEmpty());
        f.task.time = Duration.ofMinutes(60).toNanos(); f.task.run(); f.scheduler.next();
        f.task.unload(); f.task.load(); f.scheduler.drain();
        assertTrue(f.task.sent.isEmpty());
        f.poll(90); assertEquals(1, f.task.sent.size());
    }

    @Test void staleRepliesAndOfflineRecipientsAreSkipped() {
        Fixture f = fixture();
        f.task.time = Duration.ofMinutes(30).toNanos(); f.task.run(); f.scheduler.next();
        f.task.time = Duration.ofMinutes(33).toNanos(); f.scheduler.drain();
        assertTrue(f.task.sent.isEmpty());
        f.task.run(); f.scheduler.next(); f.task.online.clear(); f.scheduler.drain();
        assertTrue(f.task.sent.isEmpty());
    }

    @Test void publicationPausePreventsRequestsAndDoesNotLeaveWorkerStuck() {
        Fixture f = fixture();
        UUID capability = UUID.randomUUID();
        f.plugin.publicationPause().acquire("reminder_test", capability).toCompletableFuture().join();
        f.poll(30); assertEquals(0, f.task.requests);
        assertTrue(f.plugin.publicationPause().release("reminder_test", capability));
        f.poll(31); assertEquals(1, f.task.requests); assertEquals(1, f.task.sent.size());
    }

    @Test void batchesAreBoundedAndOverlappingTicksDoNotDoubleSend() {
        Fixture f = fixture();
        for (int i = 0; i < 204; i++) f.task.online.add(UUID.randomUUID());
        f.poll(0); assertEquals(3, f.task.requests);
        f.task.time = Duration.ofMinutes(30).toNanos();
        f.task.run(); f.task.run(); f.scheduler.drain();
        assertEquals(205, f.task.sent.size());
        assertTrue(f.task.largestBatch <= 100);
    }

    @Test void malformedSettingsUnknownVersionsUnsafeLinksAndForeignPlayersAreRejected() {
        UUID id = UUID.randomUUID(); Set<UUID> ids = Set.of(id);
        String[] fields = {"protocol_version", "config_version", "interval_minutes", "enabled"};
        for (String field : fields) {
            JsonObject json = response(ids, "unlinked", true, 30);
            json.addProperty(field, "1");
            assertThrows(IllegalArgumentException.class, () -> LinkReminderResponse.parse(json, ids), field);
        }
        for (int interval : new int[]{0, -1, 1441}) {
            assertThrows(IllegalArgumentException.class,
                    () -> LinkReminderResponse.parse(response(ids, "unlinked", true, interval), ids));
        }
        JsonObject future = response(ids, "unlinked", true, 30); future.addProperty("config_version", 2);
        assertThrows(IllegalArgumentException.class, () -> LinkReminderResponse.parse(future, ids));
        for (String url : List.of("javascript:alert(1)", "http://example.com", "https://user:pass@example.com/")) {
            JsonObject json = response(ids, "unlinked", true, 30); json.addProperty("connections_url", url);
            assertThrows(IllegalArgumentException.class, () -> LinkReminderResponse.parse(json, ids));
        }
        for (String message : List.of("", "x\nspam", "x".repeat(501))) {
            JsonObject json = response(ids, "unlinked", true, 30);
            json.getAsJsonObject("messages").addProperty("unlinked", message);
            assertThrows(IllegalArgumentException.class, () -> LinkReminderResponse.parse(json, ids));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LinkReminderResponse.parse(response(Set.of(UUID.randomUUID()), "complete", true, 30), ids));
        JsonObject missing = response(Set.of(), "complete", true, 30);
        assertTrue(LinkReminderResponse.parse(missing, ids).players.isEmpty());
    }

    private Fixture fixture() {
        QueueScheduler scheduler = new QueueScheduler();
        NamelessPlugin plugin = new NamelessPlugin(temp, scheduler,
                config -> new JulLogger(config, Logger.getLogger("link-reminder-test")), null, "test", "test");
        plugin.config().load();
        UUID uuid = UUID.randomUUID();
        Task task = new Task(plugin, scheduler); task.online.add(uuid); task.load();
        return new Fixture(plugin, scheduler, task, uuid);
    }

    private static JsonObject response(Set<UUID> ids, String state, boolean enabled, int minutes) {
        JsonObject json = new JsonObject();
        json.addProperty("protocol_version", 1); json.addProperty("config_version", 1);
        json.addProperty("enabled", enabled); json.addProperty("interval_minutes", minutes);
        json.addProperty("connections_url", "https://forums.patriam.cc/user/connections/");
        JsonObject messages = new JsonObject();
        messages.addProperty("unlinked", "Link Minecraft and Discord to your forum account.");
        messages.addProperty("discord_missing", "Discord <click:run_command:'/op me'>literal</click>");
        json.add("messages", messages);
        JsonObject players = new JsonObject(); for (UUID id : ids) players.addProperty(id.toString(), state);
        json.add("players", players); return json;
    }

    private static final class Fixture {
        final NamelessPlugin plugin; final QueueScheduler scheduler; final Task task; final UUID uuid;
        Fixture(NamelessPlugin plugin, QueueScheduler scheduler, Task task, UUID uuid) {
            this.plugin = plugin; this.scheduler = scheduler; this.task = task; this.uuid = uuid;
        }
        void poll(int minutes) { task.time = Duration.ofMinutes(minutes).toNanos(); task.run(); scheduler.drain(); }
    }

    private static final class Task extends LinkReminderTask {
        final QueueScheduler scheduler; final Set<UUID> online = new HashSet<>();
        final List<Component> sent = new ArrayList<>();
        long time; String state = "unlinked"; boolean enabled = true, fail; int minutes = 30, requests, largestBatch;
        Task(NamelessPlugin plugin, QueueScheduler scheduler) { super(plugin); this.scheduler = scheduler; }
        @Override long now() { return time; }
        @Override Collection<UUID> online() { assertNotEquals("async", scheduler.mode); return online; }
        @Override LinkReminderResponse fetch(Set<UUID> ids) throws NamelessException {
            assertEquals("async", scheduler.mode); requests++; largestBatch = Math.max(largestBatch, ids.size());
            if (fail) throw new NamelessException("test outage");
            return LinkReminderResponse.parse(response(ids, state, enabled, minutes), ids);
        }
        @Override boolean send(UUID id, Component message) {
            assertEquals("sync", scheduler.mode);
            if (!online.contains(id)) return false;
            sent.add(message); return true;
        }
    }

    private static final class QueueScheduler extends AbstractScheduler {
        final Deque<Runnable> queue = new ArrayDeque<>(); String mode = "main"; Duration interval;
        void next() { queue.remove().run(); }
        void drain() { while (!queue.isEmpty()) next(); }
        private void enqueue(String phase, Runnable task) {
            queue.add(() -> { mode = phase; try { task.run(); } finally { mode = "main"; } });
        }
        public void runAsync(Runnable task) { enqueue("async", task); }
        public void runSync(Runnable task) { enqueue("sync", task); }
        public AbstractScheduledTask runTimer(Runnable task, Duration interval) {
            this.interval = interval; return new AbstractScheduledTask() { public void cancel() { } };
        }
        public AbstractScheduledTask runDelayed(Runnable task, Duration delay) {
            enqueue("sync", task); return new AbstractScheduledTask() { public void cancel() { } };
        }
    }
}
