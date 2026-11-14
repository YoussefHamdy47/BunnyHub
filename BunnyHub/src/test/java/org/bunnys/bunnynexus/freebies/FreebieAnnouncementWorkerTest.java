package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Uncertain pages that no delivery row can recover any more (every row is final): history decides, and nothing is
 * ever posted. Mocked Discord and storage.
 */
class FreebieAnnouncementWorkerTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", MESSAGE = "900000000000000000",
            ROW = "gamerpower:1|" + CHANNEL, ID = FreebieAnnouncementStore.pageId("drop|" + CHANNEL + "|1", CHANNEL, 1), TOKEN = "orphan-token";
    final Instant now = Instant.parse("2026-11-10T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieAnnouncementStore store = mock(FreebieAnnouncementStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final AtomicInteger shared = new AtomicInteger(), background = new AtomicInteger();
    final FreebieAnnouncementWorker worker = new FreebieAnnouncementWorker(repository,
            new FreebieFeatures(true, true, Set.of(CHANNEL), true, true, false), work::add, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        when(repository.announcements()).thenReturn(store);
        when(store.claimRefresh(any(), any())).thenReturn(Optional.empty());
        var page = new Document("_id", ID).append("guildId", GUILD).append("channelId", CHANNEL).append("state", "SUBMITTING")
                .append("verifyFirst", true).append("members", List.of("gamerpower:1")).append("deliveries", List.of(ROW));
        when(store.claimOrphan(any(), any())).thenReturn(Optional.of(new FreebieAnnouncementStore.Orphan(page, TOKEN)))
                .thenReturn(Optional.empty());
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
    }

    void drain() { while (!work.isEmpty()) work.remove().run(); }

    @SuppressWarnings("unchecked") // Generic REST action mock.
    void history(List<Message> messages) {
        var history = mock(MessageHistory.class);
        RestAction<List<Message>> past = mock(RestAction.class, RETURNS_SELF);
        when(past.submit()).thenReturn(CompletableFuture.completedFuture(messages));
        when(history.retrievePast(50)).thenReturn(past);
        when(channel.getHistory()).thenReturn(history);
    }

    Message posted(String authorId) {
        var message = mock(Message.class);
        var author = mock(User.class);
        when(author.getId()).thenReturn(authorId);
        when(message.getAuthor()).thenReturn(author);
        when(message.getId()).thenReturn(MESSAGE);
        when(message.getEmbeds()).thenReturn(List.of(new EmbedBuilder().setDescription("card")
                .setFooter("Powered by GamerPower • ref " + FreebieMessages.reference(ROW)).build()));
        return message;
    }

    @Test void aPostFoundInHistoryIsRecordedAsSentAndNeverPostedAgain() {
        history(List.of(posted("bot")));
        assertTrue(worker.claimOne(client, shared, background));
        drain();
        verify(store).reconciled(eq(ID), eq(TOKEN), eq(MESSAGE), anyBoolean(), eq(now));
        verify(store, never()).cancel(any(), any(), any());
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        assertEquals(0, shared.get());
        assertEquals(0, background.get());
    }

    @Test void aPageNotFoundInHistoryIsWithdrawnWithoutAMessage() {
        history(List.of(posted("someone-else")));
        assertTrue(worker.claimOne(client, shared, background));
        drain();
        verify(store).cancel(ID, TOKEN, now);
        verify(store, never()).reconciled(any(), any(), any(), anyBoolean(), any());
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void unreadableHistoryLeavesThePageUncertainForALaterCheck() {
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(false);
        assertTrue(worker.claimOne(client, shared, background));
        verify(store).reconcileLater(ID, TOKEN, now.plus(Duration.ofMinutes(30)), now);
        verify(channel, never()).getHistory();
        verify(store, never()).cancel(any(), any(), any());
        assertEquals(0, shared.get());
    }

    @Test void pagesAreOnlyLookedForOccasionallyAndAfterDueEdits() {
        when(store.claimOrphan(any(), any())).thenReturn(Optional.empty());
        assertFalse(worker.claimOne(client, shared, background));
        assertFalse(worker.claimOne(client, shared, background));
        verify(store, times(1)).claimOrphan(any(), any());
        verify(store, times(2)).claimRefresh(any(), any());
    }
}
