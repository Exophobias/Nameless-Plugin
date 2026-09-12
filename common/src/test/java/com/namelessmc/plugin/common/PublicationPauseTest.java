package com.namelessmc.plugin.common;

import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PublicationPauseTest {
    @TempDir Path temp;
    private final UUID token = UUID.randomUUID();

    @Test void emptyOwnerPausesDurablyAndOnlyExactOwnerCanResume() throws Exception {
        PublicationPause pause = new PublicationPause(temp);
        pause.acquire("test_1", token).toCompletableFuture().join();
        byte[] held = Files.readAllBytes(temp.resolve("publication-pause.state"));
        assertEquals("PAUSED", pause.status().get("state"));
        assertFalse(pause.run(() -> fail("paused admission")));
        assertFalse(pause.release("test_1", UUID.randomUUID()));
        assertFalse(pause.release("test_2", token));
        assertArrayEquals(held, Files.readAllBytes(temp.resolve("publication-pause.state")));
        assertTrue(pause.release("test_1", token));
        assertTrue(pause.release("test_1", token));
        assertEquals("CLOSED", pause.status().get("state"));
        assertThrows(IllegalStateException.class, () -> pause.acquire("test_1", token));
        assertTrue(pause.run(() -> { }));
    }

    @Test void queuedFetchDispatchAndAckMustAllFinishBeforeReadiness() {
        PublicationPause pause = new PublicationPause(temp);
        QueueScheduler queue = new QueueScheduler();
        PublicationScheduler scheduler = new PublicationScheduler(queue, pause);
        List<String> calls = new ArrayList<>();
        scheduler.runAsync(() -> {
            calls.add("fetch");
            scheduler.runSync(() -> {
                calls.add("dispatch");
                scheduler.runAsync(() -> calls.add("ack"));
            });
        });
        var ready = pause.acquire("store_test", token).toCompletableFuture();
        assertFalse(ready.isDone());
        assertEquals("DRAINING", pause.status().get("state"));
        scheduler.runAsync(() -> fail("a new fetch must not be queued"));
        assertEquals(1, queue.tasks.size());
        queue.next();
        assertEquals(List.of("fetch"), calls);
        assertFalse(ready.isDone());
        queue.next();
        assertEquals(List.of("fetch", "dispatch"), calls);
        assertFalse(ready.isDone());
        assertFalse(pause.release("store_test", token));
        queue.next();
        assertEquals(List.of("fetch", "dispatch", "ack"), calls);
        ready.join();
        assertEquals("0", pause.status().get("inFlight"));
    }

    @Test void releaseHookRunsAfterDrainAndOnlyOnTheFirstSuccessfulRelease() {
        PublicationPause pause = new PublicationPause(temp);
        PublicationPause.Work queued = Objects.requireNonNull(pause.prepare(() -> { }));
        pause.acquire("release_cursor", token);
        AtomicInteger cursor = new AtomicInteger();
        assertFalse(pause.release("release_cursor", token, () -> { cursor.incrementAndGet(); return true; }));
        assertEquals(0, cursor.get());
        queued.run();
        assertTrue(pause.release("release_cursor", token, () -> { cursor.incrementAndGet(); return true; }));
        assertEquals(1, cursor.get());
        PublicationPause.Work normal = Objects.requireNonNull(pause.prepare(() -> { }));
        assertTrue(pause.release("release_cursor", token, () -> { fail("CLOSED retry must not move log cursor"); return true; }));
        normal.run();
    }

    @Test void repeatingTasksResumeWithoutRecreatingTimerOrCapturingDuringPause() {
        PublicationPause pause = new PublicationPause(temp);
        QueueScheduler queue = new QueueScheduler();
        PublicationScheduler scheduler = new PublicationScheduler(queue, pause);
        AtomicInteger captures = new AtomicInteger();
        scheduler.runTimer(captures::incrementAndGet, Duration.ofSeconds(1));
        Runnable timer = queue.tasks.remove();
        timer.run();
        assertEquals(1, captures.get());
        pause.acquire("timer_test", token).toCompletableFuture().join();
        timer.run();
        assertEquals(1, captures.get());
        assertTrue(pause.release("timer_test", token));
        timer.run();
        assertEquals(2, captures.get());
    }

    @Test void delayedCancellationReleasesQueuedWorkExactlyOnce() {
        PublicationPause pause = new PublicationPause(temp);
        QueueScheduler queue = new QueueScheduler();
        PublicationScheduler scheduler = new PublicationScheduler(queue, pause);
        AbstractScheduledTask delayed = scheduler.runDelayed(() -> fail("cancelled"), Duration.ofSeconds(1));
        var ready = pause.acquire("delayed_test", token).toCompletableFuture();
        assertFalse(ready.isDone());
        delayed.cancel();
        delayed.cancel();
        ready.join();
        queue.next();
        assertEquals("0", pause.status().get("inFlight"));
    }

    @Test void submissionFailureAndThrowingCallbackCannotStrandOrLeakActivity() {
        PublicationPause pause = new PublicationPause(temp);
        QueueScheduler queue = new QueueScheduler();
        queue.reject = true;
        PublicationScheduler scheduler = new PublicationScheduler(queue, pause);
        assertThrows(IllegalStateException.class, () -> scheduler.runAsync(() -> { }));
        assertEquals("0", pause.status().get("inFlight"));
        queue.reject = false;
        scheduler.runAsync(() -> { throw new IllegalStateException("callback"); });
        var ready = pause.acquire("exception_test", token).toCompletableFuture();
        assertThrows(IllegalStateException.class, queue::next);
        ready.join();
        assertEquals("0", pause.status().get("inFlight"));
    }

    @Test void aNewProcessRetainsPauseAndCanResumeWithOriginalIdentity() {
        PublicationPause first = new PublicationPause(temp, "first-process");
        first.acquire("restart_test", token).toCompletableFuture().join();
        PublicationPause second = new PublicationPause(temp, "second-process");
        assertEquals("PAUSED", second.status().get("state"));
        assertFalse(second.run(() -> fail("restart must retain isolation")));
        second.acquire("restart_test", token).toCompletableFuture().join();
        assertTrue(second.release("restart_test", token));
    }

    @Test void sameProcessReplacementCannotPretendOldAsyncCallbacksHaveStopped() {
        PublicationPause first = new PublicationPause(temp, "same-process");
        first.prepare(() -> { });
        first.acquire("reload_test", token);
        PublicationPause second = new PublicationPause(temp, "same-process");
        assertEquals("BLOCKED", second.status().get("state"));
        assertFalse(second.release("reload_test", token));
        assertFalse(second.run(() -> fail("retired owner callbacks remain uncertain")));
    }

    @Test void recoveredPauseRecordsTheNewProcessBeforeAnySameProcessReplacement() {
        PublicationPause original = new PublicationPause(temp, "original-process");
        original.acquire("restart_then_reload", token).toCompletableFuture().join();
        PublicationPause restarted = new PublicationPause(temp, "restarted-process");
        assertEquals("PAUSED", restarted.status().get("state"));
        PublicationPause replaced = new PublicationPause(temp, "restarted-process");
        assertEquals("BLOCKED", replaced.status().get("state"));
        assertFalse(replaced.release("restart_then_reload", token));
    }

    @Test void corruptedMarkerAndUnfinishedTempNeverOpenPublishing() throws Exception {
        Files.writeString(temp.resolve("publication-pause.state"), "bad record");
        PublicationPause malformed = new PublicationPause(temp);
        assertEquals("BLOCKED", malformed.status().get("state"));
        assertFalse(malformed.run(() -> fail("malformed marker")));
        Path other = Files.createDirectory(temp.resolve("unfinished"));
        Files.writeString(other.resolve("publication-pause.state.part"), "partial");
        assertEquals("BLOCKED", new PublicationPause(other).status().get("state"));
    }

    @Test void externalMarkerRemovalDoesNotResumeTheLiveOwner() throws Exception {
        PublicationPause pause = new PublicationPause(temp);
        pause.acquire("removed_test", token).toCompletableFuture().join();
        Files.delete(temp.resolve("publication-pause.state"));
        assertFalse(pause.release("removed_test", token));
        assertEquals("BLOCKED", pause.status().get("state"));
        assertFalse(pause.run(() -> fail("removed marker")));
    }

    @Test void disablingDuringDrainNeverReportsReady() {
        PublicationPause pause = new PublicationPause(temp);
        PublicationPause.Work queued = pause.prepare(() -> { });
        var ready = pause.acquire("disable_test", token).toCompletableFuture();
        pause.disable();
        queued.cancelBeforeStart();
        assertTrue(ready.isCompletedExceptionally());
        assertFalse(pause.release("disable_test", token));
    }

    @Test void callerCannotCompleteTheOwnersDrainFuture() {
        PublicationPause pause = new PublicationPause(temp);
        PublicationPause.Work queued = pause.prepare(() -> { });
        var caller = pause.acquire("future_test", token).toCompletableFuture();
        caller.complete(null);
        assertEquals("DRAINING", pause.status().get("state"));
        assertFalse(pause.release("future_test", token));
        queued.run();
        assertEquals("PAUSED", pause.status().get("state"));
    }

    @Test void heartbeatContainsOnlyLivenessAndNoFixtureOrPlaceholderPayload() {
        var body = AbstractDataSender.heartbeatBody(2, Duration.ofSeconds(30));
        assertEquals(Set.of("server_id", "server-id", "interval_seconds", "time", "players", "max_players", "motd"), body.keySet());
        assertEquals(0, body.getAsJsonObject("players").size());
        assertEquals(2, body.get("server-id").getAsInt());
        assertEquals(30, body.get("interval_seconds").getAsInt());
        assertTrue(body.get("time").getAsLong() > 0);
    }

    private static final class QueueScheduler extends AbstractScheduler {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        void next() { tasks.remove().run(); }
        public void runAsync(Runnable task) {
            if (reject) throw new IllegalStateException("Rejected submission");
            tasks.add(task);
        }
        public void runSync(Runnable task) { runAsync(task); }
        public AbstractScheduledTask runTimer(Runnable task, Duration interval) { return runDelayed(task, interval); }
        public AbstractScheduledTask runDelayed(Runnable task, Duration delay) {
            runAsync(task);
            return new AbstractScheduledTask() { public void cancel() { } };
        }
    }
}
