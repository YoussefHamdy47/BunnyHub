package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Owner announcement sends against mocked Discord and storage: no pings, fixed nonce, no blind resend. */
class FreebieBroadcastWorkerTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", MESSAGE = "900000000000000000", ID = "bc1";
    final Instant now = Instant.parse("2026-10-28T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieBroadcastStore store = mock(FreebieBroadcastStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
    final Queue<Runnable> work = new ArrayDeque<>();
    final FreebieBroadcastStore.Job job = new FreebieBroadcastStore.Job(ID + "|" + GUILD, ID, GUILD, CHANNEL, 1, false, "t");

    @BeforeEach void setup() {
        when(repository.broadcasts()).thenReturn(store);
        when(store.get(ID)).thenReturn(Optional.of(new FreebieBroadcastStore.Broadcast(ID, "Maintenance", "Line one\nLine two @everyone", "SENDING", "owner")));
        when(store.submitting(any(), any())).thenReturn(true);
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(send);
    }

    @Test void sendsTheSavedImageAndKeepsTheRecoveryReference() {
        String image = "https://example.com/banner.png";
        when(store.get(ID)).thenReturn(Optional.of(new FreebieBroadcastStore.Broadcast(
                ID, "Deals", "Look here", "SENDING", "owner", false, null, false, image)));
        var message = mock(Message.class);
        when(message.getId()).thenReturn(MESSAGE);
        when(send.submit()).thenReturn(CompletableFuture.completedFuture(message));
        run(job);
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel).sendMessage(captor.capture());
        assertTrue(captor.getValue().toData().toString().contains(image));
        assertTrue(FreebieBroadcastWorker.carries(V2.posted(captor.getValue().getComponents()),
                FreebieBroadcastWorker.reference(job.id())));
        assertTrue(captor.getValue().getAllowedMentions().isEmpty());
        verify(store).sent(job, MESSAGE, now);
    }

    @Test void storedImagesAreOptionalForOlderBroadcasts() {
        var old = new org.bson.Document("_id", ID).append("title", "Deals").append("body", "Text")
                .append("state", "DRAFT").append("actor", "owner");
        assertNull(FreebieBroadcastStore.Broadcast.read(old).imageUrl());
        old.append("imageUrl", "https://example.com/banner.png");
        assertEquals("https://example.com/banner.png", FreebieBroadcastStore.Broadcast.read(old).imageUrl());
    }

    @Test void imageUrlsMustBeAbsoluteHttpsWithoutCredentials() {
        assertTrue(FreebieBroadcasts.validImageUrl("https://example.com/banner.png?size=large"));
        for (String invalid : List.of("file:///tmp/a.png", "http://example.com/a.png", "https:///a",
                "https://user:password@example.com/a.png", "not a url", "https://example.com/" + "a".repeat(2048)))
            assertFalse(FreebieBroadcasts.validImageUrl(invalid), invalid);
    }
    boolean run(FreebieBroadcastStore.Job job) {
        boolean async = new FreebieBroadcastWorker(repository, work::add, Clock.fixed(now, ZoneOffset.UTC)).start(client, job, () -> {});
        while (!work.isEmpty()) work.remove().run();
        return async;
    }

    @Test void sendsOneMessageWithAFixedNonceAndNoMentions() {
        var message = mock(Message.class);
        when(message.getId()).thenReturn(MESSAGE);
        when(send.submit()).thenReturn(CompletableFuture.completedFuture(message));
        run(job);
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel).sendMessage(captor.capture());
        assertTrue(captor.getValue().getAllowedMentions().isEmpty(), "@everyone in the text never pings");
        assertTrue(captor.getValue().getMentionedRoles().isEmpty());
        assertTrue(V2.text(captor.getValue().getComponents()).endsWith("-# " + FreebieBroadcastWorker.reference(job.id())));
        verify(send).setNonce(FreebieBroadcastWorker.nonce(job.id()));
        verify(store).submitting(job, now);
        verify(store).sent(job, MESSAGE, now);
    }

    @SuppressWarnings("unchecked") // Generic REST action mock.
    @Test void anUncertainEarlierAttemptFoundInHistoryIsNotResent() {
        var uncertain = new FreebieBroadcastStore.Job(job.id(), ID, GUILD, CHANNEL, 2, true, "t");
        var posted = mock(Message.class);
        var author = mock(User.class);
        when(author.getId()).thenReturn("bot");
        when(posted.getAuthor()).thenReturn(author);
        when(posted.getId()).thenReturn(MESSAGE);
        var top = FreebieBroadcastWorker.message("Maintenance", "x", FreebieBroadcastWorker.reference(job.id())).getComponents();
        when(posted.isUsingComponentsV2()).thenReturn(true);
        when(posted.getComponentTree()).thenReturn(net.dv8tion.jda.api.components.tree.MessageComponentTree.of(top));
        var history = mock(MessageHistory.class);
        RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(List.of(posted)));
        when(history.retrievePast(50)).thenReturn(past);
        when(channel.getHistory()).thenReturn(history);
        run(uncertain);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        verify(store).sent(uncertain, MESSAGE, now);
    }

    @Test void missingAccessOrHistoryPermissionSkipsInsteadOfGuessing() {
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(false);
        assertFalse(run(job));
        verify(store).skipped(eq(job), contains("missing"), eq(now));
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        when(self.hasPermission(channel, Permission.MESSAGE_HISTORY)).thenReturn(false);
        var uncertain = new FreebieBroadcastStore.Job(job.id(), ID, GUILD, CHANNEL, 2, true, "t");
        assertFalse(run(uncertain));
        verify(store).skipped(eq(uncertain), contains("history"), eq(now));
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void aStoppedBroadcastOrGlobalPauseSendsNothing() {
        when(store.get(ID)).thenReturn(Optional.of(new FreebieBroadcastStore.Broadcast(ID, "t", "b", "CANCELLED", "owner")));
        assertFalse(run(job));
        verify(store).cancelled(job, now);
        when(store.get(ID)).thenReturn(Optional.of(new FreebieBroadcastStore.Broadcast(ID, "t", "b", "SENDING", "owner")));
        when(repository.sendingPaused()).thenReturn(true);
        run(job);
        verify(store).release(job, now.plusSeconds(120), now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void sendWithPingMentionsOnlyTheServersSavedRole() {
        var role = "300000000000000000";
        when(store.get(ID)).thenReturn(Optional.of(new FreebieBroadcastStore.Broadcast(ID, "T", "B @everyone", "SENDING", "owner", true)));
        when(guild.getRoleById(role)).thenReturn(mock(Role.class));
        when(send.submit()).thenReturn(new CompletableFuture<>());
        run(new FreebieBroadcastStore.Job(job.id(), ID, GUILD, CHANNEL, 1, false, "t", Optional.of(role)));
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel).sendMessage(captor.capture());
        assertTrue(V2.text(captor.getValue().getComponents()).startsWith("<@&" + role + ">"));
        assertEquals(Set.of(role), captor.getValue().getMentionedRoles());
        assertTrue(captor.getValue().getAllowedMentions().isEmpty(), "never @everyone, @here or users");
    }

    @Test void sendWithoutPingOrADeletedRoleMentionsNobody() {
        var role = "300000000000000000";
        when(send.submit()).thenReturn(new CompletableFuture<>());
        run(new FreebieBroadcastStore.Job(job.id(), ID, GUILD, CHANNEL, 1, false, "t", Optional.of(role))); // ping=false
        when(store.get(ID)).thenReturn(Optional.of(new FreebieBroadcastStore.Broadcast(ID, "T", "B", "SENDING", "owner", true)));
        run(new FreebieBroadcastStore.Job(job.id(), ID, GUILD, CHANNEL, 1, false, "t", Optional.of(role))); // role deleted
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel, times(2)).sendMessage(captor.capture());
        for (var message : captor.getAllValues()) {
            assertFalse(V2.text(message.getComponents()).contains("<@&"));
            assertTrue(message.getMentionedRoles().isEmpty());
        }
    }

    @Test void typedLineBreaksBecomeRealOnes() {
        assertEquals("a\nb", FreebieBroadcasts.body(" a\\nb "));
    }
}
