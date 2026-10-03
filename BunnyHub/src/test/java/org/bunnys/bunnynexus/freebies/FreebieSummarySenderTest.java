package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreebieSummarySenderTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieSummaryStore store = mock(FreebieSummaryStore.class);
    final JDA client = mock(JDA.class);
    final GuildMessageChannel channel = mock(GuildMessageChannel.class);
    final MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
    final FreebieReviewChannel reviews = mock(FreebieReviewChannel.class);
    final FreebieOffer offer = FreebieUnitTest.offer("X", FreebieStore.EPIC);
    final Queue<Runnable> tasks = new ArrayDeque<>();
    final CompletableFuture<Message> sent = new CompletableFuture<>();
    FreebieSummaryStore.Claim claim;
    FreebieSummarySender sender;

    @BeforeEach void setup() {
        when(repository.summaries()).thenReturn(store);
        when(repository.counts(offer.id())).thenReturn(new FreebieRepository.Counts(2, 0, 0, 0));
        when(repository.failures(offer.id(), 40)).thenReturn(List.of());
        when(client.getChannelById(GuildMessageChannel.class, "100000000000000099")).thenReturn(channel);
        when(channel.canTalk()).thenReturn(true);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        when(action.submit()).thenReturn(sent);
        when(store.owns(any(), any())).thenReturn(true);
        sender = new FreebieSummarySender(repository,
                new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10),
                () -> client, tasks::add, Clock.fixed(now, ZoneOffset.UTC), reviews);
        claim(false);
    }

    void claim(boolean verify) {
        claim = new FreebieSummaryStore.Claim(offer, "100000000000000099", "token", verify);
        when(store.claim(eq(offer.id()), anyString(), eq(now), any())).thenReturn(Optional.of(claim));
    }
    void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    void begin() { sender.submit(offer); drain(); }

    @Test void claimAndOutcomeRunOnlyOnExecutorAfterDiscordAcceptance() {
        sender.submit(offer);
        verifyNoInteractions(repository);
        drain();
        verify(store, never()).sent(any(), anyString(), any());
        var message = mock(Message.class);
        when(message.getId()).thenReturn("message");
        sent.complete(message); // Simulated JDA callback, outside the worker.
        verify(store, never()).sent(any(), anyString(), any());
        drain();
        verify(store).sent(claim, "message", now);
        verify(action).setNonce(FreebieMessages.nonce("summary|" + offer.id()));
    }

    @Test void failedSendRetriesWithoutRecordingSuccess() {
        begin();
        sent.completeExceptionally(new TimeoutException("uncertain"));
        verify(store, never()).retry(any(), any());
        drain();
        verify(store).retry(claim, now);
        verify(store, never()).sent(any(), anyString(), any());
    }

    @Test void unavailableChannelRetriesWithoutSending() {
        when(channel.canTalk()).thenReturn(false);
        begin();
        verify(store).retry(claim, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void missingChannelRetriesWithoutRecordingSuccess() {
        when(client.getChannelById(GuildMessageChannel.class, claim.channelId())).thenReturn(null);
        begin();
        verify(store).retry(claim, now);
        verify(store, never()).sent(any(), anyString(), any());
    }

    @Test void recordingFailureLeavesLeaseForHistoryRecovery() {
        begin();
        var message = mock(Message.class);
        when(message.getId()).thenReturn("accepted");
        when(store.sent(claim, "accepted", now)).thenThrow(new IllegalStateException("database unavailable"));
        sent.complete(message);
        drain();
        verify(store).sent(claim, "accepted", now);
        verify(store, never()).retry(any(), any());
    }

    @SuppressWarnings("unchecked") // Mockito cannot retain RestAction's generic List<Message> type.
    CompletableFuture<List<Message>> history() {
        claim(true);
        var history = mock(MessageHistory.class);
        RestAction<List<Message>> retrieve = mock(RestAction.class, RETURNS_SELF);
        var future = new CompletableFuture<List<Message>>();
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(50)).thenReturn(retrieve);
        when(retrieve.submit()).thenReturn(future);
        var self = mock(SelfUser.class);
        when(self.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(self);
        return future;
    }

    Message historical(String author, String id, boolean reference) {
        var message = mock(Message.class);
        var user = mock(User.class);
        when(user.getId()).thenReturn(author);
        when(message.getAuthor()).thenReturn(user);
        when(message.getId()).thenReturn(id);
        when(message.getEmbeds()).thenReturn(reference ? FreebieMessages.summary(offer,
                new FreebieRepository.Counts(2, 0, 0, 0), List.of(), Map.of()).getEmbeds() : List.of());
        return message;
    }

    @Test void crashAfterAcceptanceRecoversFromHistoryWithoutResending() {
        var history = history();
        begin();
        history.complete(List.of(historical("other", "forged", true), historical("bot", "existing", true)));
        drain();
        verify(store).sent(claim, "existing", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void crashBeforeSendingRetriesAfterHistoryCheckWithSameNonce() {
        var history = history();
        begin();
        history.complete(List.of(historical("other", "forged", true), historical("bot", "unrelated", false)));
        drain();
        verify(channel).sendMessage(any(MessageCreateData.class));
        verify(action).setNonce(FreebieMessages.nonce("summary|" + offer.id()));
    }

    @Test void failedHistoryReadDoesNotBlindlyResend() {
        var history = history();
        begin();
        history.completeExceptionally(new IllegalStateException("history permission missing"));
        drain();
        verify(store).retry(claim, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void expiredWorkerDoesNotSendAfterHistoryCallback() {
        var history = history();
        begin();
        when(store.owns(claim, now)).thenReturn(false);
        history.complete(List.of());
        drain();
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void outstandingDeliveriesAndLostClaimsDoNotSend() {
        when(repository.counts(offer.id())).thenReturn(new FreebieRepository.Counts(0, 0, 0, 1));
        begin();
        verifyNoInteractions(store);
        when(repository.counts(offer.id())).thenReturn(new FreebieRepository.Counts(0, 0, 0, 0));
        when(store.claim(anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        begin();
        verifyNoInteractions(channel);
    }
}
