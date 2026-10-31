package org.bunnys.utils;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStatsTest {
    private static final long MB = 1024 * 1024;

    @Test void readsPlausibleValues() {
        var keep = ByteBuffer.allocateDirect((int) (2 * MB));
        var stats = MemoryStats.read();
        assertTrue(stats.heapUsed() > 0 && stats.heapUsed() <= stats.heapCommitted(), stats.line());
        assertTrue(stats.heapMax() <= 0 || stats.heapCommitted() <= stats.heapMax(), stats.line());
        assertTrue(stats.metaspaceUsed() > 4 * MB, "loaded classes take metaspace: " + stats.line());
        assertTrue(stats.codeCacheUsed() > 0, stats.line());
        assertTrue(stats.threads() >= 1 && stats.threads() < 10_000, stats.line());
        assertTrue(stats.directBuffers() >= 2 * MB, "the direct buffer above is counted: " + stats.line());
        assertEquals(2 * MB, keep.capacity());
        stats.residentBytes().ifPresent(rss -> assertTrue(rss > stats.heapUsed() / 2, stats.line()));
    }

    @Test void lineIsInMegabytes() {
        var stats = new MemoryStats.Snapshot(100 * MB, 128 * MB, 384 * MB, 70 * MB, 20 * MB, 42, 3 * MB, OptionalLong.of(250 * MB));
        assertEquals("heap 100/128 MB (max 384) • metaspace 70 MB • code cache 20 MB • threads 42 • direct buffers 3 MB"
                + " • resident 250 MB", stats.line());
        var noRss = new MemoryStats.Snapshot(MB, MB, MB, MB, MB, 1, 0, OptionalLong.empty());
        assertFalse(noRss.line().contains("resident"));
    }

    @Test void parsesProcStatusKilobytes() {
        assertEquals(OptionalLong.of(256_000L * 1024), MemoryStats.parseKb("VmRSS:\t  256000 kB"));
        assertTrue(MemoryStats.parseKb("VmRSS: lots").isEmpty());
    }

    @Test void jumpNeedsBothSixtyFourMegabytesAndHalfAgain() {
        assertTrue(MemoryStats.jumped(heap(80), heap(150)));
        assertFalse(MemoryStats.jumped(heap(80), heap(130)), "50 MB is ordinary GC swing");
        assertFalse(MemoryStats.jumped(heap(200), heap(280)), "80 MB on 200 MB is under half");
        assertFalse(MemoryStats.jumped(heap(150), heap(80)), "a drop is not a jump");
    }

    private static MemoryStats.Snapshot heap(long usedMb) {
        return new MemoryStats.Snapshot(usedMb * MB, 384 * MB, 384 * MB, 0, 0, 1, 0, OptionalLong.empty());
    }
}
