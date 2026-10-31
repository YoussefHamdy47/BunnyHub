package org.bunnys.services;

import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.MemoryStats;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Logs one memory line at startup, every hour, and whenever heap use jumps sharply between hourly lines, so a
 * Railway memory spike can be matched to the JVM part that grew. Reads MXBeans only; one daemon thread.
 */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class MemoryReport implements BunnyService {
    static final Duration CHECK = Duration.ofMinutes(1);
    static final Duration HOURLY = Duration.ofHours(1);

    private ScheduledExecutorService scheduler;
    private MemoryStats.Snapshot lastLogged;
    private long lastLoggedAt;

    @Override public String name() { return "memory report"; }

    @Override public synchronized void start(BunnyHub client) {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("BunnyMemory").daemon().factory());
        scheduler.scheduleWithFixedDelay(this::check, 0, CHECK.toSeconds(), TimeUnit.SECONDS);
    }

    /** Reads once and logs when an hour has passed or the heap jumped. Never throws into the scheduler. */
    void check() {
        try {
            var now = MemoryStats.read();
            long at = System.nanoTime();
            String reason = due(now, at);
            if (reason == null) return;
            BunnyLog.info("[Memory] " + reason + ": " + now.line());
            lastLogged = now;
            lastLoggedAt = at;
        } catch (RuntimeException failure) {
            BunnyLog.warning("[Memory] Could not read memory use | " + failure.getClass().getSimpleName());
        }
    }

    /** Why a line is due now, or null. */
    String due(MemoryStats.Snapshot now, long atNanos) {
        if (lastLogged == null) return "startup";
        if (atNanos - lastLoggedAt >= HOURLY.toNanos()) return "hourly";
        if (MemoryStats.jumped(lastLogged, now)) return "heap jump";
        return null;
    }

    @Override public synchronized void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
