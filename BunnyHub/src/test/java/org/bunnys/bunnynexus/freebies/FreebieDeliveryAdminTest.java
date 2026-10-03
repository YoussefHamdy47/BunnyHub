package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.bson.Document;
import org.bunnys.bunnynexus.commands.freebies.FreebieHistoryCommands;
import org.bunnys.handler.commands.context.SlashContext;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreebieDeliveryAdminTest {
    final String owner = "333644367539470337";
    final FreebieConfig config = new FreebieConfig("100000000000000099", Set.of(owner), 10);
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final FreebieDeliveryAdmin admin = new FreebieDeliveryAdmin(config, repository, subscriptions, () -> null, Clock.systemUTC());

    @Test void everyOwnerOperationAuthorizesBeforeAnyDatabaseRead() {
        assertThrows(SecurityException.class, () -> admin.history("outsider", "gamerpower:1", ""));
        assertThrows(SecurityException.class, () -> admin.audit("outsider", "gamerpower:1", "channel", 1));
        assertThrows(SecurityException.class, () -> admin.preview("outsider", "gamerpower:1", "channel"));
        assertThrows(SecurityException.class, () -> admin.confirm("outsider", "gamerpower:1", "channel", "token"));
        verifyNoInteractions(repository, subscriptions);
    }
    @Test void slashHistoryAcknowledgesPrivatelyBeforeServiceReadAndRepliesPrivately() {
        var system = mock(FreebieSystem.class); var service = mock(FreebieDeliveryAdmin.class);
        var context = mock(SlashContext.class); var event = mock(SlashCommandInteractionEvent.class);
        var user = mock(User.class); var option = mock(OptionMapping.class);
        when(user.getId()).thenReturn(owner); when(context.getUser()).thenReturn(user);
        when(context.event()).thenReturn(event); when(event.getOption("offer")).thenReturn(option);
        when(option.getAsString()).thenReturn("gamerpower:1");
        when(system.config()).thenReturn(config); when(system.deliveryAdmin()).thenReturn(service);
        when(service.history(owner, "gamerpower:1", "")).thenReturn(new FreebieDeliveryAdmin.View("private", List.of()));
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            new FreebieHistoryCommands(false).execute(null, context);
        }
        var order = inOrder(context, service);
        order.verify(context).defer(true);
        order.verify(service).history(owner, "gamerpower:1", "");
        order.verify(context).reply(any(net.dv8tion.jda.api.entities.MessageEmbed.class), anyList(), eq(true));
    }
    @Test void unauthorizedSlashCannotReachDeliveryService() {
        var system = mock(FreebieSystem.class); var context = mock(SlashContext.class); var user = mock(User.class);
        when(system.config()).thenReturn(config); when(context.getUser()).thenReturn(user); when(user.getId()).thenReturn("outsider");
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            new FreebieHistoryCommands(true).execute(null, context);
        }
        verify(system, never()).deliveryAdmin();
    }
    @Test void historyContainsLinksAndAuthoredReasonsWithinEmbedLimit() {
        var store = mock(FreebieDeliveryStore.class); when(repository.deliveries()).thenReturn(store);
        var row = new Document("_id", "gamerpower:1|200000000000000001").append("guildId", "100000000000000001")
                .append("channelId", "200000000000000001").append("state", "BLOCKED").append("verifyFirst", true)
                .append("blockedReason", "HISTORY_PERMISSION").append("failure", "MISSING_PERMISSIONS")
                .append("messageId", "300000000000000001").append("failureDetail", "SECRET")
                .append("retryPreviewToken", "PRIVATE_TOKEN");
        for (String key : List.of("createdAt", "lastAttemptAt", "finishedAt", "nextAttemptAt")) row.put(key, Date.from(Instant.now()));
        row.put("retryAudit", List.of(new Document("actor", owner).append("at", new Date()).append("previousState", "FAILED")));
        when(store.page(anyString(), anyString())).thenReturn(Collections.nCopies(6, row));
        var view = admin.history(owner, "gamerpower:1", "");
        assertTrue(view.text().contains("https://discord.com/channels/100000000000000001/200000000000000001/300000000000000001"));
        assertTrue(view.text().contains("Restore Read Message History"));
        assertFalse(view.text().contains("SECRET")); assertFalse(view.text().contains("PRIVATE_TOKEN"));
        assertTrue(view.text().length() <= 4096, "size=" + view.text().length());
        assertEquals(1, view.rows().size());
        assertTrue(view.rows().getFirst().getComponents().getFirst().asButton().getCustomId().length() <= 100);
    }
    @Test void auditPaginationIsBoundedAndShowsActorAndPreviousOutcome() {
        var store = mock(FreebieDeliveryStore.class); when(repository.deliveries()).thenReturn(store);
        var audit = new ArrayList<Document>();
        for (int i = 0; i < 12; i++) audit.add(new Document("actor", "actor" + i).append("at", new Date()).append("previousState", "BLOCKED"));
        when(store.get(anyString())).thenReturn(Optional.of(new Document("state", "SENT").append("retryAudit", audit)));
        String text = admin.audit(owner, "gamerpower:1", "channel", 2).text();
        assertFalse(text.contains("actor4")); assertTrue(text.contains("actor5")); assertTrue(text.contains("actor9"));
        assertFalse(text.contains("actor10")); assertTrue(text.contains("BLOCKED"));
    }
    @SuppressWarnings("unchecked") // Generic webhook action mock.
    @Test void confirmationButtonAuthorizesAndAcknowledgesBeforeDatabaseService() {
        var system = mock(FreebieSystem.class); var service = mock(FreebieDeliveryAdmin.class);
        var event = mock(net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent.class);
        var user = mock(User.class);
        var defer = mock(net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction.class, RETURNS_SELF);
        var hook = mock(net.dv8tion.jda.api.interactions.InteractionHook.class);
        net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction<net.dv8tion.jda.api.entities.Message> edit =
                mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginalEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenReturn(edit);
        when(event.getUser()).thenReturn(user); when(user.getId()).thenReturn(owner);
        when(system.config()).thenReturn(config); when(system.deliveryAdmin()).thenReturn(service);
        when(event.deferReply(true)).thenReturn(defer); when(event.getHook()).thenReturn(hook);
        when(service.confirm(owner, "gamerpower:1", "200000000000000001", "token"))
                .thenReturn(new FreebieDeliveryAdmin.View("queued", List.of()));
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            new org.bunnys.buttons.FreebieDeliveryButtons().execute(null, event,
                    new String[]{"freebie_delivery", "retry", "1", "200000000000000001", "token"});
        }
        var order = inOrder(event, defer, service);
        order.verify(event).deferReply(true); order.verify(defer).complete();
        order.verify(service).confirm(owner, "gamerpower:1", "200000000000000001", "token");
    }
    @Test void unauthorizedButtonNeverReachesOwnerService() {
        var system = mock(FreebieSystem.class);
        var event = mock(net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getUser().getId()).thenReturn("outsider"); when(system.config()).thenReturn(config);
        try (var current = mockStatic(FreebieSystem.class)) {
            current.when(FreebieSystem::current).thenReturn(Optional.of(system));
            new org.bunnys.buttons.FreebieDeliveryButtons().execute(null, event,
                    new String[]{"freebie_delivery", "retry", "1", "200000000000000001", "token"});
        }
        verify(system, never()).deliveryAdmin();
        verify(event.reply("Only freebie owners can use this.")).setEphemeral(true);
    }}


