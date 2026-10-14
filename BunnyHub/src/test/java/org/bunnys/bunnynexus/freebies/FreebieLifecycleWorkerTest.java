package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Cancel;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Kind;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Skip;
import org.bunnys.bunnynexus.freebies.FreebieLifecycleStore.Job;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Job state transitions of ended-card edits and reminders against mocked Discord and storage. */
class FreebieLifecycleWorkerTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", MESSAGE = "900000000000000000",
            ROLE = "300000000000000000";
    final Instant now = FreebieLifecycleTest.END.minus(Duration.ofHours(24));
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieLifecycleStore store = mock(FreebieLifecycleStore.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final AtomicInteger shared = new AtomicInteger();
    final FreebieOffer offer = FreebieLifecycleTest.live();
    final String reference = FreebieMessages.reference(offer.id() + "|" + CHANNEL);
    FreebieLifecycleWorker worker;

    @BeforeEach void setup() {
        when(repository.lifecycle()).thenReturn(store);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
        when(store.owns(any(), any())).thenReturn(true);
        when(store.submitting(any(), any())).thenReturn(true);
        when(subscriptions.find(GUILD, CHANNEL, offer.store()))
                .thenReturn(Optional.of(new FreebieSubscriptions.Subscription(GUILD, CHANNEL, offer.store(), Optional.of(ROLE), 24)));
        worker = new FreebieLifecycleWorker(repository, subscriptions, work::add, Clock.fixed(now, ZoneOffset.UTC));
    }

    Job job(Kind kind, int attempts, boolean verifyFirst) {
        String id = kind == Kind.EDIT ? FreebieLifecycleStore.editId(offer.id() + "|" + CHANNEL) : FreebieLifecycleStore.remindId(offer.id(), CHANNEL);
        var job = new Job(id, kind, offer.id(), offer.id() + "|" + CHANNEL, GUILD, CHANNEL, MESSAGE, false, 24, attempts, verifyFirst, "token");
        when(store.claim(any(), any())).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        return job;
    }
    void run() { worker.tick(client, shared); while (!work.isEmpty()) work.remove().run(); }

    @SuppressWarnings("unchecked") // Generic REST action mock.
    Message postedCard(boolean ended, CompletableFuture<Message> editResult) {
        var alert = FreebieMessages.alert(offer, Optional.of(ROLE), reference);
        var data = ended ? FreebieLifecycleMessages.ended(alert.getEmbeds(), alert.getComponents(), reference, false) : null;
        var message = mock(Message.class);
        var author = mock(User.class);
        when(author.getId()).thenReturn("bot");
        when(message.getAuthor()).thenReturn(author);
        when(message.getId()).thenReturn(MESSAGE);
        when(message.getEmbeds()).thenReturn(ended ? data.getEmbeds() : alert.getEmbeds());
        doReturn(ended ? data.getComponents() : alert.getComponents()).when(message).getComponents();
        RestAction<Message> retrieve = mock(RestAction.class, RETURNS_SELF);
        when(retrieve.submit()).thenReturn(CompletableFuture.completedFuture(message));
        when(channel.retrieveMessageById(MESSAGE)).thenReturn(retrieve);
        var edit = mock(MessageEditAction.class, RETURNS_SELF);
        when(edit.submit()).thenReturn(editResult);
        when(message.editMessage(any(MessageEditData.class))).thenReturn(edit);
        return message;
    }

    @Test void editsThePostedCardOnceAndRecordsDone() {
        var job = job(Kind.EDIT, 1, false);
        var result = new CompletableFuture<Message>();
        var message = postedCard(false, result);
        worker.tick(client, shared);
        assertEquals(1, shared.get(), "the edit holds a slot of the shared send budget while in flight");
        while (!work.isEmpty()) work.remove().run();
        var captor = ArgumentCaptor.forClass(MessageEditData.class);
        verify(message).editMessage(captor.capture());
        assertEquals("Powered by GamerPower • ref " + reference, captor.getValue().getEmbeds().getFirst().getFooter().getText());
        assertTrue(captor.getValue().getAllowedMentions().isEmpty());
        result.complete(message);
        while (!work.isEmpty()) work.remove().run();
        verify(store).done(job, MESSAGE, now);
        assertEquals(0, shared.get());
        assertEquals(0, worker.inFlight());
    }

    @Test void anAlreadyEndedCardIsASuccessWithoutAnotherEdit() {
        var job = job(Kind.EDIT, 2, false);
        var message = postedCard(true, new CompletableFuture<>());
        run();
        verify(message, never()).editMessage(any(MessageEditData.class));
        verify(store).done(job, MESSAGE, now);
    }

    @Test void aDeletedMessageEndsAsSkippedNotRetried() {
        var job = job(Kind.EDIT, 1, false);
        postedCard(false, CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.UNKNOWN_MESSAGE, false, 10008)));
        run();
        verify(store).skipped(job, Skip.UNKNOWN_MESSAGE, now);
        verify(store, never()).retry(any(), any(), anyBoolean(), any(), any());
        verify(store, never()).failed(any(), any(), any());
    }

    @Test void transientErrorsRetryWithBackoffThenFail() {
        var job = job(Kind.EDIT, 1, false);
        postedCard(false, CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.SERVER_ERROR, true, 0)));
        run();
        verify(store).retry(eq(job), eq(now.plusSeconds(30)), eq(false), any(), eq(now));
        var last = job(Kind.EDIT, FreebieLifecycle.MAX_ATTEMPTS, false);
        run();
        verify(store).failed(eq(last), any(), eq(now));
    }

    @Test void exhaustedAttemptsFailWithoutTouchingDiscord() {
        var job = job(Kind.EDIT, FreebieLifecycle.MAX_ATTEMPTS + 1, true);
        run();
        verify(store).failed(job, "attempts exhausted", now);
        verify(channel, never()).retrieveMessageById(anyString());
    }

    @Test void missingDestinationsAndAccessAreSkippedAndAnOutageWaits() {
        var job = job(Kind.EDIT, 1, false);
        when(self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        run();
        verify(store).skipped(job, Skip.MISSING_ACCESS, now);

        job = job(Kind.EDIT, 1, false);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(null);
        run();
        verify(store).skipped(job, Skip.UNKNOWN_CHANNEL, now);

        job = job(Kind.EDIT, 1, false);
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(null);
        run();
        verify(store).skipped(job, Skip.BOT_REMOVED, now);

        job = job(Kind.EDIT, 1, false);
        when(client.isUnavailable(Long.parseLong(GUILD))).thenReturn(true);
        run();
        verify(store).release(job, now.plus(Duration.ofMinutes(2)), now);
        verify(channel, never()).retrieveMessageById(anyString());
    }

    @Test void aLostLeaseNeverEditsOrRecords() {
        var job = job(Kind.EDIT, 1, false);
        var message = postedCard(false, new CompletableFuture<>());
        when(store.owns(job, now)).thenReturn(false);
        run();
        verify(message, never()).editMessage(any(MessageEditData.class));
        verify(store, never()).done(any(), any(), any());
        verify(store, never()).retry(any(), any(), anyBoolean(), any(), any());
        assertEquals(0, shared.get());
    }

    // ------------------------------------------------------------------ reminders

    MessageCreateAction sendable(CompletableFuture<Message> result) {
        var action = mock(MessageCreateAction.class, RETURNS_SELF);
        when(action.submit()).thenReturn(result);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        return action;
    }

    @Test void reminderRepliesToTheAlertPingingOnlyTheSectionRole() {
        var job = job(Kind.REMIND, 1, false);
        when(guild.getRoleById(ROLE)).thenReturn(mock(Role.class));
        var sent = mock(Message.class); when(sent.getId()).thenReturn("reminder");
        var action = sendable(CompletableFuture.completedFuture(sent));
        run();
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel).sendMessage(captor.capture());
        assertEquals(Set.of(ROLE), captor.getValue().getMentionedRoles());
        assertTrue(captor.getValue().getAllowedMentions().isEmpty());
        verify(action).setMessageReference(MESSAGE);
        verify(action).failOnInvalidReply(false);
        verify(action).setNonce(FreebieLifecycleMessages.reminderNonce(offer.id(), CHANNEL));
        verify(store).submitting(job, now);
        verify(store).done(job, "reminder", now);
    }

    @Test void aSectionWithoutAPingRoleRemindsQuietly() {
        job(Kind.REMIND, 1, false);
        when(subscriptions.find(GUILD, CHANNEL, offer.store()))
                .thenReturn(Optional.of(new FreebieSubscriptions.Subscription(GUILD, CHANNEL, offer.store(), Optional.empty(), 6)));
        sendable(new CompletableFuture<>());
        run();
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel).sendMessage(captor.capture());
        assertTrue(captor.getValue().getMentionedRoles().isEmpty());
        assertTrue(captor.getValue().getContent().isEmpty());
    }

    @Test void aReminderForAnOfferThatEndedOrWasStoppedIsCancelled() {
        var job = job(Kind.REMIND, 1, false);
        when(repository.offer(offer.id())).thenReturn(Optional.of(FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, false,
                Optional.of(FreebieLifecycleTest.END), false, true)));
        sendable(new CompletableFuture<>());
        run();
        verify(store).cancelled(job, Cancel.OFFER_ENDED, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void theOfferIsCheckedAgainRightBeforePosting() {
        var job = job(Kind.REMIND, 1, false);
        var stopped = FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, false, Optional.of(FreebieLifecycleTest.END), false, true);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer)).thenReturn(Optional.of(stopped));
        sendable(new CompletableFuture<>());
        run();
        verify(store).cancelled(job, Cancel.OFFER_ENDED, now);
        verify(store, never()).submitting(any(), any());
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void turningRemindersOffCancelsAQueuedOne() {
        var job = job(Kind.REMIND, 1, false);
        when(subscriptions.find(GUILD, CHANNEL, offer.store()))
                .thenReturn(Optional.of(new FreebieSubscriptions.Subscription(GUILD, CHANNEL, offer.store(), Optional.empty(), 0)));
        run();
        verify(store).cancelled(job, Cancel.SETTING_OFF, now);
    }

    @Test void anUncertainReminderChecksHistoryAndNeverPostsTwice() {
        var job = job(Kind.REMIND, 2, true);
        var history = mock(MessageHistory.class);
        @SuppressWarnings("unchecked") RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        var earlier = mock(Message.class); var author = mock(User.class);
        when(author.getId()).thenReturn("bot"); when(earlier.getAuthor()).thenReturn(author); when(earlier.getId()).thenReturn("earlier");
        when(earlier.getEmbeds()).thenReturn(FreebieLifecycleMessages.reminder(offer, CHANNEL, Optional.empty(), now).getEmbeds());
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(50)).thenReturn(past);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(List.of(earlier)));
        sendable(new CompletableFuture<>());
        run();
        verify(store).done(job, "earlier", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));

        var blind = job(Kind.REMIND, 2, true);
        when(self.hasPermission(channel, Permission.MESSAGE_HISTORY)).thenReturn(false);
        run();
        verify(store).skipped(blind, Skip.HISTORY_UNAVAILABLE, now);
    }

    @Test void aTransientReminderFailureIsRetriedAsUncertain() {
        var job = job(Kind.REMIND, 1, false);
        sendable(CompletableFuture.failedFuture(new TimeoutException()));
        run();
        verify(store).retry(eq(job), eq(now.plusSeconds(30)), eq(true), any(), eq(now));
    }

    // ------------------------------------------------------------------ budget

    @Test void neverHoldsMoreThanHalfTheSendBudgetOrAnyFullSlot() {
        when(store.claim(any(), any())).thenAnswer(i -> Optional.of(new Job("edit|x", Kind.EDIT, offer.id(), offer.id() + "|" + CHANNEL,
                GUILD, CHANNEL, MESSAGE, false, 0, 1, false, UUID.randomUUID().toString())));
        postedCard(false, new CompletableFuture<>());
        worker.tick(client, shared);
        assertEquals(FreebieLifecycleWorker.MAX_IN_FLIGHT, worker.inFlight());
        assertEquals(FreebieSender.MAX_IN_FLIGHT / 2, shared.get());
        verify(store, times(FreebieLifecycleWorker.MAX_IN_FLIGHT)).claim(any(), any());

        var other = new FreebieLifecycleWorker(repository, subscriptions, work::add, Clock.fixed(now, ZoneOffset.UTC));
        var busy = new AtomicInteger(FreebieSender.MAX_IN_FLIGHT - 1);
        other.tick(client, busy);
        assertEquals(1, other.inFlight(), "only the slot alerts left free");
    }
}
