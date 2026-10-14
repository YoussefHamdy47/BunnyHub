package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieDmJobs.Job;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DM job transitions against mocked Discord and storage. */
class FreebieDmWorkerTest {
    static final String USER = "400000000000000000";
    final Instant now = FreebieLifecycleTest.END.minus(Duration.ofHours(30));
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieDmJobs jobs = mock(FreebieDmJobs.class);
    final FreebieDmSubscribers subscribers = mock(FreebieDmSubscribers.class);
    final JDA client = mock(JDA.class);
    final PrivateChannel dm = mock(PrivateChannel.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final AtomicInteger shared = new AtomicInteger(), background = new AtomicInteger();
    final FreebieOffer offer = FreebieLifecycleTest.live();
    FreebieDmWorker worker;

    @SuppressWarnings("unchecked") // Generic REST action mock.
    @BeforeEach void setup() {
        when(repository.dmJobs()).thenReturn(jobs);
        when(repository.dmSubscribers()).thenReturn(subscribers);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        when(subscribers.sections(USER)).thenReturn(Optional.of(EnumSet.of(FreebieSection.MAJOR_PC)));
        when(subscribers.countToday(USER, now)).thenReturn(true);
        when(jobs.counted(any(), any())).thenReturn(true);
        when(jobs.submitting(any(), any(), any())).thenReturn(true);
        when(jobs.cancelled(any(), any(), any())).thenReturn(true);
        CacheRestAction<PrivateChannel> open = mock(CacheRestAction.class, RETURNS_SELF);
        when(open.submit()).thenReturn(CompletableFuture.completedFuture(dm));
        when(client.openPrivateChannelById(USER)).thenReturn(open);
        when(dm.getId()).thenReturn("dmchannel");
        var self = mock(SelfUser.class); when(self.getId()).thenReturn("bot"); when(client.getSelfUser()).thenReturn(self);
        worker = new FreebieDmWorker(repository, work::add, Clock.fixed(now, ZoneOffset.UTC));
    }

    Job job(int attempts, boolean verifyFirst, boolean counted) {
        var job = new Job(FreebieDm.jobId(offer.id(), USER), offer.id(), USER, attempts, verifyFirst, counted, null, "token");
        when(jobs.claim(any(), any())).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        return job;
    }
    MessageCreateAction sendable(CompletableFuture<Message> result) {
        var action = mock(MessageCreateAction.class, RETURNS_SELF);
        when(action.submit()).thenReturn(result);
        when(dm.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        return action;
    }
    void run() { worker.tick(client, shared, background); while (!work.isEmpty()) work.remove().run(); }

    @Test void sendsTheCardOnceCountsItAndRecordsSent() {
        var job = job(1, false, false);
        var message = mock(Message.class); when(message.getId()).thenReturn("m1");
        var action = sendable(CompletableFuture.completedFuture(message));
        run();
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(dm).sendMessage(captor.capture());
        assertTrue(captor.getValue().getAllowedMentions().isEmpty());
        assertTrue(captor.getValue().getContent().isEmpty());
        verify(action).setNonce(FreebieDm.nonce(job.id()));
        verify(subscribers).countToday(USER, now);
        verify(jobs).submitting(job, "dmchannel", now);
        verify(jobs).sent(job, "m1", now);
        assertEquals(0, shared.get()); assertEquals(0, background.get());
    }

    @Test void overTheDailyCapTheJobIsSkippedAndNothingIsSent() {
        var job = job(1, false, false);
        when(subscribers.countToday(USER, now)).thenReturn(false);
        sendable(new CompletableFuture<>());
        run();
        verify(jobs).skipped(job, FreebieDm.Skip.DAILY_CAP, now);
        verify(client, never()).openPrivateChannelById(anyString());
    }

    @Test void aRetriedDmIsNeverCountedTwice() {
        job(2, true, true);
        var history = mock(MessageHistory.class);
        @SuppressWarnings("unchecked") RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        when(dm.getHistory()).thenReturn(history); when(history.retrievePast(50)).thenReturn(past);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(List.of()));
        sendable(new CompletableFuture<>());
        run();
        verify(subscribers, never()).countToday(any(), any());
    }

    @Test void cannotSendToUserRemovesTheirSubscriptionAndEndsTheirQueuedDms() {
        var job = job(1, false, false);
        sendable(CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.CANNOT_SEND_TO_USER, false, 50007)));
        run();
        verify(jobs).cancelled(job, FreebieDm.Cancel.CANNOT_DM, now);
        verify(subscribers).unsubscribe(USER);
        verify(jobs).cancelForUser(USER, FreebieDm.Cancel.CANNOT_DM, now);
        verify(jobs, never()).retry(any(), any(), anyBoolean(), any(), any());
    }

    @Test void otherErrorsFollowTheAlertBackoffThenFail() {
        var job = job(1, false, false);
        sendable(CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.SERVER_ERROR, true, 0)));
        run();
        verify(jobs).retry(eq(job), eq(now.plusSeconds(30)), eq(true), any(), eq(now));
        verify(subscribers, never()).unsubscribe(any());
        var last = job(FreebieFailure.MAX_ATTEMPTS, true, true);
        var history = mock(MessageHistory.class);
        @SuppressWarnings("unchecked") RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        when(dm.getHistory()).thenReturn(history); when(history.retrievePast(50)).thenReturn(past);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(List.of()));
        run();
        verify(jobs).failed(eq(last), any(), eq(now));
        var exhausted = job(FreebieFailure.MAX_ATTEMPTS + 1, true, true);
        run();
        verify(jobs).failed(exhausted, "attempts exhausted", now);
    }

    @Test void stoppedOffersAndUnsubscribedUsersAreCancelledEvenRightBeforeSending() {
        var job = job(1, false, false);
        var stopped = FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, false, Optional.of(FreebieLifecycleTest.END), false, true);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer)).thenReturn(Optional.of(stopped));
        sendable(new CompletableFuture<>());
        run();
        verify(jobs).cancelled(job, FreebieDm.Cancel.OFFER_ENDED, now);
        verify(dm, never()).sendMessage(any(MessageCreateData.class));

        var other = job(1, false, true);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        when(subscribers.sections(USER)).thenReturn(Optional.of(EnumSet.of(FreebieSection.CONSOLE)));
        run();
        verify(jobs).cancelled(other, FreebieDm.Cancel.UNSUBSCRIBED, now);
    }

    @Test void anUncertainDmFoundInHistoryIsRecordedNotResent() {
        var job = job(2, true, true);
        var history = mock(MessageHistory.class);
        @SuppressWarnings("unchecked") RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        var earlier = mock(Message.class); var author = mock(User.class);
        when(author.getId()).thenReturn("bot"); when(earlier.getAuthor()).thenReturn(author); when(earlier.getId()).thenReturn("earlier");
        when(earlier.getEmbeds()).thenReturn(FreebieDm.card(offer, job.id()).getEmbeds());
        when(dm.getHistory()).thenReturn(history); when(history.retrievePast(50)).thenReturn(past);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(List.of(earlier)));
        sendable(new CompletableFuture<>());
        run();
        verify(jobs).sent(job, "earlier", now);
        verify(dm, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void aLostLeaseNeverSends() {
        job(1, false, false);
        when(jobs.submitting(any(), any(), any())).thenReturn(false);
        sendable(new CompletableFuture<>());
        run();
        verify(dm, never()).sendMessage(any(MessageCreateData.class));
        verify(jobs, never()).sent(any(), any(), any());
    }

    @Test void usesAtMostHalfTheBudgetAndOnlyWhatBackgroundWorkLeaves() {
        when(jobs.claim(any(), any())).thenAnswer(i -> Optional.of(new Job(UUID.randomUUID().toString(), offer.id(), USER, 1, false, true,
                null, UUID.randomUUID().toString())));
        sendable(new CompletableFuture<>());
        worker.tick(client, shared, background);
        assertEquals(FreebieDmWorker.MAX_IN_FLIGHT, worker.inFlight());
        assertEquals(FreebieSender.BACKGROUND_SLOTS, background.get());

        var other = new FreebieDmWorker(repository, work::add, Clock.fixed(now, ZoneOffset.UTC));
        var busy = new AtomicInteger(FreebieSender.BACKGROUND_SLOTS - 1);
        other.tick(client, new AtomicInteger(), busy);
        assertEquals(1, other.inFlight(), "catch-up and card edits share the same half");
    }
}
