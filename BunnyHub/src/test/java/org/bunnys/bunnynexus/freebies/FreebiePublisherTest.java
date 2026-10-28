package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Publication decisions against mocked Discord and stores: reconcile, never resend, never publish the ineligible. */
class FreebiePublisherTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", MESSAGE = "900000000000000000";
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebiePublicationStore jobs = mock(FreebiePublicationStore.class);
    final FreebieAnnouncementStore pages = mock(FreebieAnnouncementStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final NewsChannel channel = mock(NewsChannel.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final FreebieOffer offer = FreebieAnnouncementCardsTest.epic("a", "Game a");
    final String delivery = offer.id() + "|" + CHANNEL;
    final Document page = new Document("_id", "epic|m|1|" + CHANNEL + "|1").append("members", List.of(offer.id()))
            .append("deliveries", List.of(delivery)).append("revision", 1L).append("applied", 1L).append("state", "SENT");
    FreebieFeatures features = new FreebieFeatures(true, true, Set.of(CHANNEL), false, true, false);

    @BeforeEach void setup() {
        when(repository.publications()).thenReturn(jobs);
        when(repository.announcements()).thenReturn(pages);
        when(pages.get(page.getString("_id"))).thenReturn(Optional.of(page));
        when(pages.settled(any())).thenReturn(true);
        var doc = FreebieSourceStore.fresh(FreebieIdentityTest.epic(Instant.parse("2026-10-22T15:00:00Z"), Instant.parse("2026-10-29T15:00:00Z")), now);
        doc.put("_id", offer.id()); doc.put("state", "APPROVED"); doc.put("decidedBy", "owner");
        when(repository.offerDocument(offer.id())).thenReturn(Optional.of(doc));
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        when(guild.getNewsChannelById(CHANNEL)).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
        when(jobs.submitting(any(), any())).thenReturn(true);
    }

    FreebiePublicationStore.Job job(int attempts, boolean uncertain) {
        return new FreebiePublicationStore.Job(CHANNEL + "|" + MESSAGE, page.getString("_id"), GUILD, CHANNEL, MESSAGE, 1, "epic|m|1",
                attempts, uncertain, "token");
    }

    FreebiePublisher publisher() { return new FreebiePublisher(repository, features, work::add, Clock.fixed(now, ZoneOffset.UTC)); }

    void run(FreebiePublicationStore.Job job) {
        boolean async = publisher().start(client, job, () -> {});
        if (async) while (!work.isEmpty()) work.remove().run();
    }

    @SuppressWarnings("unchecked") // Generic REST action mocks.
    Message posted(EnumSet<Message.MessageFlag> flags, CompletableFuture<Message> crosspost) {
        var message = mock(Message.class);
        var author = mock(User.class);
        when(author.getId()).thenReturn("bot");
        when(message.getAuthor()).thenReturn(author);
        when(message.getId()).thenReturn(MESSAGE);
        when(message.getFlags()).thenReturn(flags);
        var alert = FreebieMessages.alert(offer, Optional.empty(), FreebieMessages.reference(delivery));
        when(message.getEmbeds()).thenReturn(alert.getEmbeds());
        RestAction<Message> retrieve = mock(RestAction.class, RETURNS_SELF);
        when(retrieve.submit()).thenReturn(CompletableFuture.completedFuture(message));
        when(channel.retrieveMessageById(MESSAGE)).thenReturn(retrieve);
        RestAction<Message> cross = mock(RestAction.class, RETURNS_SELF);
        when(cross.submit()).thenReturn(crosspost);
        when(message.crosspost()).thenReturn(cross);
        return message;
    }

    @Test void publishesTheExactSavedMessageOnce() {
        var job = job(1, false);
        var message = posted(EnumSet.noneOf(Message.MessageFlag.class), CompletableFuture.completedFuture(mock(Message.class)));
        run(job);
        verify(jobs).submitting(job, now);
        verify(message).crosspost();
        verify(jobs).published(job, false, now);
        verify(channel, never()).sendMessage(any(net.dv8tion.jda.api.utils.messages.MessageCreateData.class));
    }

    @Test void anAlreadyCrosspostedOriginalIsReconciledWithoutAnotherRequest() {
        var job = job(2, true); // e.g. a crash after Discord accepted the earlier request
        var message = posted(EnumSet.of(Message.MessageFlag.CROSSPOSTED), new CompletableFuture<>());
        run(job);
        verify(message, never()).crosspost();
        verify(jobs).published(job, true, now);
    }

    @Test void isCrosspostIsAFollowerCopyNotAPublishedOriginal() {
        var message = posted(EnumSet.of(Message.MessageFlag.IS_CROSSPOST), CompletableFuture.completedFuture(mock(Message.class)));
        assertFalse(FreebiePublisher.alreadyPublished(message));
        run(job(1, false));
        verify(message).crosspost();
    }

    @Test void alreadyCrosspostedErrorIsReconciledAndTimeoutsStayUncertain() {
        var job = job(1, false);
        posted(EnumSet.noneOf(Message.MessageFlag.class), CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.ALREADY_CROSSPOSTED, false, 40033)));
        run(job);
        verify(jobs).published(job, true, now);
        var timeout = job(1, false);
        posted(EnumSet.noneOf(Message.MessageFlag.class), CompletableFuture.failedFuture(new TimeoutException()));
        run(timeout);
        verify(jobs).uncertain(eq(timeout), anyString(), eq(now.plus(FreebiePublisher.UNCERTAIN)), eq(now));
        verify(channel, never()).sendMessage(any(net.dv8tion.jda.api.utils.messages.MessageCreateData.class));
    }

    @Test void missingPermissionsAndTextChannelsBlockWithoutConverting() {
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(false);
        var job = job(1, false);
        run(job);
        verify(jobs).blocked(eq(job), contains("missing"), eq(now.plus(FreebiePublisher.RECHECK)), eq(now));
        when(guild.getNewsChannelById(CHANNEL)).thenReturn(null);
        doReturn(mock(TextChannel.class)).when(guild).getGuildChannelById(CHANNEL);
        var text = job(1, false);
        run(text);
        verify(jobs).blocked(eq(text), eq("not an Announcement Channel"), any(), eq(now));
        verify(channel, never()).getManager();
    }

    @Test void rateLimitsAndTheHourlyBudgetDelayWithAPersistedTime() {
        when(jobs.usedLastHour(CHANNEL, now)).thenReturn((long) FreebiePublicationStore.HOURLY_LIMIT + 1);
        Instant frees = now.plus(Duration.ofMinutes(17));
        when(jobs.budgetFreesAt(CHANNEL, now)).thenReturn(frees);
        var job = job(1, false);
        run(job);
        verify(jobs).delay(job, "hourly publish limit", frees, now);
        when(jobs.usedLastHour(CHANNEL, now)).thenReturn(1L);
        var limited = job(1, false);
        posted(EnumSet.noneOf(Message.MessageFlag.class), CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.ANNOUNCEMENT_RATE_LIMIT, false, 20022)));
        run(limited);
        verify(jobs).delay(limited, "Discord announcement rate limit", now.plus(Duration.ofHours(1)), now);
    }

    @Test void expiredOrStoppedAnnouncementsAreSkippedAndPendingEditsWaitFirst() {
        var doc = repository.offerDocument(offer.id()).orElseThrow();
        doc.put("state", "STOPPED");
        var job = job(1, false);
        run(job);
        verify(jobs).skipped(eq(job), contains("no game"), eq(now));
        doc.put("state", "APPROVED");
        when(pages.settled(any())).thenReturn(false);
        var waiting = job(1, false);
        run(waiting);
        verify(jobs).delay(eq(waiting), eq("waiting for the latest edit"), any(), eq(now));
    }

    @Test void laterPagesWaitForEarlierOnesAndDisabledChannelsAreCancelled() {
        when(jobs.earlierPageOpen(any())).thenReturn(true);
        var job = job(1, false);
        run(job);
        verify(jobs).delay(eq(job), eq("waiting for the previous page"), any(), eq(now));
        features = new FreebieFeatures(true, true, Set.of("999999999999999999"), false, true, false);
        var other = job(1, false);
        run(other);
        verify(jobs).cancelled(eq(other), anyString(), eq(now));
    }

    @Test void disablingStopsPendingPublicationsAndDropsUnplannedPages() {
        features = new FreebieFeatures(true, false, Set.of(), false, true, false);
        var publisher = publisher();
        publisher.prepare();
        verify(jobs).cancelOpen(Optional.empty(), now);
        verify(pages).dropPublishPending(Optional.empty());
        verify(pages, never()).publishPending(anyInt());
        assertFalse(publisher.claimOne(client, new AtomicInteger(), new AtomicInteger()));
        verify(jobs, never()).claim(any(), any());
    }

    @Test void enabledPlanningIsIdempotentAndOnlyForMarkedPages() {
        when(pages.publishPending(anyInt())).thenReturn(List.of(page));
        publisher().prepare();
        verify(jobs).plan(page, now);
        verify(pages).publishPlanned(page.getString("_id"));
        verify(jobs).cancelOpen(Optional.of(Set.of(CHANNEL)), now);
    }
}
