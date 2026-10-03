package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.*;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FreebieNotifierTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieNoticeStore store = mock(FreebieNoticeStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final PrivateChannel dm = mock(PrivateChannel.class);
    final MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
    final CompletableFuture<Message> response = new CompletableFuture<>();
    final Queue<Runnable> tasks = new ArrayDeque<>();
    final FreebieNotifier notifier = new FreebieNotifier(repository, () -> client, Clock.fixed(now, ZoneOffset.UTC), tasks::add);
    FreebieNoticeStore.Claim job;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(repository.notices()).thenReturn(store);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getGuildById("guild")).thenReturn(guild);
        when(guild.getOwnerId()).thenReturn("owner"); when(guild.getName()).thenReturn("Server");
        when(repository.offer("gamerpower:1")).thenReturn(Optional.of(FreebieUnitTest.offer("X", FreebieStore.EPIC)));
        net.dv8tion.jda.api.requests.restaction.CacheRestAction<PrivateChannel> open = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, RETURNS_SELF);
        when(client.openPrivateChannelById("owner")).thenReturn(open);
        when(open.submit()).thenReturn(CompletableFuture.completedFuture(dm));
        when(dm.getId()).thenReturn("dm"); when(dm.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        when(action.submit()).thenReturn(response);
        when(store.destination(any(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(store.owns(any(), any())).thenReturn(true);
        claim(false);
    }
    void claim(boolean verify) {
        job = new FreebieNoticeStore.Claim("guild", "delivery", "gamerpower:1", "channel", FreebieFailure.MISSING_PERMISSIONS,
                "token", verify ? "DM" : null, verify ? "dm" : null, verify ? "owner" : null, verify);
        when(store.claim(now)).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
    }
    void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    void begin() { notifier.tick(); drain(); }

    @Test void successIsRecordedOnlyOnWorkerAfterAcceptance() {
        begin(); verify(store, never()).sent(any(), anyString(), any());
        var message = mock(Message.class); when(message.getId()).thenReturn("accepted");
        response.complete(message); verify(store, never()).sent(any(), anyString(), any());
        drain(); verify(store).sent(job, "accepted", now);
        verify(action).setNonce(FreebieMessages.nonce("notice|delivery"));
    }

    @Test void uncertainDmOutcomeRetriesSameDestinationWithoutFallback() {
        begin(); response.completeExceptionally(new TimeoutException()); drain();
        verify(store).retry(job, now); verify(store, never()).fallback(any(), any());
        verify(store, never()).sent(any(), anyString(), any());
    }

    @Test void onlyDefiniteDmRejectionAllowsFallback() {
        begin(); var rejected = mock(ErrorResponseException.class);
        when(rejected.getErrorResponse()).thenReturn(ErrorResponse.CANNOT_SEND_TO_USER);
        when(rejected.getStackTrace()).thenReturn(new StackTraceElement[0]);
        response.completeExceptionally(rejected); drain();
        verify(store).fallback(job, now); verify(store).retry(job, now);
    }

    @SuppressWarnings("unchecked") CompletableFuture<List<Message>> history() {
        claim(true); var history = mock(MessageHistory.class);
        RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        var result = new CompletableFuture<List<Message>>();
        when(dm.getHistory()).thenReturn(history); when(history.retrievePast(50)).thenReturn(past); when(past.submit()).thenReturn(result);
        var self = mock(SelfUser.class); when(self.getId()).thenReturn("bot"); when(client.getSelfUser()).thenReturn(self);
        return result;
    }

    @Test void restartFindsAcceptedNoticeWithoutAnotherSend() {
        var history = history(); begin();
        var message = mock(Message.class); var author = mock(User.class);
        when(author.getId()).thenReturn("bot"); when(message.getAuthor()).thenReturn(author); when(message.getId()).thenReturn("existing");
        when(message.getEmbeds()).thenReturn(List.of(new net.dv8tion.jda.api.EmbedBuilder().setDescription("notice")
                .setFooter(FreebieMessages.noticeReference("delivery")).build()));
        history.complete(List.of(message)); drain();
        verify(store).sent(job, "existing", now); verify(dm, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void inaccessibleHistoryDoesNotResendOrSwitchChannels() {
        var history = history(); begin(); history.completeExceptionally(new IllegalStateException("history inaccessible")); drain();
        verify(store).retry(job, now); verify(store, never()).fallback(any(), any());
        verify(dm, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void expiredLeaseAfterHistoryPreventsSending() {
        var history = history(); begin(); when(store.owns(job, now)).thenReturn(false);
        history.complete(List.of()); drain(); verify(dm, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void databaseFailureAfterAcceptanceLeavesLeaseRecoverable() {
        begin(); var message = mock(Message.class); when(message.getId()).thenReturn("accepted");
        when(store.sent(job, "accepted", now)).thenThrow(new IllegalStateException("database offline"));
        response.complete(message); drain(); verify(store).sent(job, "accepted", now); verify(store, never()).retry(any(), any());
    }

    @Test void shutdownAdmitsNoFurtherWork() {
        notifier.close(); notifier.tick(); verifyNoInteractions(store);
    }

    @Test void persistedFallbackUsesSystemChannelWithoutReopeningDm() {
        job = new FreebieNoticeStore.Claim("guild", "delivery", "gamerpower:1", "channel", FreebieFailure.MISSING_PERMISSIONS,
                "token", "SYSTEM", null, null, false);
        when(store.claim(now)).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        var fallback = mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class);
        when(guild.getSystemChannel()).thenReturn(fallback); when(fallback.getId()).thenReturn("system");
        when(fallback.canTalk()).thenReturn(true); when(fallback.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        begin(); verify(fallback).sendMessage(any(MessageCreateData.class)); verify(client, never()).openPrivateChannelById(anyString());
    }

    @Test void synchronousFailuresHaveBoundedAdmissionPerTick() {
        when(client.getGuildById("guild")).thenReturn(null);
        when(store.claim(now)).thenReturn(Optional.of(job));
        notifier.tick(); verify(store, times(2)).claim(now); verify(store, times(2)).retry(job, now);
    }
}
