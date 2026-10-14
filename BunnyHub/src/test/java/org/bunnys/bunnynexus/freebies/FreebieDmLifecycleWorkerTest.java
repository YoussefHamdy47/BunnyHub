package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.bunnynexus.freebies.FreebieLifecycleStore.Job;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieDmLifecycleWorkerTest {
    private final Instant now = Instant.parse("2026-10-13T00:00:00Z");
    private final FreebieRepository repository = mock(FreebieRepository.class);
    private final FreebieLifecycleStore jobs = mock(FreebieLifecycleStore.class);
    private final JDA client = mock(JDA.class);
    private final PrivateChannel channel = mock(PrivateChannel.class);
    private final Message message = mock(Message.class);
    private final User author = mock(User.class);
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final AtomicInteger shared = new AtomicInteger(), background = new AtomicInteger();
    private final String dmId = "gamerpower:1|400000000000000001";
    private final String reference = FreebieDm.reference(dmId);
    private final Job job = new Job("edit|dm|" + dmId, FreebieLifecycle.Kind.EDIT, "gamerpower:1", "dm|" + dmId,
            null, "500000000000000001", "600000000000000001", true, 0, 1, false, "token", "400000000000000001");
    private FreebieLifecycleWorker worker;

    @BeforeEach void setup() {
        when(repository.lifecycle()).thenReturn(jobs);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        when(jobs.owns(job, now)).thenReturn(true);
        var self = mock(SelfUser.class);
        when(self.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(self);
        doReturn(action(CompletableFuture.completedFuture(channel))).when(client).openPrivateChannelById(job.recipientId());
        when(channel.getId()).thenReturn(job.channelId());
        doReturn(action(CompletableFuture.completedFuture(message))).when(channel).retrieveMessageById(job.messageId());
        when(author.getId()).thenReturn("bot");
        when(message.getAuthor()).thenReturn(author);
        when(message.getId()).thenReturn(job.messageId());
        var card = FreebieDm.card(FreebieUnitTest.offer("Game", FreebieStore.EPIC), dmId);
        when(message.getEmbeds()).thenReturn(card.getEmbeds());
        doReturn(card.getComponents()).when(message).getComponents();
        var edit = mock(MessageEditAction.class, RETURNS_SELF);
        when(edit.submit()).thenReturn(CompletableFuture.completedFuture(message));
        when(message.editMessage(any(MessageEditData.class))).thenReturn(edit);
        worker = new FreebieLifecycleWorker(repository, mock(FreebieSubscriptions.class), tasks::add, Clock.fixed(now, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private static <T> CacheRestAction<T> action(CompletableFuture<T> result) {
        CacheRestAction<T> action = mock(CacheRestAction.class, RETURNS_SELF);
        when(action.submit()).thenReturn(result);
        return action;
    }
    private void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    private void run() { worker.tick(client, shared, background); drain(); }

    @Test void editsSavedDmAndKeepsUnsubscribeAndExactReference() {
        worker.tick(client, shared, background);
        assertEquals(1, background.get());
        assertEquals(1, shared.get());
        drain();
        var edit = ArgumentCaptor.forClass(MessageEditData.class);
        verify(message).editMessage(edit.capture());
        assertTrue(edit.getValue().getEmbeds().getFirst().getDescription().contains("withdrawn"));
        assertEquals(message.getEmbeds().getFirst().getFooter().getText(), edit.getValue().getEmbeds().getFirst().getFooter().getText());
        var row = (net.dv8tion.jda.api.components.actionrow.ActionRow) edit.getValue().getComponents().getFirst();
        assertTrue(row.getButtons().stream().anyMatch(b -> FreebieDm.UNSUBSCRIBE_PREFIX.equals(b.getCustomId())));
        assertTrue(row.getButtons().stream().noneMatch(b -> b.getLabel().startsWith("Claim")));
        assertTrue(edit.getValue().getAllowedMentions().isEmpty());
        verify(jobs).done(job, job.messageId(), now);
        assertEquals(0, shared.get());
        assertEquals(0, background.get());
        verify(client, never()).getGuildById(anyLong());
    }

    @Test void refusesAnotherAuthorsMessage() {
        when(author.getId()).thenReturn("someone-else");
        run();
        verify(jobs).skipped(job, FreebieLifecycle.Skip.NOT_OURS, now);
        verify(message, never()).editMessage(any(MessageEditData.class));
    }

    @Test void refusesWrongReferenceEvenForBotAuthoredMessage() {
        when(message.getEmbeds()).thenReturn(FreebieDm.card(FreebieUnitTest.offer("Game", FreebieStore.EPIC), "different").getEmbeds());
        run();
        verify(jobs).skipped(job, FreebieLifecycle.Skip.NOT_OURS, now);
        verify(message, never()).editMessage(any(MessageEditData.class));
    }

    @Test void neverSwitchesTheSavedDmChannel() {
        when(channel.getId()).thenReturn("different");
        run();
        verify(channel, never()).retrieveMessageById(anyString());
        verify(jobs).skipped(job, FreebieLifecycle.Skip.UNKNOWN_CHANNEL, now);
    }

    @Test void lostLeaseAndShutdownPreventEdits() {
        when(jobs.owns(job, now)).thenReturn(false);
        run();
        verify(message, never()).editMessage(any(MessageEditData.class));
        verify(jobs, never()).done(any(), any(), any());
        assertEquals(0, shared.get());
        when(jobs.claim(any(), any())).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        when(jobs.owns(job, now)).thenReturn(true);
        worker.tick(client, shared, background);
        worker.close();
        drain();
        verify(message, never()).editMessage(any(MessageEditData.class));
        assertEquals(0, background.get());
    }

    @Test void alreadyEndedDmCompletesWithoutAnotherEdit() {
        var data = FreebieLifecycleMessages.ended(message.getEmbeds(), message.getComponents(), reference, true);
        when(message.getEmbeds()).thenReturn(data.getEmbeds());
        doReturn(data.getComponents()).when(message).getComponents();
        run();
        verify(jobs).done(job, job.messageId(), now);
        verify(message, never()).editMessage(any(MessageEditData.class));
    }

    @Test void missingMessageAndUnreachableUserAreSkipped() {
        doReturn(action(CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.UNKNOWN_MESSAGE, false, 10008))))
                .when(channel).retrieveMessageById(job.messageId());
        run();
        verify(jobs).skipped(job, FreebieLifecycle.Skip.UNKNOWN_MESSAGE, now);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        doReturn(action(CompletableFuture.failedFuture(FreebieLifecycleTest.discord(ErrorResponse.CANNOT_SEND_TO_USER, false, 50007))))
                .when(client).openPrivateChannelById(job.recipientId());
        run();
        verify(jobs).skipped(job, FreebieLifecycle.Skip.MISSING_ACCESS, now);
        verify(jobs, never()).retry(any(), any(), anyBoolean(), any(), any());
    }

    @Test void transientReadFailureRetriesWithoutBlindEdit() {
        doReturn(action(CompletableFuture.failedFuture(new TimeoutException()))).when(channel).retrieveMessageById(job.messageId());
        run();
        verify(jobs).retry(eq(job), eq(now.plusSeconds(30)), eq(false), anyString(), eq(now));
        verify(message, never()).editMessage(any(MessageEditData.class));
        assertEquals(0, background.get());
    }

    @Test void fullBackgroundBudgetDoesNotAdmitDmEdits() {
        background.set(FreebieSender.BACKGROUND_SLOTS);
        run();
        verify(jobs, never()).claim(any(), any());
        verify(client, never()).openPrivateChannelById(anyString());
    }
}
