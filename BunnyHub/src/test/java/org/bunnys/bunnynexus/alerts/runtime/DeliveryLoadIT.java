package org.bunnys.bunnynexus.alerts.runtime;

import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.bunnys.handler.InteractionExecutor;
import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in synthetic load: production scheduler/admission, in-memory queue and simulated transport. No Discord benchmark. */
class DeliveryLoadIT {
    @Test void launch6000() throws Exception { run(6000); }
    @Test void overload20000() throws Exception { run(20000); }

    private void run(int count) throws Exception {
        Instant created = Instant.now().minusSeconds(1);
        var pending = new TreeMap<String, DeliveryJob>();
        var guildIndex = new TreeMap<String, TreeMap<String, DeliveryJob>>();
        for (int i = 0; i < count; i++) {
            String guild = Integer.toString(100_000 + i % 1000), channel = Integer.toString(200_000 + i % 2000);
            String id = String.format(Locale.ROOT, "job-%05d", i);
            var job = DeliveryJob.ready(id, new DeliveryKey("event-" + i / 2000, channel),
                    new DestinationRef(guild, channel, "dest-" + channel, "inc"), "sub-" + channel, created);
            pending.put(id, job); guildIndex.computeIfAbsent(guild, ignored -> new TreeMap<>()).put(id, job);
        }
        var candidates = new SchedulingRepository() {
            public List<String> guilds(Optional<String> after, int limit) {
                return guildIndex.tailMap(after.orElse(""), false).keySet().stream().limit(limit).toList();
            }
            public List<DeliveryJob> due(DeliveryJob.State state, Optional<String> guild, Optional<Cursor> cursor, int limit) {
                if (state != DeliveryJob.State.READY) return List.of();
                var index = guild.isPresent() ? guildIndex.getOrDefault(guild.get(), new TreeMap<>()) : pending;
                return index.tailMap(cursor.map(Cursor::jobId).orElse(""), false).values().stream().limit(limit).toList();
            }
            public List<DeliveryJob> expired(DeliveryJob.State state, int limit) { return List.of(); }
        };
        var queue = mock(DeliveryQueue.class);
        when(queue.claim(anyString(), anyString(), any())).thenAnswer(call -> {
            var job = pending.remove(call.getArgument(0));
            if (job == null) return Optional.empty();
            guildIndex.get(job.destination().guildId()).remove(job.id());
            Instant now = Instant.now();
            return Optional.of(job.claim(call.getArgument(1), now, now.plus(call.<Duration>getArgument(2))));
        });
        var owners = mock(OwnershipRepository.class);
        when(owners.acquire(any(), anyString(), any())).thenAnswer(call -> Optional.of(new OwnershipRepository.Lease(
                new OwnershipRepository.Runtime(), call.getArgument(1), 1, created, created.plusSeconds(900))));
        var admission = new SendAdmission(32);
        var ownership = new RuntimeOwnership(owners, admission, "load", Duration.ofMinutes(15), Duration.ofSeconds(1), System::nanoTime);
        assertTrue(ownership.acquire());
        var finished = ConcurrentHashMap.<String>newKeySet(); var channels = ConcurrentHashMap.<String>newKeySet();
        var outcomes = new ConcurrentHashMap<DeliveryJob.State, AtomicInteger>();
        var error = new AtomicReference<Throwable>(); var token = new AtomicInteger();
        var latencies = new ConcurrentLinkedQueue<Long>();
        long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(120);
        long peakHeap = 0; int maxOccupied = 0, maxHints = 0, maxTransportQueue = 0;
        var transport = new ScheduledThreadPoolExecutor(4);
        transport.setRemoveOnCancelPolicy(true);
        try (var commands = new InteractionExecutor(8, 100);
             var scheduler = new DeliveryScheduler(candidates, queue, ownership, admission, 128,
                     new DeliveryScheduler.Budget(64, 8, 32, 16, Duration.ofSeconds(30), Duration.ofNanos(1)), work -> {
                 work.beginAuthorization();
                 Instant now = Instant.now(); var job = work.job();
                 var sending = job.authorize(job.lease().orElseThrow(), now, new DeliveryJob.Attempt("attempt-" + job.id(), 1,
                         "nonce-" + job.id(), 1, 1, "test", "a".repeat(64), Set.of(), now, now.plusSeconds(20)));
                 work.confirmAuthorized(sending);
                 assertTrue(work.tryBeginTransport()); assertFalse(work.tryBeginTransport());
                 assertTrue(channels.add(job.destination().channelId()), "Concurrent sends to one channel");
                 transport.schedule(() -> {
                     try {
                         int n = Integer.parseInt(job.id().substring(4));
                         DeliveryJob.Outcome outcome = n % 100 == 0 ? new DeliveryJob.Unknown()
                                 : n % 50 == 0 ? new DeliveryJob.DefinitelyRejected(false) : new DeliveryJob.Accepted(Integer.toString(900_000 + n));
                         var completed = sending.complete(sending.lease().orElseThrow(), sending.attempt().orElseThrow().id(),
                                 Instant.now(), outcome, Optional.empty());
                         outcomes.computeIfAbsent(completed.state(), ignored -> new AtomicInteger()).incrementAndGet();
                         assertTrue(channels.remove(job.destination().channelId()));
                         // Exercise both callback orders; receipt alone must not release active transport.
                         if ((n & 1) == 0) { work.confirmReceiptRecorded(); assertFalse(work.tryBeginTransport()); work.confirmTransportStopped(); }
                         else { work.confirmTransportStopped(); work.confirmReceiptRecorded(); }
                         assertTrue(finished.add(job.id()), "Duplicate completion");
                     } catch (Throwable failure) { error.compareAndSet(null, failure); }
                 }, 2, TimeUnit.MILLISECONDS);
             }, () -> "claim-" + token.incrementAndGet(), System::nanoTime)) {
            var commandSubmitter = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 10_000; i++) {
                    String key = "user-" + i % 1000;
                    while (true) {
                        long queued = System.nanoTime();
                        try { commands.submit(key, () -> latencies.add(System.nanoTime() - queued)); break; }
                        catch (RejectedExecutionException full) {
                            if (System.nanoTime() >= deadline) throw new IllegalStateException("Commands failed to drain");
                            LockSupport.parkNanos(100_000);
                        }
                    }
                }
            });
            while (finished.size() < count) {
                if (error.get() != null) throw new AssertionError("Transport simulation failed", error.get());
                assertTrue(System.nanoTime() < deadline, "Load scenario exceeded finite deadline");
                int claimed = scheduler.tick();
                maxOccupied = Math.max(maxOccupied, admission.snapshot().occupied());
                maxHints = Math.max(maxHints, scheduler.bufferedCandidates());
                maxTransportQueue = Math.max(maxTransportQueue, transport.getQueue().size());
                peakHeap = Math.max(peakHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                if (claimed == 0) LockSupport.parkNanos(100_000);
            }
            commandSubmitter.get(10, TimeUnit.SECONDS); commands.shutdown();
            assertTrue(commands.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(10_000, commands.snapshot().completed()); assertEquals(10_000, latencies.size());
            assertTrue(pending.isEmpty()); assertTrue(channels.isEmpty()); assertEquals(0, admission.snapshot().occupied());
            assertEquals(count, token.get()); assertTrue(maxOccupied <= 32); assertTrue(maxHints <= 128); assertTrue(maxTransportQueue <= 32);
            assertEquals(count / 100, outcomes.get(DeliveryJob.State.UNCERTAIN).get());
            assertEquals(count / 100, outcomes.get(DeliveryJob.State.FAILED).get());
            assertEquals(count - 2 * count / 100, outcomes.get(DeliveryJob.State.SENT).get());
            var sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf(Locale.ROOT, "SYNTHETIC_LOAD jobs=%d guilds=1000 elapsed=%.3fs occupiedMax=%d hintsMax=%d transportQueueMax=%d heapPeakMiB=%.1f commandP95Ms=%.3f commandMaxMs=%.3f uncertain=%d rejected=%d noDuplicates=true%n",
                    count, (System.nanoTime() - started) / 1e9, maxOccupied, maxHints, maxTransportQueue, peakHeap / 1048576.0,
                    sorted[(int)(sorted.length * .95)] / 1e6, sorted[sorted.length - 1] / 1e6,
                    outcomes.get(DeliveryJob.State.UNCERTAIN).get(), outcomes.get(DeliveryJob.State.FAILED).get());
        } finally { transport.shutdownNow(); assertTrue(transport.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
