package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** "Your alert channel / ping role was deleted" notices: queued by cleanup, sent by the notice worker. */
class FreebieChangeNoticeTest {
    static final String GUILD = "100000000000000000", GONE = "200000000000000002", KEPT = "200000000000000001",
            ROLE = "300000000000000001";
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final FreebieDeliveryStore deliveries = mock(FreebieDeliveryStore.class);
    final FreebieNoticeStore notices = mock(FreebieNoticeStore.class);
    final FreebieCleanup cleanup = new FreebieCleanup(subscriptions, deliveries, notices);

    static FreebieSubscriptions.Subscription sub(String channel, FreebieStore store, String role) {
        return new FreebieSubscriptions.Subscription(GUILD, channel, store, Optional.ofNullable(role));
    }

    @Test void deletedChannelQueuesOneNoticeNamingTheLaunchersThatStopped() {
        when(subscriptions.forGuild(GUILD)).thenReturn(List.of(sub(GONE, FreebieStore.EPIC, null), sub(GONE, FreebieStore.STEAM, ROLE),
                sub(KEPT, FreebieStore.GOG, null)));
        when(subscriptions.remove(GUILD, GONE, Optional.empty())).thenReturn(2L);
        cleanup.channelDeleted(GUILD, GONE, Optional.of("epic-games"), now);
        verify(notices).enqueueChange(GUILD, FreebieNoticeStore.CHANNEL_DELETED, GONE, Optional.of("epic-games"),
                List.of("epic", "steam"), List.of(GONE), now);
    }

    @Test void noNoticeWhenTheChannelHadNoSettings() {
        when(subscriptions.forGuild(GUILD)).thenReturn(List.of(sub(KEPT, FreebieStore.GOG, null)));
        cleanup.channelDeleted(GUILD, GONE, Optional.empty(), now);
        verifyNoInteractions(notices);
    }

    @Test void deletedRoleQueuesOneNoticeWithTheChannelsThatKeepPosting() {
        when(subscriptions.forGuild(GUILD)).thenReturn(List.of(sub(KEPT, FreebieStore.EPIC, ROLE), sub(KEPT, FreebieStore.STEAM, ROLE),
                sub(GONE, FreebieStore.GOG, "300000000000000009")));
        when(subscriptions.clearRole(GUILD, ROLE)).thenReturn(2L);
        cleanup.roleDeleted(GUILD, ROLE, Optional.of("Pings"), now);
        verify(notices).enqueueChange(GUILD, FreebieNoticeStore.ROLE_DELETED, ROLE, Optional.of("Pings"),
                List.of("epic", "steam"), List.of(KEPT), now);
    }

    @Test void messagesExplainWhatChangedAndWhatToDo() {
        var channel = FreebieMessages.changeNotice("My Server", new FreebieNoticeStore.Change(FreebieNoticeStore.CHANNEL_DELETED,
                GONE, Optional.of("epic-games"), List.of("epic", "steam"), List.of(GONE)));
        var embed = channel.getEmbeds().getFirst();
        assertEquals("A free-game alert channel was deleted", embed.getTitle().replaceAll("^\\S+\\s", ""));
        String all = embed.getDescription() + embed.getFields().stream().map(f -> f.getValue()).toList();
        assertTrue(all.contains("#epic-games") && all.contains("Epic Games Store, Steam") && all.contains("`/free-games setup`"), all);
        assertTrue(channel.getAllowedMentions().isEmpty());
        var role = FreebieMessages.changeNotice("My Server", new FreebieNoticeStore.Change(FreebieNoticeStore.ROLE_DELETED,
                ROLE, Optional.empty(), List.of("gog"), List.of(KEPT)));
        String text = role.getEmbeds().getFirst().getFields().stream().map(f -> f.getValue()).toList().toString();
        assertTrue(text.contains("<#" + KEPT + ">") && text.contains("without a ping"), text);
    }

    // ------------------------------------------------------------------ notice worker

    final FreebieRepository repository = mock(FreebieRepository.class);
    final JDA client = mock(JDA.class);
    final Queue<Runnable> tasks = new ArrayDeque<>();
    final FreebieNotifier notifier = new FreebieNotifier(repository, () -> client, Clock.fixed(now, ZoneOffset.UTC), tasks::add);
    final String id = "change|" + GUILD + "|CHANNEL_DELETED|" + GONE;
    final FreebieNoticeStore.Claim job = new FreebieNoticeStore.Claim(GUILD, null, null, GONE, null, "token", null, null, null,
            false, id, Optional.of(new FreebieNoticeStore.Change(FreebieNoticeStore.CHANNEL_DELETED, GONE, Optional.of("epic-games"),
            List.of("epic"), List.of(GONE))));

    void worker() {
        when(repository.notices()).thenReturn(notices);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(notices.claim(now)).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
    }

    @Test @SuppressWarnings("unchecked") void changeNoticeIsSentToTheOwnerWithItsOwnReference() {
        worker();
        var guild = mock(Guild.class);
        when(client.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getOwnerId()).thenReturn("owner"); when(guild.getName()).thenReturn("Server");
        CacheRestAction<PrivateChannel> open = mock(CacheRestAction.class, RETURNS_SELF);
        var dm = mock(PrivateChannel.class);
        var action = mock(MessageCreateAction.class, RETURNS_SELF);
        when(client.openPrivateChannelById("owner")).thenReturn(open);
        when(open.submit()).thenReturn(CompletableFuture.completedFuture(dm));
        when(dm.getId()).thenReturn("dm");
        when(dm.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        var message = mock(Message.class);
        when(message.getId()).thenReturn("accepted");
        when(action.submit()).thenReturn(CompletableFuture.completedFuture(message));
        when(notices.destination(any(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(notices.owns(any(), any())).thenReturn(true);
        notifier.tick();
        while (!tasks.isEmpty()) tasks.remove().run();
        var sent = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(dm).sendMessage(sent.capture());
        var embed = sent.getValue().getEmbeds().getFirst();
        assertTrue(embed.getTitle().contains("alert channel was deleted"));
        assertEquals(FreebieMessages.noticeReference(id), embed.getFooter().getText());
        verify(action).setNonce(FreebieMessages.nonce("notice|" + id));
        verify(notices).sent(job, "accepted", now);
        verify(repository, never()).offer(any());
    }

    @Test void changeNoticeForAServerTheBotLeftIsDroppedNotRetriedForever() {
        worker();
        notifier.tick();
        verify(notices).drop(job, now);
        verify(notices, never()).retry(any(), any());
    }

    @Test void changeNoticeWaitsWhileTheServerIsUnavailable() {
        worker();
        when(client.isUnavailable(Long.parseLong(GUILD))).thenReturn(true);
        notifier.tick();
        while (!tasks.isEmpty()) tasks.remove().run();
        verify(notices, never()).drop(any(), any());
        verify(notices).retry(job, now);
    }
}
