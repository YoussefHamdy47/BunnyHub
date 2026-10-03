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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FreebieReviewRecoveryTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final String channelId = "100000000000000099";
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieReviewStore store = mock(FreebieReviewStore.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final JDA client = mock(JDA.class);
    final GuildMessageChannel channel = mock(GuildMessageChannel.class);
    final FreebieOffer offer = FreebieUnitTest.offer("X", FreebieStore.EPIC);
    final FreebieReviewStore.Claim job = new FreebieReviewStore.Claim(offer, "token", true, channelId);
    FreebieReviewChannel reviews;
    RestAction<List<Message>> history;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(repository.reviews()).thenReturn(store);
        when(store.destination(any(), anyString(), any())).thenReturn(true);
        when(store.owns(any(), any())).thenReturn(true);
        when(subscriptions.audience(any())).thenReturn(new FreebieSubscriptions.Audience(2, 1));
        when(client.getChannelById(GuildMessageChannel.class, channelId)).thenReturn(channel);
        when(channel.canTalk()).thenReturn(true);
        var past = mock(MessageHistory.class); history = mock(RestAction.class, RETURNS_SELF);
        when(channel.getHistory()).thenReturn(past); when(past.retrievePast(50)).thenReturn(history);
        when(history.submit()).thenReturn(CompletableFuture.completedFuture(List.of()));
        var self = mock(SelfUser.class); when(self.getId()).thenReturn("bot"); when(client.getSelfUser()).thenReturn(self);
        reviews = new FreebieReviewChannel(new FreebieConfig(channelId, Set.of("333644367539470337"), 10),
                repository, subscriptions, () -> client, Runnable::run, Clock.fixed(now, ZoneOffset.UTC));
    }

    Message existing(String authorId) {
        var message = mock(Message.class); var author = mock(User.class);
        when(author.getId()).thenReturn(authorId); when(message.getAuthor()).thenReturn(author); when(message.getId()).thenReturn("existing");
        when(message.getEmbeds()).thenReturn(List.of(FreebieMessages.reviewEmbed(offer, 2, 1, "Waiting")));
        return message;
    }

    @Test void crashAfterAcceptanceRecoversReviewWithoutAnotherOwnerPing() {
        var messages = List.of(existing("other"), existing("bot"));
        when(history.submit()).thenReturn(CompletableFuture.completedFuture(messages));
        reviews.post(client, job);
        verify(store).sent(job, channelId, "existing", now); verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void crashBeforeSendUsesSameNonceAfterEmptyHistory() {
        var action = mock(MessageCreateAction.class, RETURNS_SELF);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        var message = existing("bot");
        when(action.submit()).thenReturn(CompletableFuture.completedFuture(message));
        reviews.post(client, job);
        verify(action).setNonce(FreebieMessages.nonce("review|" + offer.id()));
        verify(store).sent(job, channelId, "existing", now);
    }

    @Test void failedHistoryCheckDoesNotResend() {
        when(history.submit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("history unavailable")));
        reviews.post(client, job);
        verify(channel, never()).sendMessage(any(MessageCreateData.class)); verify(store, never()).sent(any(), anyString(), anyString(), any());
    }

    @Test void staleWorkerCannotSendAfterItsHistoryCheck() {
        when(store.owns(job, now)).thenReturn(false); reviews.post(client, job);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void pinnedUnavailableChannelCannotFallBackToAnotherDestination() {
        when(channel.canTalk()).thenReturn(false); reviews.post(client, job);
        verifyNoInteractions(store); verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void failedSendDoesNotRecordReviewMessage() {
        var action = mock(MessageCreateAction.class, RETURNS_SELF);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        when(action.submit()).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));
        reviews.post(client, job); verify(store, never()).sent(any(), anyString(), anyString(), any());
    }

    @Test void olderSignedReviewFooterIsRecognized() {
        var message = existing("bot");
        when(message.getEmbeds()).thenReturn(List.of(new net.dv8tion.jda.api.EmbedBuilder().setDescription("legacy")
                .setFooter("Offer " + offer.id() + " • " + org.bunnys.utils.Embeds.FOOTER).build()));
        when(history.submit()).thenReturn(CompletableFuture.completedFuture(List.of(message)));
        reviews.post(client, job); verify(store).sent(job, channelId, "existing", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
}
