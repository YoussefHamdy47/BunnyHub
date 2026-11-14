package org.bunnys.bunnynexus.freebies;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A scheduler with its own clock for tests: nothing runs until the test calls {@link #runDue()} or
 * {@link #advance(Duration)}, and every task then runs on the calling thread at its due time. Tasks may be submitted
 * from any thread. No sleeping, no real time.
 */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Task<?>> queue = new PriorityQueue<>();
    private Instant now;
    private long sequence;
    private boolean shutdown;

    ManualScheduler(Instant start) { now = start; }

    /** The scheduler's time as a clock, for the code under test. */
    Clock clock() {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now(); }
        };
    }

    synchronized Instant now() { return now; }

    /** Tasks waiting, due or not, not counting cancelled ones. */
    synchronized int pending() { return (int) queue.stream().filter(t -> !t.isCancelled()).count(); }

    /** Runs everything due at the current time, including tasks those tasks submit for now. */
    void runDue() { advance(Duration.ZERO); }

    /** Moves time forward, running each task at its own due time on the way. */
    void advance(Duration by) {
        Instant until = now().plus(by);
        while (true) {
            Task<?> next;
            synchronized (this) {
                next = queue.peek();
                if (next == null || next.due.isAfter(until)) { now = until; return; }
                queue.poll();
                if (next.due.isAfter(now)) now = next.due;
            }
            next.run();
        }
    }

    private synchronized <T> Task<T> add(Callable<T> work, long delay, TimeUnit unit) {
        if (shutdown) throw new RejectedExecutionException("shut down");
        var task = new Task<>(work, now.plusNanos(Math.max(0, unit.toNanos(delay))), sequence++);
        queue.add(task);
        return task;
    }

    @Override public void execute(Runnable command) { schedule(command, 0, TimeUnit.NANOSECONDS); }
    @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return add(() -> { command.run(); return null; }, delay, unit);
    }
    @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { return add(callable, delay, unit); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("Not used by the code under test.");
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Not used by the code under test.");
    }
    @Override public synchronized void shutdown() { shutdown = true; }
    @Override public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> left = new ArrayList<>(queue);
        queue.clear();
        return left;
    }
    @Override public synchronized boolean isShutdown() { return shutdown; }
    @Override public synchronized boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }

    private final class Task<T> extends FutureTask<T> implements ScheduledFuture<T>, Comparable<Delayed> {
        final Instant due;
        final long order;
        Task(Callable<T> work, Instant due, long order) { super(work); this.due = due; this.order = order; }

        @Override public void run() {
            super.run();
            // Like a real worker thread, a task's failure must not vanish silently in a test.
            try { if (!isCancelled()) get(); }
            catch (ExecutionException failure) { throw new IllegalStateException("Scheduled task failed", failure.getCause()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(Duration.between(now(), due)); }
        @Override public int compareTo(Delayed other) {
            Task<?> that = (Task<?>) other;
            int byTime = due.compareTo(that.due);
            return byTime != 0 ? byTime : Long.compare(order, that.order);
        }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }
}
