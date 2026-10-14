package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieDiscoveryTest {
    private final Clock clock = mock(Clock.class);
    private final Instant start = Instant.parse("2026-10-13T00:00:00Z");
    private final FreebieFeed feed = mock(FreebieFeed.class);
    private final FreebieRepository repository = mock(FreebieRepository.class);
    private final FreebieReviewChannel reviews = mock(FreebieReviewChannel.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final FreebieDiscovery discovery;

    FreebieDiscoveryTest() {
        when(clock.instant()).thenReturn(start);
        when(feed.provider()).thenReturn(FreebieFeed.Provider.GAMERPOWER);
        doAnswer(call -> {
            scheduled.add(call.getArgument(0));
            delays.add(call.getArgument(1));
            return null;
        }).when(executor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        discovery = new FreebieDiscovery(new FreebieConfig("123456789012345678", Set.of("223456789012345678"), 10),
                repository, feed, clock, reviews, (offer, reason) -> {});
        discovery.start(executor, 0);
    }

    private static FreebieFeed.Result empty() {
        return new FreebieFeed.Result(FreebieFeed.State.COMPLETE, List.of(), Optional.empty(), "");
    }

    @Test void failedPersistenceDoesNotReportSuccessOrResetBackoff() {
        when(feed.poll()).thenReturn(empty());
        when(repository.markMissing(eq(FreebieFeed.Provider.GAMERPOWER), anySet())).thenThrow(new IllegalStateException("storage unavailable"));
        scheduled.getFirst().run();
        assertTrue(discovery.status().contains("none since start"));
        assertEquals(1200L, delays.getLast());
        when(clock.instant()).thenReturn(start.plusSeconds(1200));
        scheduled.getLast().run();
        assertEquals(2400L, delays.getLast());
    }

    @Test void storageOutageNotifiesOnceAndOnlyACompletedPollRecovers() {
        when(feed.poll()).thenReturn(empty());
        when(repository.markMissing(eq(FreebieFeed.Provider.GAMERPOWER), anySet())).thenThrow(new IllegalStateException("storage unavailable"));
        when(clock.instant()).thenReturn(start.plusSeconds(3600));
        scheduled.getFirst().run();
        verify(reviews).notifyOwners(contains("over an hour"));
        when(clock.instant()).thenReturn(start.plusSeconds(4800));
        scheduled.getLast().run();
        verify(reviews, never()).notifyOwners(contains("working again"));
        doReturn(List.of()).when(repository).markMissing(eq(FreebieFeed.Provider.GAMERPOWER), anySet());
        when(clock.instant()).thenReturn(start.plusSeconds(7200));
        scheduled.getLast().run();
        verify(reviews).notifyOwners(contains("working again"));
        assertTrue(discovery.status().contains("✅"));
    }

    @Test void manualChecksAndScheduledChecksRespectProviderWait() {
        when(feed.poll()).thenReturn(new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(),
                Optional.of(Duration.ofHours(2)), "rate limited"));
        scheduled.getFirst().run();
        discovery.pollNow();
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        scheduled.getLast().run();
        verify(feed, times(1)).poll();
        assertEquals(7140L, delays.getLast());
        when(clock.instant()).thenReturn(start.plusSeconds(7200));
        scheduled.getLast().run();
        verify(feed, times(2)).poll();
    }

    @Test void partialFeedKeepsValidCandidatesButNeverEndsOffersOrReportsHealthy() throws Exception {
        GamerPowerIntake.Candidate candidate;
        try (var input = getClass().getResourceAsStream("/gamerpower/candidate.json")) {
            candidate = new GamerPowerIntake().decode(200, input.readAllBytes(), start).candidates().getFirst();
        }
        when(feed.poll()).thenReturn(new FreebieFeed.Result(FreebieFeed.State.PARTIAL,
                List.of(FreebieFeed.Candidate.from(candidate)), Optional.empty(), "partial feed: 1 rejected item(s)"));
        scheduled.getFirst().run();
        verify(repository).recordSeen(FreebieFeed.Candidate.from(candidate), start);
        verify(repository, never()).markMissing(eq(FreebieFeed.Provider.GAMERPOWER), anySet());
        assertTrue(discovery.status().contains("partial feed"));
        assertTrue(discovery.status().contains("none since start"));
    }

    @Test void thrownFetchStillRecordsAttemptAndReschedules() {
        when(feed.poll()).thenThrow(new IllegalStateException("fetch failed"));
        scheduled.getFirst().run();
        assertTrue(discovery.status().contains("<t:" + start.getEpochSecond() + ":R>"));
        assertEquals(1200L, delays.getLast());
    }

    @Test void failedHealthNoticeCannotStopDiscovery() {
        when(feed.poll()).thenReturn(new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(), Optional.empty(), "unavailable"));
        doThrow(new IllegalStateException("Discord unavailable")).when(reviews).notifyOwners(anyString());
        when(clock.instant()).thenReturn(start.plusSeconds(3600));
        assertDoesNotThrow(() -> scheduled.getFirst().run());
        assertEquals(2, scheduled.size());
        assertEquals(1200L, delays.getLast());
    }

    @Test void subsecondBackoffRemainderDoesNotSpinTheExecutor() {
        when(feed.poll()).thenReturn(new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(), Optional.empty(), "unavailable"));
        scheduled.getFirst().run();
        when(clock.instant()).thenReturn(start.plusSeconds(1200).minusMillis(100));
        scheduled.getLast().run();
        assertEquals(1L, delays.getLast());
        verify(feed, times(1)).poll();
    }

    @Test void twoSourceFailuresHaveIndependentDeadlinesAndRecoveryNotices() {
        var epic = mock(FreebieFeed.class);
        when(epic.provider()).thenReturn(FreebieFeed.Provider.EPIC);
        when(epic.poll()).thenReturn(new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(), Optional.of(Duration.ofHours(2)), "rate limited"));
        var second = new FreebieDiscovery(new FreebieConfig("123456789012345678", Set.of("223456789012345678"), 10),
                repository, epic, clock, reviews, (offer, reason) -> {});
        second.start(executor, 0);
        when(feed.poll()).thenReturn(new FreebieFeed.Result(FreebieFeed.State.FAILED, List.of(), Optional.empty(), "network"));
        when(clock.instant()).thenReturn(start.plusSeconds(3600));
        scheduled.get(0).run(); scheduled.get(1).run();
        verify(reviews).notifyOwners(contains("GamerPower free-game discovery has not worked"));
        verify(reviews).notifyOwners(contains("Epic Games free-game discovery has not worked"));
        when(clock.instant()).thenReturn(start.plusSeconds(4800)); when(feed.poll()).thenReturn(empty());
        discovery.pollNow(); second.pollNow();
        verify(feed, times(2)).poll(); verify(epic, times(1)).poll();
        verify(reviews).notifyOwners(contains("GamerPower free-game discovery is working again"));
        verify(reviews, never()).notifyOwners(contains("Epic Games free-game discovery is working again"));
        assertTrue(second.status().contains("Discovery retry"));
        when(clock.instant()).thenReturn(start.plusSeconds(10800)); when(epic.poll()).thenReturn(empty()); second.pollNow();
        verify(reviews).notifyOwners(contains("Epic Games free-game discovery is working again"));
    }
}


