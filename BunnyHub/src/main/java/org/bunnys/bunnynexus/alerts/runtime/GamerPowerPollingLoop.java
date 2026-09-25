package org.bunnys.bunnynexus.alerts.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Explicit lifecycle, inactive until start(). Inject a dedicated background scheduler, never the command executor. */
public final class GamerPowerPollingLoop implements AutoCloseable {
    public static final Duration TICK_INTERVAL = Duration.ofSeconds(15);
    public static final Duration POLL_INTERVAL = Duration.ofMinutes(5);
    private final ScheduledExecutorService executor;
    private final Supplier<GamerPowerPoller.Result> tick;
    private final Runnable stop;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
    private volatile GamerPowerPoller.Result lastResult;
    public GamerPowerPollingLoop(ScheduledExecutorService executor, GamerPowerPoller poller) {
        this(executor, poller::tick, poller::close);
    }
    GamerPowerPollingLoop(ScheduledExecutorService executor, Supplier<GamerPowerPoller.Result> tick, Runnable stop) {
        this.executor = Objects.requireNonNull(executor); this.tick = Objects.requireNonNull(tick); this.stop = Objects.requireNonNull(stop);
    }
    /** Call only after explicit schema verification/provisioning. The durable poller owns due time/backoff/pauses. */
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) throw new IllegalStateException("Polling loop already started or closed.");
        try {
            var future = executor.scheduleWithFixedDelay(this::runTick, 0, TICK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            scheduled.set(future);
            if (closed.get()) future.cancel(false);
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    private void runTick() {
        if (closed.get()) return;
        try { lastResult = Objects.requireNonNull(tick.get()); }
        catch (RuntimeException failure) {
            lastResult = GamerPowerPoller.Result.UNCERTAIN;
            org.slf4j.LoggerFactory.getLogger(GamerPowerPollingLoop.class).error("Discovery tick failed | {}",
                    org.bunnys.utils.FailureDiagnostics.describe(failure));
        }
    }
    /** Last tick result is diagnostic only; NOT the last successful provider fetch timestamp. */
    public Optional<GamerPowerPoller.Result> lastResult() { return Optional.ofNullable(lastResult); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var future = scheduled.get();
        if (future != null) future.cancel(false);
        stop.run(); // Fetcher cancellation retains its capacity until the running fetch actually terminates.
        // Executor belongs to the composition root, which drains/shuts it down separately.
    }
}
