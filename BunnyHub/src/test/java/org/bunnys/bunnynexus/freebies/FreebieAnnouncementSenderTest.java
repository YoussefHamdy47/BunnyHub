package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** First posts of announcement pages against mocked Discord and storage. */
class FreebieAnnouncementSenderTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", ROLE = "300000000000000000",
            MESSAGE = "900000000000000000", KEY = "epic|m|1792508400";
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieAnnouncementStore store = mock(FreebieAnnouncementStore.class);
    final FreebieDeliveryStore deliveries = mock(FreebieDeliveryStore.class);
    final FreebieRoutes routes = mock(FreebieRoutes.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final FreebieNotifier notifier = mock(FreebieNotifier.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final FreebieOffer a = FreebieAnnouncementCardsTest.epic("a", "Game a"), b = FreebieAnnouncementCardsTest.epic("b", "Game b");
    final MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
    final CompletableFuture<Message> sent = new CompletableFuture<>();
    int released;

    @BeforeEach void setup() {
        when(repository.announcements()).thenReturn(store);
        when(repository.deliveries()).thenReturn(deliveries);
        when(repository.routes()).thenReturn(routes);
        when(deliveries.owns(any(), any())).thenReturn(true);
        when(deliveries.get(anyString())).thenAnswer(call -> Optional.of(new Document("_id", call.getArgument(0))));
        for (var offer : List.of(a, b)) {
            when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
            var doc = FreebieSourceStore.fresh(FreebieIdentityTest.epic(Instant.parse("2026-10-22T15:00:00Z"), Instant.parse("2026-10-29T15:00:00Z")), now);
            doc.put("_id", offer.id()); doc.put("title", offer.title()); doc.put("state", "APPROVED"); doc.put("decidedBy", "owner");
            when(repository.offerDocument(offer.id())).thenReturn(Optional.of(doc));
        }
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(send);
        when(send.submit()).thenReturn(sent);
        when(store.submitting(anyString(), anyString(), any())).thenReturn(true);
        when(store.sent(anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
    }

    FreebieRepository.Claimed job(FreebieOffer offer) {
        return new FreebieRepository.Claimed(offer.id() + "|" + CHANNEL, offer.id(), GUILD, CHANNEL, Optional.of(ROLE), 1, false,
                "token-" + offer.id(), false, Optional.of(KEY));
    }

    static Document page(int number, String state, List<FreebieOffer> members, boolean verifyFirst, String token, Instant lease) {
        return new Document("_id", FreebieAnnouncementStore.pageId(KEY, CHANNEL, number)).append("key", KEY).append("channelId", CHANNEL)
                .append("guildId", GUILD).append("page", number).append("roleId", number == 1 ? ROLE : null)
                .append("members", members.stream().map(FreebieOffer::id).toList())
                .append("deliveries", members.stream().map(o -> o.id() + "|" + CHANNEL).toList())
                .append("state", state).append("verifyFirst", verifyFirst).append("token", token)
                .append("leaseUntil", lease == null ? null : Date.from(lease)).append("messageId", "SENT".equals(state) ? MESSAGE : null)
                .append("revision", 1L).append("applied", 1L);
    }

    boolean start(FreebieRepository.Claimed job) {
        var sender = new FreebieAnnouncementSender(repository, new FreebieFeatures(true, true, Set.of(CHANNEL), true, true, false),
                work::add, Clock.fixed(now, ZoneOffset.UTC), notifier).withSubscriptions(subscriptions);
        return sender.start(client, job, guild, channel, Optional.of(ROLE), () -> released++);
    }
    void drain() { while (!work.isEmpty()) work.remove().run(); }

    @Test void gamesQueuedTogetherShareOneMessageAndOnePing() {
        var job = job(a);
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(KEY), eq(CHANNEL), eq(a.id()), anyInt()))
                .thenReturn(List.of(new Document("_id", b.id() + "|" + CHANNEL).append("offerId", b.id())));
        var created = page(1, "SUBMITTING", List.of(a, b), false, job.token(), now.plusSeconds(180));
        when(store.create(eq(KEY), eq(GUILD), eq(CHANNEL), eq(1), eq(Optional.of(ROLE)), eq(List.of(a.id(), b.id())), any(), eq(job.token()), any(), eq(now)))
                .thenReturn(Optional.of(created));
        assertTrue(start(job));
        drain();
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel, times(1)).sendMessage(captor.capture());
        assertEquals(2, captor.getValue().getEmbeds().size());
        assertEquals(Set.of(ROLE), captor.getValue().getMentionedRoles());
        verify(send).setNonce(FreebieAnnouncementStore.nonce(created.getString("_id")));
        verify(store).submitting(created.getString("_id"), job.token(), now);
        var message = mock(Message.class);
        when(message.getId()).thenReturn(MESSAGE);
        sent.complete(message);
        drain();
        verify(store).sent(created.getString("_id"), job.token(), MESSAGE, true, now);
        verify(repository).markSent(job, MESSAGE, now);
        verify(routes).followPage(List.of(b.id() + "|" + CHANNEL), MESSAGE, now);
        assertEquals(1, released);
    }

    @Test void aLaterMemberJoinsThePostedPageSilently() {
        var job = job(b);
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(page(1, "SENT", List.of(a), false, null, null)));
        when(store.join(FreebieAnnouncementStore.pageId(KEY, CHANNEL, 1), b.id(), job.id(), now)).thenReturn(true);
        assertFalse(start(job));
        verify(repository).markSent(job, MESSAGE, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void aPageThatMayHaveBeenSentIsFoundInHistoryNotResent() {
        var job = job(a);
        var uncertain = page(1, "SUBMITTING", List.of(a, b), true, "dead", now.minusSeconds(1));
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(uncertain));
        when(store.takeOver(eq(uncertain), eq(job.token()), any(), eq(now))).thenReturn(Optional.of(uncertain));
        var posted = mock(Message.class);
        var author = mock(User.class);
        when(author.getId()).thenReturn("bot");
        when(posted.getAuthor()).thenReturn(author);
        when(posted.getId()).thenReturn(MESSAGE);
        var members = FreebieAnnouncementSender.members(repository, uncertain, now);
        when(posted.getEmbeds()).thenReturn(FreebieAnnouncementCards.embeds(members, now));
        history(List.of(posted));
        assertTrue(start(job));
        drain();
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        verify(store).sent(uncertain.getString("_id"), job.token(), MESSAGE, true, now);
        verify(repository).markSent(job, MESSAGE, now);
    }

    @Test void missingHistoryPermissionBlocksInsteadOfResending() {
        var job = job(a);
        var uncertain = page(1, "SUBMITTING", List.of(a), true, "dead", null);
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(uncertain));
        when(store.takeOver(eq(uncertain), eq(job.token()), any(), eq(now))).thenReturn(Optional.of(uncertain));
        when(self.hasPermission(channel, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertFalse(start(job));
        verify(deliveries).block(job, FreebieDeliveryStore.Block.HISTORY_PERMISSION, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void anotherSubmitterHoldingTheLeaseMakesTheRowWait() {
        var job = job(b);
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(page(1, "SUBMITTING", List.of(a, b), false, "other", now.plusSeconds(60))));
        when(store.takeOver(any(), anyString(), any(), any())).thenReturn(Optional.empty());
        assertFalse(start(job));
        verify(repository).release(job, now.plusSeconds(10), now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void approvalWithdrawnBeforeSubmissionCancelsWithoutAMessage() {
        var job = job(a);
        repository.offerDocument(a.id()).orElseThrow().put("state", "STOPPED");
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of());
        var created = page(1, "SUBMITTING", List.of(a), false, job.token(), now.plusSeconds(180));
        when(store.create(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(created));
        assertTrue(start(job));
        drain();
        verify(store).cancel(created.getString("_id"), job.token(), now);
        verify(repository).markCancelled(job, "offer no longer eligible", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void continuationPagesNeverPing() {
        var job = job(b);
        var full = page(1, "SENT", List.of(a), false, null, null);
        full.put("members", Collections.nCopies(FreebieAnnouncementStore.PAGE_SIZE, a.id()));
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(full));
        var second = page(2, "SUBMITTING", List.of(b), false, job.token(), now.plusSeconds(180));
        when(store.create(eq(KEY), any(), any(), eq(2), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(second));
        assertTrue(start(job));
        drain();
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel).sendMessage(captor.capture());
        assertTrue(captor.getValue().getMentionedRoles().isEmpty());
        assertEquals("", captor.getValue().getContent());
    }

    @Test void aFailedPageFailsItsRowsUntilAnOwnerRetryReopensItWithHistoryFirst() {
        var job = job(a);
        var failed = page(1, "FAILED", List.of(a), true, null, null).append("failure", "MISSING_PERMISSIONS")
                .append("finishedAt", Date.from(now.minusSeconds(60)));
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(failed));
        when(deliveries.get(job.id())).thenReturn(Optional.of(new Document("_id", job.id())));
        when(repository.markFailed(any(), any(), any(), any())).thenReturn(true);
        assertFalse(start(job));
        verify(repository).markFailed(job, FreebieFailure.MISSING_PERMISSIONS, "announcement page was rejected", now);
        verify(store, never()).reopen(any(), any(), any(), any());

        retried(job);
        var reopened = page(1, "SUBMITTING", List.of(a), true, job.token(), now.plusSeconds(180));
        when(store.reopen(eq(failed), eq(job.token()), any(), eq(now))).thenReturn(Optional.of(reopened));
        history(List.of());
        destination(true);
        assertTrue(start(job));
        drain();
        verify(channel.getHistory()).retrievePast(50);
        verify(channel).sendMessage(any(MessageCreateData.class));
    }

    /** The owner previewed and confirmed a retry, then the server moved the launcher elsewhere before submission. */
    @Test void aRetryIsNotSentWhenTheSubscriptionChangedAfterThePreview() {
        var job = job(a);
        var failed = page(1, "FAILED", List.of(a), true, null, null).append("failure", "MISSING_PERMISSIONS")
                .append("finishedAt", Date.from(now.minusSeconds(60)));
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(failed));
        retried(job);
        var reopened = page(1, "SUBMITTING", List.of(a), true, job.token(), now.plusSeconds(180));
        when(store.reopen(eq(failed), eq(job.token()), any(), eq(now))).thenReturn(Optional.of(reopened));
        history(List.of());
        destination(false); // the channel no longer has this launcher with this role
        assertTrue(start(job));
        drain();
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        verify(store, never()).submitting(anyString(), anyString(), any());
        verify(deliveries).block(job, FreebieDeliveryStore.Block.DESTINATION, now);
        verify(store).release(reopened.getString("_id"), job.token(), now);
    }

    @Test void aRetryThatFindsTheEarlierPostInHistoryNeverResendsEvenIfSettingsChanged() {
        var job = job(a);
        var failed = page(1, "FAILED", List.of(a), true, null, null).append("failure", "MISSING_PERMISSIONS")
                .append("finishedAt", Date.from(now.minusSeconds(60)));
        when(store.pages(KEY, CHANNEL)).thenReturn(List.of(failed));
        retried(job);
        var reopened = page(1, "SUBMITTING", List.of(a), true, job.token(), now.plusSeconds(180));
        when(store.reopen(eq(failed), eq(job.token()), any(), eq(now))).thenReturn(Optional.of(reopened));
        var posted = mock(Message.class);
        var author = mock(User.class);
        when(author.getId()).thenReturn("bot");
        when(posted.getAuthor()).thenReturn(author);
        when(posted.getId()).thenReturn(MESSAGE);
        var embeds = FreebieAnnouncementCards.embeds(FreebieAnnouncementSender.members(repository, reopened, now), now);
        when(posted.getEmbeds()).thenReturn(embeds);
        history(List.of(posted));
        destination(false);
        assertTrue(start(job));
        drain();
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        verify(repository).markSent(job, MESSAGE, now);
    }

    void retried(FreebieRepository.Claimed job) {
        when(deliveries.get(job.id())).thenReturn(Optional.of(new Document("_id", job.id()).append("guildId", GUILD)
                .append("channelId", CHANNEL).append("roleId", ROLE).append("verifyFirst", true).append("manualRetries", 1)
                .append("retryAudit", List.of(new Document("at", Date.from(now.minusSeconds(5)))))));
    }

    /** The normal destination check's inputs: connected, channel and permissions present, role pingable, subscription. */
    void destination(boolean subscribed) {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        var role = mock(Role.class);
        when(role.isMentionable()).thenReturn(true);
        when(guild.getRoleById(ROLE)).thenReturn(role);
        when(subscriptions.forGuild(GUILD)).thenReturn(subscribed
                ? List.of(new FreebieSubscriptions.Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.of(ROLE), 0))
                : List.of(new FreebieSubscriptions.Subscription(GUILD, "299999999999999999", FreebieStore.EPIC, Optional.of(ROLE), 0)));
    }

    @SuppressWarnings("unchecked") // Generic REST action mock.
    void history(List<Message> messages) {
        var history = mock(MessageHistory.class);
        RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(messages));
        when(history.retrievePast(50)).thenReturn(past);
        when(channel.getHistory()).thenReturn(history);
    }
}
