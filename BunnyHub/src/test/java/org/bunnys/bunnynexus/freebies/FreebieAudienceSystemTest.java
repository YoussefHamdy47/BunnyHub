package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DM planning and cancellation wired into approval fan-out, owner stop and automatic end. */
class FreebieAudienceSystemTest {
    static final String OWNER = "333644367539470337";
    final Instant now = FreebieLifecycleTest.END.minus(Duration.ofHours(30));
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieDmJobs dmJobs = mock(FreebieDmJobs.class);
    final FreebieDmSubscribers dmSubscribers = mock(FreebieDmSubscribers.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final JDA client = mock(JDA.class);
    final FreebieOffer live = FreebieLifecycleTest.live();
    FreebieSystem system;

    FreebieSystem system(int dmCap) {
        when(repository.dmJobs()).thenReturn(dmJobs);
        when(repository.dmSubscribers()).thenReturn(dmSubscribers);
        when(repository.lifecycle()).thenReturn(mock(FreebieLifecycleStore.class));
        when(repository.deliveries()).thenReturn(mock(FreebieDeliveryStore.class));
        when(repository.retention()).thenReturn(mock(FreebieRetention.class));
        when(repository.reviews()).thenReturn(mock(FreebieReviewStore.class));
        when(repository.summaries()).thenReturn(mock(FreebieSummaryStore.class));
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        system = new FreebieSystem(new FreebieConfig("100000000000000099", "100000000000000099", Set.of(OWNER), 10, 90, dmCap),
                repository, subscriptions, () -> client, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        return system;
    }
    @AfterEach void close() { if (system != null) system.close(); }

    @Test void fanOutPlansOneDmPerFollowerOfTheSection() {
        when(dmSubscribers.following(FreebieSection.MAJOR_PC)).thenReturn(List.of("u1", "u2"));
        when(subscriptions.forStore(any())).thenReturn(List.of());
        when(repository.offer(live.id())).thenReturn(Optional.of(live));
        system(100).fanOut(live);
        verify(dmJobs).plan(live.id(), List.of("u1", "u2"), now);
        var order = inOrder(dmJobs, repository);
        order.verify(dmJobs).plan(any(), any(), any());
        order.verify(repository).setFanoutDone(eq(live.id()), anyInt());
        verify(dmJobs, never()).cancelForOffer(any(), any());
    }

    @Test void noDmsArePlannedWhenTheFeatureIsOff() {
        when(subscriptions.forStore(any())).thenReturn(List.of());
        when(repository.offer(live.id())).thenReturn(Optional.of(live));
        system(0).fanOut(live);
        verify(dmJobs, never()).plan(any(), any(), any());
        verify(dmSubscribers, never()).following(any());
    }

    @Test void aStopDuringFanOutCancelsThePlannedDms() {
        when(subscriptions.forStore(any())).thenReturn(List.of());
        when(repository.offer(live.id())).thenReturn(Optional.of(FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, false,
                Optional.of(FreebieLifecycleTest.END), false, true)));
        system(100).fanOut(live);
        verify(dmJobs).cancelForOffer(live.id(), now);
    }

    @Test void anOwnerStopCancelsUnsentDms() {
        var stopped = FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, false, Optional.of(FreebieLifecycleTest.END), false, true);
        system(100);
        when(repository.stopByOwner(live.id(), FreebieOffer.State.APPROVED, OWNER, now)).thenReturn(Optional.of(stopped));
        system.stop(OWNER, live.id());
        verify(dmJobs).cancelForOffer(live.id(), now);
    }

    @Test void anAutomaticEndCancelsUnsentDms() {
        var gone = FreebieLifecycleTest.offer(FreebieOffer.State.APPROVED, true, Optional.of(FreebieLifecycleTest.END), false, true);
        system(100);
        when(repository.offersIn(FreebieOffer.State.APPROVED, 200)).thenReturn(List.of(gone));
        when(repository.transition(gone.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, null, now))
                .thenReturn(Optional.of(FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, true, Optional.of(FreebieLifecycleTest.END), false, true)));
        system.housekeeping();
        verify(dmJobs).cancelForOffer(gone.id(), now);
        verify(dmJobs).recover(now);
        verify(dmSubscribers).expirePending(now);
    }
}
