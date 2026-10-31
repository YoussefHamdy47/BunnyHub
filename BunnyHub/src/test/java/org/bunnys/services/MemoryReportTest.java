package org.bunnys.services;

import org.bunnys.utils.MemoryStats;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class MemoryReportTest {
    private static final long MB = 1024 * 1024;

    @Test void logsAtStartupHourlyAndOnAHeapJump() {
        var report = new MemoryReport();
        assertEquals("startup", report.due(heap(80), 0));
        report.check(); // Logs the startup line and remembers it.
        var logged = MemoryStats.read();
        long hour = MemoryReport.HOURLY.toNanos();
        // Due decisions are relative to the last logged line, whatever its time.
        assertNull(report.due(logged, System.nanoTime()));
        assertEquals("hourly", report.due(logged, System.nanoTime() + hour));
        var jumped = new MemoryStats.Snapshot(logged.heapUsed() * 2 + 64 * MB, 0, 0, 0, 0, 1, 0, OptionalLong.empty());
        assertEquals("heap jump", report.due(jumped, System.nanoTime()));
    }

    @Test void lineIsPrintedEvenWithActionLogsOff() {
        var out = new java.io.ByteArrayOutputStream();
        var original = System.out;
        org.bunnys.utils.BunnyLog.setLogActions(false); // As Main runs the bot.
        try {
            System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            new MemoryReport().check();
        } finally {
            System.setOut(original);
            org.bunnys.utils.BunnyLog.setLogActions(true);
        }
        String printed = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(printed.contains("[Memory] startup: heap "), printed);
    }

    private static MemoryStats.Snapshot heap(long usedMb) {
        return new MemoryStats.Snapshot(usedMb * MB, 0, 0, 0, 0, 1, 0, OptionalLong.empty());
    }
}
