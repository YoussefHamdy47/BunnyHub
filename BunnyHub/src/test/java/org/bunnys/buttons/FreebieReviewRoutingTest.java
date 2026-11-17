package org.bunnys.buttons;

import org.bunnys.bunnynexus.freebies.*;
import org.bunnys.selects.FreebieStoreSelect;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class FreebieReviewRoutingTest {
    private static final String OWNER = "223456789012345678", CHANNEL = "123456789012345678";
    private FreebieSystem system() {
        var system = mock(FreebieSystem.class);
        when(system.config()).thenReturn(new FreebieConfig(CHANNEL, Set.of(OWNER), 10)); return system;
    }
    @Test void actualRouterSplitPreservesWholeGamerPowerAndEpicOfferIds() {
        var system = system();
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            for (String id : List.of("gamerpower:123", "epic:0123456789abcdef")) {
                var event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
                net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message> reply =
                        mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction.class, RETURNS_SELF);
                when(event.getHook().sendMessage(anyString())).thenReturn(reply);
                String component = "freebie_review:approve:" + id;
                when(event.getComponentId()).thenReturn(component); when(event.getChannelId()).thenReturn(CHANNEL);
                when(event.getUser().getId()).thenReturn(OWNER);
                when(system.requestApproval(OWNER, id)).thenReturn(new FreebieSystem.Text("Waiting for confirmation"));
                new FreebieReviewButtons().execute(null, event, component.split(":"));
                verify(system).requestApproval(OWNER, id); verify(system, never()).confirmApproval(OWNER, id);
            }
        }
    }
    @Test void actualSelectRouterSplitPreservesWholeOfferId() {
        var system = system();
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            var event = mock(StringSelectInteractionEvent.class, RETURNS_DEEP_STUBS);
            net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message> reply =
                    mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction.class, RETURNS_SELF);
            when(event.getHook().sendMessage(anyString())).thenReturn(reply);
            when(event.getComponentId()).thenReturn("freebie_store:gamerpower:123");
            when(event.getChannelId()).thenReturn(CHANNEL); when(event.getUser().getId()).thenReturn(OWNER);
            when(event.getValues()).thenReturn(List.of("epic")); when(system.changeStore(OWNER, "gamerpower:123", "epic")).thenReturn("Saved");
            new FreebieStoreSelect().execute(null, event, "freebie_store:gamerpower:123".split(":"));
            verify(system).changeStore(OWNER, "gamerpower:123", "epic");
        }
    }
    /** A decision turns the question message into its record: decision on top, no decision buttons, Clear finished only. */
    @Test void aSourceDecisionEditsTheQuestionMessageAndRemovesItsButtons() {
        var system = system();
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            for (var status : List.of(FreebieSourceDecisions.Status.SAVED, FreebieSourceDecisions.Status.ALREADY)) {
                var event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
                String component = "freebie_review:source-same-0123456789ab:gamerpower:123";
                when(event.getComponentId()).thenReturn(component); when(event.getChannelId()).thenReturn(CHANNEL);
                when(event.getUser().getId()).thenReturn(OWNER);
                when(event.getMessage().getContentRaw()).thenReturn("**Possible duplicate held for your decision**\n**Possible duplicate — owner decision required**\n**Knightica**");
                when(event.getMessage().isEphemeral()).thenReturn(false);
                net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction<net.dv8tion.jda.api.entities.Message> edit =
                        mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class, RETURNS_SELF);
                var text = org.mockito.ArgumentCaptor.forClass(String.class);
                when(event.getHook().editOriginal(text.capture())).thenReturn(edit);
                when(system.resolveSource(OWNER, "gamerpower:123", "source-same-0123456789ab"))
                        .thenReturn(new FreebieSourceDecisions.Outcome(status, "same", "x"));
                when(system.now()).thenReturn(java.time.Instant.parse("2026-11-17T12:00:00Z"));
                new FreebieReviewButtons().execute(null, event, component.split(":"));
                org.junit.jupiter.api.Assertions.assertTrue(text.getValue().startsWith(status == FreebieSourceDecisions.Status.SAVED
                        ? "✅ **Decided: Same giveaway** · by <@" + OWNER + ">" : "☑️ **Already answered**"), text.getValue());
                org.junit.jupiter.api.Assertions.assertTrue(text.getValue().contains("**Knightica**"), "the details stay");
                var rows = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
                verify(edit).setComponents(rows.capture());
                var after = (List<net.dv8tion.jda.api.components.MessageTopLevelComponent>) new ArrayList<>(rows.getValue());
                org.junit.jupiter.api.Assertions.assertFalse(FreebieMessages.needsOwner(after), "no decision buttons left");
                org.junit.jupiter.api.Assertions.assertFalse(after.isEmpty(), "Clear finished stays");
                verify(edit).setAllowedMentions(List.of());
                verify(event.getHook(), never()).sendMessage(anyString());
            }
        }
    }

    @Test void sourceResolutionRequiresOwnerAndConfiguredReviewChannel() {
        var system = system();
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            for (String user : List.of(OWNER, "333333333333333333")) {
                var event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
                when(event.getComponentId()).thenReturn("freebie_review:source-same-0123456789ab:gamerpower:123");
                when(event.getUser().getId()).thenReturn(user); when(event.getChannelId()).thenReturn("999999999999999999");
                new FreebieReviewButtons().execute(null, event, event.getComponentId().split(":"));
            }
            verify(system, never()).resolveSource(anyString(), anyString(), anyString());
        }
    }
}

