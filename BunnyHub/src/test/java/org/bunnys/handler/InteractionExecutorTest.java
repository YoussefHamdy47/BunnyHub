package org.bunnys.handler;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InteractionExecutorTest {
    @Test void oneBusyUserDoesNotOccupyOtherWorkers() throws Exception {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new CountDownLatch(1);
        try (var executor = new InteractionExecutor(2, 16)) {
            try {
                executor.submit("hot", () -> { running.countDown(); await(release); });
                assertTrue(running.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 8; i++) executor.submit("hot", () -> {});
                executor.submit("cold", () -> { other.countDown(); await(release); });
                assertTrue(other.await(5, TimeUnit.SECONDS), "Waiting work for hot must not take the second worker");
                assertEquals(2, executor.snapshot().active());
                assertEquals(8, executor.snapshot().queued());
            } finally { release.countDown(); }
        }
    }

    @Test void hashCollisionsDoNotSerializeUnrelatedUsers() throws Exception {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new CountDownLatch(1);
        try (var executor = new InteractionExecutor(2, 4)) {
            try {
                executor.submit("Aa", () -> { running.countDown(); await(release); });
                assertTrue(running.await(5, TimeUnit.SECONDS));
                executor.submit("BB", other::countDown);
                assertTrue(other.await(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void gracefulShutdownDrainsInOrderAndReleasesAllKeys() throws Exception {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new java.util.ArrayList<Integer>();
        try (var executor = new InteractionExecutor(2, 110)) {
            executor.submit("user", () -> { running.countDown(); await(release); });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) {
                int value = i;
                executor.submit("user", () -> order.add(value));
            }
            executor.shutdown();
            assertThrows(RejectedExecutionException.class, () -> executor.submit("new", () -> {}));
            release.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), order);
            assertEquals(0, executor.snapshot().keys());
            assertEquals(101, executor.snapshot().completed());
            assertEquals(1, executor.snapshot().rejected());
        } finally { release.countDown(); }
    }

    @Test void readyKeysTakeTurnsInsteadOfDrainingOneUsersBacklog() throws Exception {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new java.util.ArrayList<String>();
        try (var executor = new InteractionExecutor(1, 8)) {
            executor.submit("hot", () -> { running.countDown(); await(release); order.add("first"); });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            executor.submit("hot", () -> order.add("second"));
            executor.submit("hot", () -> order.add("third"));
            executor.submit("other", () -> order.add("other"));
            executor.shutdown();
            release.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("first", "other", "second", "third"), order);
        } finally { release.countDown(); }
    }

    @Test void forcedShutdownCancelsQueuedFuturesAndReportsAbandonedWork() throws Exception {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = new InteractionExecutor(1, 2)) {
            executor.submit("running", () -> { running.countDown(); await(release); });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var future = executor.executor().submit(() -> fail("Queued work must not execute"));
            executor.submit("queued", () -> fail("Queued work must not execute"));
            assertEquals(2, executor.shutdownNow().size());
            assertTrue(future.isCancelled());
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, executor.snapshot().abandoned());
            assertEquals(0, executor.snapshot().queued());
        } finally { release.countDown(); }
    }

    @Test void failureDoesNotStrandFollowingWorkInTheSameLane() throws Exception {
        try (var executor = new InteractionExecutor(1, 4)) {
            executor.submit("user", () -> { throw new IllegalStateException("expected test failure"); });
            var done = new CountDownLatch(1);
            executor.submit("user", done::countDown);
            executor.shutdown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, executor.snapshot().failed());
            assertEquals(2, executor.snapshot().completed());
            assertEquals(0, executor.snapshot().keys());
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    @Test void sameUserActionsDoNotOverlap() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(100);
        try (var executor = new InteractionExecutor()) {
            for (int i = 0; i < 100; i++) executor.submit("user", () -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                Thread.yield();
                active.decrementAndGet();
                finished.countDown();
            });
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(1, peak.get());
        }
    }
}
