package org.bunnys.utils;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * The JVM's memory use, read through the platform MXBeans: heap, class metadata, compiled code, threads and direct
 * buffers, plus the process's resident memory where Linux reports it (that is what Railway bills).
 */
public final class MemoryStats {
    private static final long MB = 1024 * 1024;
    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    private MemoryStats() {}

    /** Bytes, except {@code threads}; {@code residentBytes} is empty when the OS does not report it. */
    public record Snapshot(long heapUsed, long heapCommitted, long heapMax, long metaspaceUsed, long codeCacheUsed,
                           int threads, long directBuffers, OptionalLong residentBytes) {
        /** One log line, in MB. */
        public String line() {
            return "heap " + mb(heapUsed) + "/" + mb(heapCommitted) + " MB (max " + mb(heapMax) + ") • metaspace "
                    + mb(metaspaceUsed) + " MB • code cache " + mb(codeCacheUsed) + " MB • threads " + threads
                    + " • direct buffers " + mb(directBuffers) + " MB"
                    + (residentBytes.isPresent() ? " • resident " + mb(residentBytes.getAsLong()) + " MB" : "");
        }
    }

    public static Snapshot read() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long metaspace = 0, code = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.NON_HEAP) continue;
            String name = pool.getName();
            long used = pool.getUsage().getUsed();
            if (name.equals("Metaspace")) metaspace += used;
            else if (name.startsWith("CodeHeap") || name.equals("CodeCache")) code += used;
        }
        long direct = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            if (pool.getName().equals("direct")) direct += pool.getMemoryUsed();
        return new Snapshot(heap.getUsed(), heap.getCommitted(), heap.getMax(), metaspace, code,
                ManagementFactory.getThreadMXBean().getThreadCount(), direct, resident());
    }

    /** VmRSS from /proc/self/status (Linux), or empty. */
    static OptionalLong resident() {
        if (!Files.isReadable(PROC_STATUS)) return OptionalLong.empty();
        try {
            for (String line : Files.readAllLines(PROC_STATUS)) if (line.startsWith("VmRSS:")) return parseKb(line);
        } catch (IOException | RuntimeException ignored) { /* Not reported: leave it out. */ }
        return OptionalLong.empty();
    }

    static OptionalLong parseKb(String line) {
        String[] parts = line.substring(line.indexOf(':') + 1).strip().split("\\s+");
        try { return parts.length >= 1 ? OptionalLong.of(Long.parseLong(parts[0]) * 1024) : OptionalLong.empty(); }
        catch (NumberFormatException invalid) { return OptionalLong.empty(); }
    }

    /**
     * Whether heap use rose sharply since the last logged snapshot: by at least 64 MB and by half. Small heaps
     * swing by tens of MB between collections, so both conditions must hold.
     */
    public static boolean jumped(Snapshot last, Snapshot now) {
        long rise = now.heapUsed() - last.heapUsed();
        return rise >= 64 * MB && rise * 2 >= last.heapUsed();
    }

    private static long mb(long bytes) { return Math.round(bytes / (double) MB); }
}
