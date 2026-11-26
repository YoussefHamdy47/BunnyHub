package org.bunnys;

import org.bunnys.handler.BunnyHub;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The bot's memory-related settings: worker pools, the Mongo pool that follows them, and the container flags. */
class MemoryFootprintTest {
    @Test void botUsesSmallWorkerPoolsWithTheSameQueueBounds() {
        var config = Main.builder("test").snapshot();
        assertEquals(4, config.getCommandPoolSize());
        assertEquals(100, config.getCommandQueueCapacity());
        assertEquals(2, config.getAutocompletePoolSize());
        assertEquals(32, config.getAutocompleteQueueCapacity());
        assertEquals(8, config.getCommandUserCapacity());
        assertEquals(2, config.getAutocompleteUserCapacity());
    }

    @Test void mongoPoolFollowsTheWorkerPools() {
        assertEquals(14, BunnyHub.mongoPoolSize(Main.builder("test").snapshot()));
    }

    @Test void containerLimitsNativeMemoryAndCompiledCode() throws Exception {
        String docker = Files.readString(Path.of("Dockerfile"));
        assertTrue(docker.contains("ENV MALLOC_ARENA_MAX=2"));
        String options = docker.lines().filter(l -> l.startsWith("ENV JAVA_OPTS=")).findFirst().orElseThrow();
        for (String flag : new String[] {"-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m",
                "-XX:+UnlockExperimentalVMOptions", "-XX:TrimNativeHeapInterval=60000",
                // From the hourly memory log: heap under 35 MB, metaspace 37 MB.
                "-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-XX:+UseSerialGC", "-Xss512k", "-XX:+ExitOnOutOfMemoryError"})
            assertTrue(options.contains(flag), flag);
        assertFalse(options.contains("ReservedCodeCacheSize=64m"));
        assertTrue(options.indexOf("-XX:+UnlockExperimentalVMOptions") < options.indexOf("-XX:TrimNativeHeapInterval"),
                "the unlock must come before the experimental flag");
    }
}
