package com.namelessmc.plugin.common;

import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.time.Duration;

/** Tracks entire nested scheduler chains, including queued Store execution and acknowledgements. */
public final class PublicationScheduler extends AbstractScheduler {
    private final AbstractScheduler delegate;
    private final PublicationPause pause;
    public PublicationScheduler(AbstractScheduler delegate, PublicationPause pause) {
        this.delegate = delegate;
        this.pause = pause;
    }
    @Override public void runAsync(Runnable runnable) {
        PublicationPause.Work work = pause.prepare(runnable);
        if (work == null) return;
        try { delegate.runAsync(work); }
        catch (RuntimeException | Error failure) { work.cancelBeforeStart(); throw failure; }
    }
    @Override public void runSync(Runnable runnable) {
        PublicationPause.Work work = pause.prepare(runnable);
        if (work == null) return;
        try { delegate.runSync(work); }
        catch (RuntimeException | Error failure) { work.cancelBeforeStart(); throw failure; }
    }
    @Override public @Nullable AbstractScheduledTask runTimer(Runnable runnable, Duration interval) {
        return delegate.runTimer(() -> pause.run(runnable), interval);
    }
    @Override public @Nullable AbstractScheduledTask runDelayed(Runnable runnable, Duration delay) {
        PublicationPause.Work work = pause.prepare(runnable);
        if (work == null) return null;
        try {
            AbstractScheduledTask scheduled = delegate.runDelayed(work, delay);
            if (scheduled == null) { work.cancelBeforeStart(); return null; }
            return new AbstractScheduledTask() {
                @Override public void cancel() { scheduled.cancel(); work.cancelBeforeStart(); }
            };
        } catch (RuntimeException | Error failure) { work.cancelBeforeStart(); throw failure; }
    }
}
