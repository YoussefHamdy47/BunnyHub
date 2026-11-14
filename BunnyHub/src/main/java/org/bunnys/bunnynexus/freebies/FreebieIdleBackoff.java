package org.bunnys.bunnynexus.freebies;

import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * A tick that reschedules itself on one executor thread: after a busy tick it runs again after {@code busy}; after
 * each idle tick in a row it waits one step longer through {@code idle}, staying at the last step. {@link #wake()}
 * runs one tick at once and starts again from the first step.
 *
 * <p>An idle queue costs database round trips per poll, so polling slows down while nothing is due and every place
 * that queues work due now calls {@code wake()}. Work that becomes due by time alone (a retry, a reminder) is found
 * at most one last step late.
 */
final class FreebieIdleBackoff {
    private final ScheduledExecutorService executor;
    private final String name;
    private final Duration busy;
    private final List<Duration> idle;
    private final BooleanSupplier tick;
    private final AtomicBoolean wakeQueued = new AtomicBoolean();
    private long epoch;     // Executor thread only. A scheduled tick runs only while its epoch is current.
    private int idleTicks;  // Executor thread only.
    private volatile boolean started, closed;

    /** {@code tick} returns true when it found work (or polling costs nothing right now), false when it was idle. */
    FreebieIdleBackoff(ScheduledExecutorService executor, String name, Duration busy, List<Duration> idle, BooleanSupplier tick) {
        if (idle.isEmpty()) throw new IllegalArgumentException("At least one idle delay is required.");
        this.executor = executor; this.name = name; this.busy = busy; this.idle = List.copyOf(idle); this.tick = tick;
    }

    /** Runs the first tick after {@code initial}. Until this is called, {@link #wake()} does nothing. */
    void start(Duration initial) {
        started = true;
        try { executor.schedule(() -> run(0), initial.toMillis(), TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException shuttingDown) { /* Closed before it started. */ }
    }

    /**
     * Work is due now: one tick as soon as the thread is free, then polling from the first step again. Safe from any
     * thread; it only queues a task and never blocks. Calls made before that tick starts share it.
     */
    void wake() {
        if (!started || closed || !wakeQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                idleTicks = 0;
                run(++epoch); // The tick scheduled earlier is now stale and will do nothing.
            });
        } catch (RejectedExecutionException shuttingDown) { wakeQueued.set(false); }
    }

    void close() { closed = true; }

    private void run(long mine) {
        if (closed || mine != epoch) return;
        // Cleared before the tick: work queued while it runs gets a tick of its own.
        wakeQueued.set(false);
        boolean found = false;
        try { found = tick.getAsBoolean(); }
        catch (Throwable failure) {
            // A failing database is polled more slowly, like an idle one; the next tick tries again.
            BunnyLog.error("[Freebies] " + name + " failed | " + FailureDiagnostics.describe(failure));
        }
        idleTicks = found ? 0 : Math.min(idleTicks + 1, idle.size());
        Duration delay = found ? busy : idle.get(idleTicks - 1);
        long next = ++epoch;
        if (closed) return;
        try { executor.schedule(() -> run(next), delay.toMillis(), TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException shuttingDown) { /* Leases are recovered on the next start. */ }
    }
}
