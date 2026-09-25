package org.bunnys.handler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded FIFO work per key, with round-robin scheduling between ready keys. */
public final class InteractionExecutor extends AbstractExecutorService implements AutoCloseable {
    private record Job(Runnable action, long enqueuedAt) {}
    private static final class Lane {
        final Object key;
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        boolean running;
        Lane(Object key) { this.key = key; }
    }
    public record Snapshot(int capacity, int active, int queued, int keys, long accepted,
                           long completed, long failed, long rejected, long abandoned,
                           double averageQueueWaitMillis, double maxQueueWaitMillis, double oldestQueuedMillis) {}

    private final Object monitor = new Object();
    private final Map<Object, Lane> lanes = new HashMap<>();
    private final ArrayDeque<Lane> ready = new ArrayDeque<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int capacity;
    private final int perUserCapacity;
    private boolean shutdown;
    private boolean stopNow;
    private boolean workersStarted;
    private int active, queued, liveWorkers;
    private long accepted, completed, failed, rejected, abandoned, started, totalWaitNanos, maxWaitNanos;

    public InteractionExecutor() { this(24, 100); }
    public InteractionExecutor(int workers, int queueCapacity) { this("BunnyCommand", workers, queueCapacity); }

    public InteractionExecutor(String name, int workerCount, int queueCapacity) {
        this(name, workerCount, queueCapacity, Math.addExact(workerCount, queueCapacity));
    }

    /** Limit includes a user's running action and all waiting actions. */
    public InteractionExecutor(String name, int workerCount, int queueCapacity, int perUserCapacity) {
        if (workerCount < 1 || queueCapacity < 1) throw new IllegalArgumentException("Worker and queue sizes must be positive.");
        if (perUserCapacity < 1) throw new IllegalArgumentException("Per-user capacity must be positive.");
        this.perUserCapacity = perUserCapacity;
        capacity = Math.addExact(workerCount, queueCapacity);
        for (int i = 0; i < workerCount; i++) workers.add(Thread.ofPlatform().name(name + "-" + i).unstarted(this::runWorker));
    }

    public ExecutorService executor() { return this; }

    /** Serializes accepted work for exactly this user, without locking a worker while it waits. */
    public void submit(String userId, Runnable action) {
        enqueue(Objects.requireNonNull(userId, "userId"), action);
    }

    /** Independent work remains bounded too; this accessor cannot bypass admission limits. */
    @Override public void execute(Runnable action) { enqueue(new Object(), action); }

    private void enqueue(Object key, Runnable action) {
        Objects.requireNonNull(action, "action");
        synchronized (monitor) {
            if (shutdown || active + queued >= capacity) {
                rejected++;
                throw new RejectedExecutionException(shutdown ? "Executor is shut down." : "Interaction capacity reached.");
            }
            Lane lane = lanes.get(key);
            if (key instanceof String && lane != null && lane.jobs.size() + (lane.running ? 1 : 0) >= perUserCapacity) {
                rejected++;
                throw new RejectedExecutionException("Per-user interaction capacity reached.");
            }
            if (lane == null) {
                lane = new Lane(key);
                lanes.put(key, lane);
                ready.addLast(lane);
            }
            lane.jobs.addLast(new Job(action, System.nanoTime()));
            queued++;
            accepted++;
            if (!workersStarted) {
                workersStarted = true;
                liveWorkers = workers.size();
                workers.forEach(Thread::start);
            }
            monitor.notifyAll();
        }
    }

    private void runWorker() {
        try {
            while (true) {
                Lane lane;
                Job job;
                synchronized (monitor) {
                    while (ready.isEmpty() && !stopNow && !(shutdown && active + queued == 0)) {
                        try { monitor.wait(); } catch (InterruptedException ignored) { /* Recheck shutdown state. */ }
                    }
                    if (stopNow || (shutdown && active + queued == 0)) return;
                    lane = ready.removeFirst();
                    job = lane.jobs.removeFirst();
                    lane.running = true;
                    queued--;
                    active++;
                    long wait = System.nanoTime() - job.enqueuedAt();
                    totalWaitNanos += wait;
                    maxWaitNanos = Math.max(maxWaitNanos, wait);
                    started++;
                }
                boolean crashed = false;
                try { job.action().run(); }
                catch (Throwable error) {
                    crashed = true;
                    org.bunnys.utils.BunnyLog.error("Background action failed", error);
                } finally {
                    synchronized (monitor) {
                        active--;
                        lane.running = false;
                        completed++;
                        if (crashed) failed++;
                        if (lane.jobs.isEmpty()) lanes.remove(lane.key);
                        else ready.addLast(lane);
                        monitor.notifyAll();
                    }
                    // An individual task must not leave a recycled worker interrupted.
                    Thread.interrupted();
                }
            }
        } finally {
            synchronized (monitor) { liveWorkers--; monitor.notifyAll(); }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long now = System.nanoTime(), oldest = 0;
            for (Lane lane : lanes.values())
                if (!lane.jobs.isEmpty()) oldest = Math.max(oldest, now - lane.jobs.getFirst().enqueuedAt());
            return new Snapshot(capacity, active, queued, lanes.size(), accepted, completed, failed, rejected, abandoned,
                    started == 0 ? 0 : totalWaitNanos / 1_000_000.0 / started, maxWaitNanos / 1_000_000.0, oldest / 1_000_000.0);
        }
    }

    @Override public void shutdown() {
        synchronized (monitor) { shutdown = true; monitor.notifyAll(); }
    }

    @Override public List<Runnable> shutdownNow() {
        List<Runnable> pending = new ArrayList<>();
        synchronized (monitor) {
            shutdown = true;
            stopNow = true;
            for (Lane lane : lanes.values()) {
                for (Job job : lane.jobs) pending.add(job.action());
                lane.jobs.clear();
            }
            ready.clear();
            lanes.clear();
            abandoned += queued;
            queued = 0;
            monitor.notifyAll();
        }
        for (Runnable action : pending) if (action instanceof Future<?> future) future.cancel(false);
        workers.forEach(Thread::interrupt);
        return pending;
    }

    @Override public boolean isShutdown() { synchronized (monitor) { return shutdown; } }
    @Override public boolean isTerminated() { synchronized (monitor) { return shutdown && liveWorkers == 0; } }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout), start = System.nanoTime();
        synchronized (monitor) {
            while (!(shutdown && liveWorkers == 0)) {
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                remaining = unit.toNanos(timeout) - (System.nanoTime() - start);
            }
            return true;
        }
    }

    @Override public void close() {
        shutdown();
        try {
            if (!awaitTermination(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)) {
                int dropped = shutdownNow().size();
                org.bunnys.utils.BunnyLog.warning("Worker shutdown timed out; cancelled " + dropped + " queued actions.");
            }
        } catch (InterruptedException error) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
