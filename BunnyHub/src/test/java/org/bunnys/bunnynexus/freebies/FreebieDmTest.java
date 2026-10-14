package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DM eligibility, the DM card, error classification, the consent-first subscribe flow and the cap setting. */
class FreebieDmTest {
    final Instant now = FreebieLifecycleTest.END.minus(Duration.ofHours(30));
    final FreebieOffer epic = FreebieLifecycleTest.live();

    @Test void onlyLiveApprovedOffersInAFollowedSectionAreSent() {
        assertTrue(FreebieDm.wants(EnumSet.of(FreebieSection.MAJOR_PC), epic, now));
        assertFalse(FreebieDm.wants(EnumSet.of(FreebieSection.CONSOLE, FreebieSection.MOBILE), epic, now), "other sections");
        assertFalse(FreebieDm.wants(EnumSet.allOf(FreebieSection.class),
                FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, false, Optional.of(FreebieLifecycleTest.END), false, true), now));
        assertFalse(FreebieDm.wants(EnumSet.allOf(FreebieSection.class),
                FreebieLifecycleTest.offer(FreebieOffer.State.COMPLETED, true, Optional.of(FreebieLifecycleTest.END), false, true), now), "gone");
        assertFalse(FreebieDm.wants(EnumSet.allOf(FreebieSection.class),
                FreebieLifecycleTest.offer(FreebieOffer.State.APPROVED, false, Optional.of(FreebieLifecycleTest.END), false, false), now), "never approved");
        assertFalse(FreebieDm.wants(EnumSet.allOf(FreebieSection.class), epic, FreebieLifecycleTest.END), "ended");
        assertTrue(FreebieDm.wants(EnumSet.allOf(FreebieSection.class),
                FreebieLifecycleTest.offer(FreebieOffer.State.COMPLETED, false, Optional.of(FreebieLifecycleTest.END), false, true), now));
    }

    @Test void theDailyCapUsesUtcDays() {
        assertEquals("2026-10-09", FreebieDm.day(Instant.parse("2026-10-09T23:59:59Z")));
        assertEquals("2026-10-10", FreebieDm.day(Instant.parse("2026-10-10T00:00:00Z")));
        assertEquals(5, FreebieDm.DAILY_CAP);
    }

    static List<Button> buttons(MessageCreateData data) {
        List<Button> buttons = new ArrayList<>();
        for (var row : data.getComponents()) if (row instanceof ActionRow r) r.getComponents().forEach(c -> buttons.add((Button) c));
        return buttons;
    }

    @Test void theDmCardIsTheAlertCardWithoutAnyMentionPlusUnsubscribe() {
        String job = FreebieDm.jobId(epic.id(), "400000000000000000");
        var card = FreebieDm.card(epic, job);
        assertTrue(card.getContent().isEmpty(), "no role ping in a DM");
        assertTrue(card.getAllowedMentions().isEmpty());
        assertTrue(card.getMentionedRoles().isEmpty());
        assertTrue(card.getMentionedUsers().isEmpty());
        assertFalse(card.isMentionRepliedUser());
        var channelCard = FreebieMessages.alertEmbed(epic, FreebieDm.reference(job));
        assertEquals(channelCard, card.getEmbeds().getFirst(), "same card as channel alerts");
        assertEquals("Powered by GamerPower • ref " + FreebieDm.reference(job), card.getEmbeds().getFirst().getFooter().getText());
        var unsubscribe = buttons(card).getLast();
        assertEquals("Unsubscribe", unsubscribe.getLabel());
        assertEquals(FreebieDm.UNSUBSCRIBE_PREFIX, unsubscribe.getCustomId(), "stateless: no user ID in the button");
        assertTrue(buttons(card).getFirst().getLabel().startsWith("Claim"));
        assertNotEquals(FreebieDm.reference(job), FreebieMessages.reference(epic.id() + "|400000000000000000"),
                "a DM reference never matches a channel alert's");
    }

    @Test void undeliverableUsersAreUnreachableOtherErrorsRetryOrFail() {
        assertEquals(FreebieDm.Outcome.UNREACHABLE, FreebieDm.classify(FreebieLifecycleTest.discord(ErrorResponse.CANNOT_SEND_TO_USER, false, 50007)));
        assertEquals(FreebieDm.Outcome.UNREACHABLE, FreebieDm.classify(new CompletionException(FreebieLifecycleTest.discord(ErrorResponse.UNKNOWN_USER, false, 10013))));
        assertEquals(FreebieDm.Outcome.RETRY, FreebieDm.classify(FreebieLifecycleTest.discord(ErrorResponse.SERVER_ERROR, true, 0)));
        assertEquals(FreebieDm.Outcome.RETRY, FreebieDm.classify(new TimeoutException()));
        assertEquals(FreebieDm.Outcome.FAIL, FreebieDm.classify(FreebieLifecycleTest.discord(ErrorResponse.INVALID_FORM_BODY, false, 50035)));
    }

    // ------------------------------------------------------------------ subscribe

    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieDmSubscribers subscribers = mock(FreebieDmSubscribers.class);
    final FreebieDmJobs jobs = mock(FreebieDmJobs.class);

    FreebieDmService service(int cap) {
        when(repository.dmSubscribers()).thenReturn(subscribers);
        when(repository.dmJobs()).thenReturn(jobs);
        var config = new FreebieConfig("100000000000000099", "100000000000000099", Set.of("333644367539470337"), 10, 90, cap);
        return new FreebieDmService(config, repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void aNewSubscriptionIsSavedOnlyAfterTheConsentDmIsAccepted() {
        var dms = service(100);
        when(subscribers.reserve(eq("u"), any(), eq(100), eq(now))).thenReturn(FreebieDmSubscribers.Reserve.RESERVED);
        when(subscribers.activate("u", now)).thenReturn(true);
        List<MessageCreateData> sent = new ArrayList<>();
        assertEquals(FreebieDmService.Subscribed.SUBSCRIBED, dms.subscribe("u", EnumSet.of(FreebieSection.MAJOR_PC), m -> sent.add(m)));
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().getAllowedMentions().isEmpty());
        var order = inOrder(subscribers);
        order.verify(subscribers).reserve(eq("u"), any(), eq(100), eq(now));
        order.verify(subscribers).activate("u", now);
    }

    @Test void aRefusedConsentDmSavesNothingAndFreesTheSlot() {
        var dms = service(100);
        when(subscribers.reserve(eq("u"), any(), anyInt(), any())).thenReturn(FreebieDmSubscribers.Reserve.RESERVED);
        assertEquals(FreebieDmService.Subscribed.DM_REFUSED, dms.subscribe("u", EnumSet.of(FreebieSection.MOBILE), m -> false));
        verify(subscribers).release("u");
        verify(subscribers, never()).activate(any(), any());
        assertThrows(IllegalStateException.class, () -> dms.subscribe("u", EnumSet.of(FreebieSection.MOBILE), m -> { throw new IllegalStateException(); }));
        verify(subscribers, times(2)).release("u");
    }

    @Test void editingFullAndDisabledNeverSendAConsentDm() {
        var dms = service(100);
        when(subscribers.reserve(eq("edit"), any(), anyInt(), any())).thenReturn(FreebieDmSubscribers.Reserve.EDITED);
        when(subscribers.reserve(eq("full"), any(), anyInt(), any())).thenReturn(FreebieDmSubscribers.Reserve.FULL);
        assertEquals(FreebieDmService.Subscribed.EDITED, dms.subscribe("edit", EnumSet.of(FreebieSection.CONSOLE), m -> fail("no DM")));
        assertEquals(FreebieDmService.Subscribed.FULL, dms.subscribe("full", EnumSet.of(FreebieSection.CONSOLE), m -> fail("no DM")));
        assertFalse(service(0).enabled());
    }

    @Test void unsubscribingRemovesOnlyThatUserAndEndsTheirQueuedDms() {
        var dms = service(100);
        when(subscribers.unsubscribe("me")).thenReturn(true);
        assertTrue(dms.unsubscribe("me"));
        verify(subscribers).unsubscribe("me");
        verify(jobs).cancelForUser("me", FreebieDm.Cancel.UNSUBSCRIBED, now);
        verifyNoMoreInteractions(subscribers);
    }

    @Test void theSubscriberCapComesFromTheEnvironmentWithASafeDefault() {
        List<String> warnings = new ArrayList<>();
        assertEquals(100, FreebieConfig.dmMaxSubscribers(null, warnings::add));
        assertEquals(0, FreebieConfig.dmMaxSubscribers("0", warnings::add));
        assertEquals(250, FreebieConfig.dmMaxSubscribers(" 250 ", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(100, FreebieConfig.dmMaxSubscribers("-1", warnings::add));
        assertEquals(100, FreebieConfig.dmMaxSubscribers("lots", warnings::add));
        assertEquals(2, warnings.size());
        Map<String, String> env = Map.of("FREEBIE_REVIEW_CHANNEL_ID", "100000000000000099", "FREEBIE_OWNER_IDS", "333644367539470337",
                "FREEBIE_DM_MAX_SUBSCRIBERS", "0");
        var config = FreebieConfig.load(env::get, new ArrayList<>()).orElseThrow();
        assertFalse(config.dmEnabled(), "0 turns DM alerts off without disabling the rest");
    }

    @Test void theSummaryReportsDmTotalsInOneLine() {
        var counts = new FreebieRepository.Counts(3, 0, 0, 0);
        var summary = FreebieMessages.summary(epic, counts, List.of(), Map.of(), new FreebieDmJobs.Totals(4, 1, 0, 0, 2));
        var text = summary.getEmbeds().getFirst().getDescription();
        assertTrue(text.contains("**Personal DMs** · 4 sent · 2 still queued · 1 over the daily limit · 0 failed · 0 cancelled"), text);
        var none = FreebieMessages.summary(epic, counts, List.of(), Map.of(), new FreebieDmJobs.Totals(0, 0, 0, 0, 0));
        assertFalse(none.getEmbeds().getFirst().getDescription().contains("Personal DMs"));
    }
}
