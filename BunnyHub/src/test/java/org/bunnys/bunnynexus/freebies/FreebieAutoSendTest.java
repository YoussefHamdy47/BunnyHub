package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Launchers on the auto-send list skip owner approval once their review message is up; everything else waits. */
class FreebieAutoSendTest {
    static final String OWNER = "333644367539470337", CHANNEL = "100000000000000099";
    final Instant now = FreebieLifecycleTest.END.minus(Duration.ofHours(30));
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final JDA client = mock(JDA.class);
    FreebieSystem system;

    FreebieSystem system(Set<FreebieStore> autoSend) {
        when(repository.dmJobs()).thenReturn(mock(FreebieDmJobs.class));
        when(repository.dmSubscribers()).thenReturn(mock(FreebieDmSubscribers.class));
        when(repository.lifecycle()).thenReturn(mock(FreebieLifecycleStore.class));
        when(repository.deliveries()).thenReturn(mock(FreebieDeliveryStore.class));
        when(repository.retention()).thenReturn(mock(FreebieRetention.class));
        when(repository.reviews()).thenReturn(mock(FreebieReviewStore.class));
        when(repository.summaries()).thenReturn(mock(FreebieSummaryStore.class));
        when(repository.counts(any())).thenReturn(new FreebieRepository.Counts(0, 0, 0, 3));
        when(subscriptions.forStore(any())).thenReturn(List.of());
        when(subscriptions.audience(any())).thenReturn(new FreebieSubscriptions.Audience(3, 2));
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        system = new FreebieSystem(new FreebieConfig(CHANNEL, CHANNEL, Set.of(OWNER), 10, 90, 0, autoSend),
                repository, subscriptions, () -> client, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        return system;
    }

    @AfterEach void close() { if (system != null) system.close(); }

    static FreebieOffer pending(FreebieStore store, boolean reviewPosted) {
        return new FreebieOffer("gamerpower:7", "7", store, "PC", "Game Giveaway", "desc", "1. Click",
                Optional.empty(), Optional.empty(), URI.create("https://www.gamerpower.com/open/x"), URI.create("https://www.gamerpower.com/x"),
                Optional.of(FreebieLifecycleTest.END), Instant.parse("2026-10-01T00:00:00Z"), FreebieOffer.State.PENDING,
                reviewPosted ? Optional.of("1234567890123456789") : Optional.empty(),
                reviewPosted ? Optional.of(CHANNEL) : Optional.empty(), Optional.empty(), false, false, 1);
    }

    @Test void anAutoSendLauncherIsApprovedAndFannedOutWithoutAnOwner() {
        var offer = pending(FreebieStore.EPIC, true);
        when(repository.offersIn(FreebieOffer.State.PENDING, 200)).thenReturn(List.of(offer));
        when(repository.transition(offer.id(), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, FreebieSystem.AUTO_ACTOR, now))
                .thenReturn(Optional.of(offer));
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        system(Set.of(FreebieStore.EPIC)).housekeeping();
        verify(repository).transition(offer.id(), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, FreebieSystem.AUTO_ACTOR, now);
        verify(repository).setFanoutDone(eq(offer.id()), anyInt());
    }

    @Test void otherLaunchersAndUnpostedReviewsStillWait() {
        var steam = pending(FreebieStore.STEAM, true);
        var unposted = pending(FreebieStore.EPIC, false);
        when(repository.offersIn(FreebieOffer.State.PENDING, 200)).thenReturn(List.of(steam, unposted));
        system(Set.of(FreebieStore.EPIC)).housekeeping();
        verify(repository, never()).transition(any(), eq(FreebieOffer.State.PENDING), eq(FreebieOffer.State.APPROVED), any(), any());
        verify(repository, never()).setFanoutDone(any(), anyInt());
    }

    @Test void theEnvironmentPicksTheListWithSafeDefaults() {
        List<String> warnings = new ArrayList<>();
        assertEquals(FreebieConfig.DEFAULT_AUTO_SEND, FreebieConfig.autoSend(null, warnings::add));
        assertEquals(Set.of(), FreebieConfig.autoSend("none", warnings::add));
        assertEquals(EnumSet.of(FreebieStore.EPIC, FreebieStore.GOG), FreebieConfig.autoSend(" Epic, gog ,nope", warnings::add));
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals(Set.of(), new FreebieConfig(CHANNEL, Set.of(OWNER), 10).autoSend(), "built in code: everything reviewed");
    }

    @Test void anAutoSendReviewStillPingsTheOwners() {
        var message = FreebieMessages.review(pending(FreebieStore.EPIC, false), 3, 2, Set.of(OWNER), true);
        assertEquals(Set.of(OWNER), message.getMentionedUsers());
        assertTrue(message.getContent().startsWith("<@" + OWNER + ">"), message.getContent());
        assertTrue(message.getContent().contains("sent automatically"), message.getContent());
        var reviewed = FreebieMessages.review(pending(FreebieStore.EPIC, false), 3, 2, Set.of(OWNER), false);
        assertEquals(Set.of(OWNER), reviewed.getMentionedUsers());
    }
}
