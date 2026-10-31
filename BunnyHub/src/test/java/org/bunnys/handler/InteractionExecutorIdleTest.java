package org.bunnys.handler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Workers start on demand, exit when idle, and come back for new work; the admission bound is unchanged. */
class InteractionExecutorIdleTest {
    @Test void noWorkerThreadsBeforeWorkOrAfterTheIdleTimeout() throws Exception {
        try (var executor = new InteractionExecutor("idle-test", 4, 8, 4, Duration.ofMillis(100))) {
            assertEquals(0, executor.liveWorkers());
            var done = new CountDownLatch(1);
            executor.submit("user", done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, executor.liveWorkers(), "one action needs only one worker");
            waitFor(() -> executor.liveWorkers() == 0);
        }
    }

    @Test void idleWorkersRestartOnDemand() throws Exception {
        try (var executor = new InteractionExecutor("restart-test", 2, 8, 4, Duration.ofMillis(50))) {
            for (int round = 0; round < 3; round++) {
                var done = new CountDownLatch(2);
                var release = new CountDownLatch(1);
                executor.submit("a", () -> { done.countDown(); await(release); });
                executor.submit("b", () -> { done.countDown(); await(release); });
                assertTrue(done.await(5, TimeUnit.SECONDS), "both users run at once in round " + round);
                assertEquals(2, executor.liveWorkers());
                release.countDown();
                waitFor(() -> executor.liveWorkers() == 0);
            }
            assertEquals(6, executor.snapshot().completed());
        }
    }

    @Test void workerCountNeverExceedsThePoolSize() throws Exception {
        var release = new CountDownLatch(1);
        try (var executor = new InteractionExecutor("cap-test", 4, 100, 8, Duration.ofSeconds(30))) {
            var started = new CountDownLatch(4);
            for (int i = 0; i < 20; i++) executor.submit("user-" + i, () -> { started.countDown(); await(release); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(4, executor.liveWorkers());
            assertEquals(4, executor.snapshot().active());
            assertEquals(16, executor.snapshot().queued());
            release.countDown(); // Let the workers finish before close() drains the queue.
        } finally { release.countDown(); }
    }

    @Test void commandPoolStillRejectsWorkAboveItsQueueLimit() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(4);
        // The bot's command pool: 4 workers, 100 queued.
        try (var executor = new InteractionExecutor("BunnyCommand", 4, 100, 8)) {
            for (int i = 0; i < 4; i++) executor.submit("busy-" + i, () -> { started.countDown(); await(release); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) executor.submit("queued-" + i, () -> {});
            assertThrows(RejectedExecutionException.class, () -> executor.submit("one-too-many", () -> {}));
            assertEquals(1, executor.snapshot().rejected());
            assertEquals(104, executor.snapshot().capacity());
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test void shutdownStopsIdleWorkersPromptly() throws Exception {
        var executor = new InteractionExecutor("stop-test", 2, 4, 2, Duration.ofMinutes(10));
        var done = new CountDownLatch(1);
        executor.submit("user", done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, executor.liveWorkers());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not reached in 5 s");
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
