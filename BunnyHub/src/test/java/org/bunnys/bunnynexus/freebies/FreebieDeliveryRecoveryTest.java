package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
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

class FreebieDeliveryRecoveryTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieDeliveryStore store = mock(FreebieDeliveryStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember member = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
    final CompletableFuture<Message> send = new CompletableFuture<>();
    final FreebieOffer offer = FreebieUnitTest.offer("X", FreebieStore.EPIC);
    FreebieSender sender;
    FreebieRepository.Claimed job;
    @BeforeEach void setup() {
        when(repository.deliveries()).thenReturn(store);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        when(client.getGuildById("100000000000000000")).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, "200000000000000000")).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(member);
        when(member.hasPermission(org.mockito.ArgumentMatchers.eq(channel), any(Permission[].class))).thenReturn(true);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(action);
        when(action.submit()).thenReturn(send);
        when(store.owns(any(), any())).thenReturn(true);
        when(store.submitting(any(), any())).thenReturn(true);
        doAnswer(i -> { work.add(i.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        sender = new FreebieSender(repository, () -> client, executor, clock, mock(FreebieNotifier.class));
        claim(true);
    }
    void claim(boolean uncertain) {
        var base = FreebieSenderTest.job();
        job = new FreebieRepository.Claimed(base.id(), base.offerId(), base.guildId(), base.channelId(), base.roleId(), 2, uncertain, "token");
        when(repository.claim(any(), any())).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
    }
    void drain() { while (!work.isEmpty()) work.remove().run(); }
    @SuppressWarnings("unchecked") // Generic REST action mock.
    CompletableFuture<List<Message>> history() {
        var history = mock(MessageHistory.class);
        RestAction<List<Message>> request = mock(RestAction.class, RETURNS_SELF);
        var result = new CompletableFuture<List<Message>>();
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(50)).thenReturn(request);
        when(request.submit()).thenReturn(result);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot"); when(client.getSelfUser()).thenReturn(user);
        return result;
    }
    @Test void missingHistoryPermissionBlocksWithoutBlindResend() {
        when(member.hasPermission(channel, Permission.MESSAGE_HISTORY)).thenReturn(false);
        sender.tick(); drain();
        verify(store).block(job, FreebieDeliveryStore.Block.HISTORY_PERMISSION, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        assertEquals(0, sender.inFlight());
    }
    @Test void inaccessibleHistoryNeverBecomesDefiniteFailureOrResend() {
        var history = history(); sender.tick();
        history.completeExceptionally(new IllegalStateException("private failure"));
        verify(store, never()).block(any(), any(), any());
        drain();
        verify(store).block(job, FreebieDeliveryStore.Block.HISTORY_FAILED, now);
        verify(repository, never()).markFailed(any(), any(), any(), any());
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void synchronousHistoryFailureAlsoBlocks() {
        when(channel.getHistory()).thenThrow(new IllegalStateException("missing history"));
        sender.tick(); drain();
        verify(store).block(job, FreebieDeliveryStore.Block.HISTORY_FAILED, now);
    }
    @Test void uncertainAcceptanceKeepsRecoveryOnWorker() {
        claim(false); sender.tick(); drain();
        verify(store).submitting(job, now);
        send.completeExceptionally(new TimeoutException());
        verify(store, never()).block(any(), any(), any());
        drain();
        verify(store).block(job, FreebieDeliveryStore.Block.UNCERTAIN, now);
        verify(repository, never()).markFailed(any(), any(), any(), any());
        assertEquals(0, sender.inFlight());
    }
    @Test void acceptanceRecordingFailureLeavesDurableUncertainty() {
        claim(false); sender.tick(); drain();
        var message = mock(Message.class); when(message.getId()).thenReturn("accepted");
        when(repository.markSent(job, "accepted", now)).thenThrow(new IllegalStateException("database down"));
        send.complete(message); drain();
        verify(store).submitting(job, now);
        verify(repository).markSent(job, "accepted", now);
        verify(repository, never()).markFailed(any(), any(), any(), any());
        assertEquals(0, sender.inFlight());
    }
    @Test void historyMatchRecordsAcceptanceWithoutResending() {
        var future = history(); sender.tick();
        var message = mock(Message.class); var user = mock(User.class);
        when(user.getId()).thenReturn("bot"); when(message.getAuthor()).thenReturn(user);
        when(message.getId()).thenReturn("found");
        when(message.getEmbeds()).thenReturn(FreebieMessages.alert(offer, Optional.empty(), FreebieMessages.reference(job.id())).getEmbeds());
        future.complete(List.of(message)); drain();
        verify(repository).markSent(job, "found", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void staleLeaseCannotSubmitAfterHistoryCompletes() {
        var future = history(); sender.tick(); when(store.owns(any(), any())).thenReturn(false);
        future.complete(List.of()); drain();
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void stopBetweenHistoryReadAndResendCancels() {
        var future = history(); sender.tick(); when(repository.offer(offer.id())).thenReturn(Optional.empty());
        future.complete(List.of()); drain();
        verify(repository).markCancelled(job, "offer no longer eligible", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void secondEligibilityCheckCatchesStopAfterSubmissionMarker() {
        claim(false);
        when(store.submitting(any(), any())).thenAnswer(i -> {
            when(repository.offer(offer.id())).thenReturn(Optional.empty()); return true;
        });
        sender.tick(); drain();
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void recoveredResendUsesOriginalNonce() {
        var future = history(); sender.tick(); future.complete(List.of()); drain();
        verify(action).setNonce(FreebieMessages.nonce(job.id()));
        verify(store).submitting(job, now);
    }
    @Test void exhaustedBudgetKeepsUncertaintyBlocked() {
        var future = history(); when(store.submitting(any(), any())).thenReturn(false);
        sender.tick(); future.complete(List.of()); drain();
        verify(store).block(job, FreebieDeliveryStore.Block.EXHAUSTED, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void rejectedCallbackDispatchReleasesLocalSlotWithoutDatabaseWork() {
        claim(false); sender.tick(); drain();
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        send.completeExceptionally(new TimeoutException());
        assertEquals(0, sender.inFlight());
        verify(store, never()).block(any(), any(), any());
    }
    @Test void stopDuringDestinationRevalidationIsCheckedAgainBeforeSending() {
        claim(false);
        var subscriptions = mock(FreebieSubscriptions.class); sender.withSubscriptions(subscriptions);
        when(store.get(job.id())).thenReturn(Optional.of(new org.bson.Document("manualRetries", 1)
                .append("guildId", job.guildId()).append("channelId", job.channelId())));
        when(subscriptions.forGuild(job.guildId())).thenAnswer(i -> {
            when(repository.offer(offer.id())).thenReturn(Optional.empty());
            return List.of(new FreebieSubscriptions.Subscription(job.guildId(), job.channelId(), offer.store(), Optional.empty()));
        });
        sender.tick(); drain();
        verify(repository).markCancelled(job, "offer no longer eligible", now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }
    @Test void permissionRevokedDuringHistoryReadPreventsResend() {
        var future = history(); sender.tick();
        when(member.hasPermission(channel, Permission.MESSAGE_HISTORY)).thenReturn(false);
        future.complete(List.of()); drain();
        verify(store).block(job, FreebieDeliveryStore.Block.DESTINATION, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }}



