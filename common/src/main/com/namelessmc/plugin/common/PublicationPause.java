package com.namelessmc.plugin.common;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Owner-held durable publication pause. It neither edits configuration nor depends on a harness. */
@SuppressWarnings({"nullness", "initialization"})
public final class PublicationPause {
    private static final String PROCESS = ProcessHandle.current().pid() + "@"
            + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("unknown");
    private final Path file;
    private final Path pending;
    private final String process;
    private final ThreadLocal<Work> current = new ThreadLocal<>();
    private @Nullable byte[] expected;
    private String runId = "";
    private String capability = "";
    private boolean paused;
    private boolean blocked;
    private boolean disabled;
    private int inFlight;
    private CompletableFuture<Void> drained = CompletableFuture.completedFuture(null);

    public PublicationPause(Path dataDirectory) {
        this(dataDirectory, PROCESS);
    }

    PublicationPause(Path dataDirectory, String process) {
        this.process = Objects.requireNonNull(process);
        file = dataDirectory.toAbsolutePath().normalize().resolve("publication-pause.state");
        pending = file.resolveSibling("publication-pause.state.part");
        try {
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unfinished pause write");
            expected = read();
            if (expected != null) {
                String[] fields = new String(expected, StandardCharsets.UTF_8).split("\n", -1);
                if (fields.length != 6 || !fields[0].equals("1") || !fields[5].isEmpty()
                        || (!fields[1].equals("ACTIVE") && !fields[1].equals("CLOSED")))
                    throw new IOException("Invalid pause record");
                validate(fields[2], UUID.fromString(fields[3]));
                if (!UUID.fromString(fields[3]).toString().equals(fields[3]) || fields[4].isEmpty())
                    throw new IOException("Invalid pause identity");
                runId = fields[2];
                capability = fields[3];
                paused = fields[1].equals("ACTIVE");
                // A fresh process has no surviving callbacks. Re-enabling in the same JVM cannot
                // make that assertion about the retired plugin's classloader or scheduler tasks.
                blocked = paused && fields[4].equals(process);
                if (paused && !blocked) persist("ACTIVE", runId, UUID.fromString(capability));
            }
        } catch (IOException | RuntimeException failure) {
            paused = true;
            blocked = true;
        }
    }

    public synchronized CompletionStage<Void> acquire(String owner, UUID token) {
        validate(owner, token);
        try {
            verify();
            if (blocked || disabled) throw new IllegalStateException("Publication pause requires repair");
            if (paused) {
                if (!matches(owner, token)) throw new IllegalStateException("Publication pause has another owner");
                return drained.minimalCompletionStage();
            }
            if (matches(owner, token)) throw new IllegalStateException("That publication pause is already closed");
            paused = true; // Refuse new admissions even if persistence fails.
            persist("ACTIVE", owner, token);
            runId = owner;
            capability = token.toString();
            drained = new CompletableFuture<>();
            if (inFlight == 0) drained.complete(null);
            return drained.minimalCompletionStage();
        } catch (IOException failure) {
            blocked = true;
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot persist publication pause", failure));
        }
    }

    public synchronized boolean release(String owner, UUID token) {
        return release(owner, token, () -> true);
    }

    synchronized boolean release(String owner, UUID token, java.util.function.BooleanSupplier beforeRelease) {
        validate(owner, token);
        try {
            verify();
            if (blocked || disabled || !matches(owner, token)) return false;
            if (!paused) return true; // Exact CLOSED acknowledgement can be retried.
            if (inFlight != 0 || !beforeRelease.getAsBoolean()) return false;
            persist("CLOSED", owner, token);
            paused = false;
            return true;
        } catch (IOException failure) {
            blocked = true;
            return false;
        }
    }

    public synchronized Map<String, String> status() {
        try { verify(); } catch (IOException failure) { blocked = true; }
        String state = blocked || disabled ? "BLOCKED" : paused
                ? (inFlight == 0 ? "PAUSED" : "DRAINING") : runId.isEmpty() ? "OPEN" : "CLOSED";
        return Map.of("protocol", "1", "state", state, "runId", runId,
                "capability", capability, "inFlight", Integer.toString(inFlight));
    }

    public synchronized boolean isPaused() { return paused || blocked || disabled; }

    public synchronized void disable() {
        disabled = true;
        if (!drained.isDone()) drained.completeExceptionally(new IllegalStateException("Publisher disabled during drain"));
    }

    /** Reserve queued work before handing it to another executor; children inherit the same drain. */
    public synchronized @Nullable Work prepare(Runnable task) {
        Work parent = current.get();
        if (disabled || blocked || (paused && (parent == null || !parent.running()))) return null;
        inFlight++;
        return new Work(task);
    }

    public boolean run(Runnable task) {
        Work work = prepare(task);
        if (work == null) return false;
        work.run();
        return true;
    }

    public final class Work implements Runnable {
        private final Runnable task;
        private final AtomicInteger phase = new AtomicInteger(); // queued, executing, finished
        private Work(Runnable task) { this.task = Objects.requireNonNull(task); }
        private boolean running() { return phase.get() == 1; }
        public void cancelBeforeStart() {
            if (phase.compareAndSet(0, 2)) finished();
        }
        @Override public void run() {
            if (!phase.compareAndSet(0, 1)) return;
            Work previous = current.get();
            current.set(this);
            try { task.run(); }
            finally {
                if (previous == null) current.remove(); else current.set(previous);
                phase.set(2);
                finished();
            }
        }
    }

    private synchronized void finished() {
        if (--inFlight < 0) throw new IllegalStateException("Publication work count underflow");
        if (paused && !blocked && !disabled && inFlight == 0) drained.complete(null);
    }

    private boolean matches(String owner, UUID token) {
        return runId.equals(owner) && capability.equals(token.toString());
    }
    private static void validate(String owner, UUID token) {
        Objects.requireNonNull(token);
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid publication pause owner");
    }
    private @Nullable byte[] read() throws IOException {
        BasicFileAttributes attrs;
        try { attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return null; }
        if (!attrs.isRegularFile() || attrs.size() > 1024) throw new IOException("Invalid pause file");
        byte[] bytes;
        try (java.io.InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(1025);
        }
        if (bytes.length > 1024) throw new IOException("Oversize pause file");
        return bytes;
    }
    private void verify() throws IOException {
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(expected, read()))
            throw new IOException("Publication pause record changed outside its owner");
    }
    private void persist(String phase, String owner, UUID token) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bytes = ("1\n" + phase + "\n" + owner + "\n" + token + "\n" + process + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        if (!Arrays.equals(expected, read())) throw new IOException("Publication pause source changed");
        Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // Opening directories is supported on the Linux game host. Windows denies directory
        // channels; atomic replacement plus file force remains available for local owner tests.
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        }
        expected = bytes;
        if (!Arrays.equals(bytes, read())) throw new IOException("Publication pause readback differs");
    }
}
