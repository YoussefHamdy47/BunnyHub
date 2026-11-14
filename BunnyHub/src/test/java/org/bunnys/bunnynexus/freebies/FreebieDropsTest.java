package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drops (FREEBIE_REDESIGN.md step 3) against mocked Discord and storage: one message and one mention per channel
 * batch, every saved role once, no ping for catch-up, quiet games or overflow pages, ten games within Discord's
 * limits, and the per-game rollback with the shared card.
 */
class FreebieDropsTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", ROLE = "300000000000000000",
            ROLE_2 = "300000000000000002", ROLE_QUIET = "300000000000000003", ROLE_DELETED = "300000000000000004",
            MESSAGE = "900000000000000000";
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    final String key = FreebieDrops.key(CHANNEL, now.minusSeconds(120));
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieAnnouncementStore store = mock(FreebieAnnouncementStore.class);
    final FreebieDeliveryStore deliveries = mock(FreebieDeliveryStore.class);
    final FreebieRoutes routes = mock(FreebieRoutes.class);
    final FreebieNotifier notifier = mock(FreebieNotifier.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final List<FreebieOffer> games = new ArrayList<>();
    final MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
    final CompletableFuture<Message> sent = new CompletableFuture<>();
    int released;

    @BeforeEach void setup() {
        when(repository.announcements()).thenReturn(store);
        when(repository.deliveries()).thenReturn(deliveries);
        when(repository.routes()).thenReturn(routes);
        when(deliveries.owns(any(), any())).thenReturn(true);
        when(deliveries.get(anyString())).thenAnswer(call -> Optional.of(new Document("_id", call.getArgument(0))));
        for (int i = 0; i < 12; i++) {
            var offer = FreebieAnnouncementCardsTest.epic("g" + i, "Game " + i);
            games.add(offer);
            when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
            var doc = FreebieSourceStore.fresh(FreebieIdentityTest.epic(Instant.parse("2026-10-22T15:00:00Z"), Instant.parse("2026-10-29T15:00:00Z")), now);
            doc.put("_id", offer.id()); doc.put("title", offer.title()); doc.put("state", "APPROVED"); doc.put("decidedBy", "owner");
            when(repository.offerDocument(offer.id())).thenReturn(Optional.of(doc));
            when(repository.offerStatus(offer.id())).thenReturn(Optional.of(doc));
        }
        when(guild.getSelfMember()).thenReturn(self);
        for (String role : List.of(ROLE, ROLE_2, ROLE_QUIET)) when(guild.getRoleById(role)).thenReturn(mock(Role.class));
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(send);
        when(send.submit()).thenReturn(sent);
        when(store.submitting(anyString(), anyString(), any())).thenReturn(true);
        when(store.sent(anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
    }

    FreebieOffer game(int i) { return games.get(i); }
    static String row(FreebieOffer offer) { return offer.id() + "|" + CHANNEL; }

    FreebieRepository.Claimed job(FreebieOffer offer, boolean catchUp) {
        return new FreebieRepository.Claimed(row(offer), offer.id(), GUILD, CHANNEL, Optional.of(ROLE), 1, false,
                "token-" + offer.id(), catchUp, Optional.of(key));
    }

    static Document peer(FreebieOffer offer, String role, boolean catchUp) {
        var d = new Document("_id", row(offer)).append("offerId", offer.id()).append("roleId", role);
        if (catchUp) d.append("catchUp", true);
        return d;
    }

    Document page(int number, String state, List<FreebieOffer> members, String token, List<String> roles) {
        return new Document("_id", FreebieAnnouncementStore.pageId(key, CHANNEL, number)).append("key", key).append("channelId", CHANNEL)
                .append("guildId", GUILD).append("page", number).append("drop", true).append("capacity", FreebieAnnouncementStore.DROP_PAGE_SIZE)
                .append("roleIds", roles).append("roleId", roles.isEmpty() ? null : roles.getFirst())
                .append("members", members.stream().map(FreebieOffer::id).toList())
                .append("deliveries", members.stream().map(FreebieDropsTest::row).toList())
                .append("state", state).append("verifyFirst", false).append("token", token)
                .append("leaseUntil", token == null ? null : Date.from(now.plusSeconds(180)))
                .append("messageId", "SENT".equals(state) ? MESSAGE : null).append("revision", 1L).append("applied", 1L);
    }

    boolean start(FreebieRepository.Claimed job) { return start(job, null); }
    boolean start(FreebieRepository.Claimed job, FreebiePings ledger) {
        var sender = new FreebieAnnouncementSender(repository, new FreebieFeatures(true, false, Set.of(), false, true, false),
                work::add, Clock.fixed(now, ZoneOffset.UTC), notifier).withPings(ledger);
        return sender.start(client, job, guild, channel, Optional.of(ROLE), () -> released++);
    }
    void drain() { while (!work.isEmpty()) work.remove().run(); }

    MessageCreateData onlyMessage() {
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel, times(1)).sendMessage(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ one message, one mention

    @Test void aBurstInOneChannelIsOneMessageWithOneMentionAndItsCount() {
        var first = job(game(0), false);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(key), eq(CHANNEL), eq(game(0).id()), anyInt()))
                .thenReturn(List.of(peer(game(1), ROLE, false), peer(game(2), ROLE, false)));
        var three = List.of(game(0), game(1), game(2));
        var created = page(1, "SUBMITTING", three, first.token(), List.of(ROLE));
        when(store.createWithRoles(eq(key), eq(GUILD), eq(CHANNEL), eq(1), eq(List.of(ROLE)), eq(three.stream().map(FreebieOffer::id).toList()),
                any(), eq(first.token()), any(), eq(now))).thenReturn(Optional.of(created));
        assertTrue(start(first));
        drain();
        var message = onlyMessage();
        assertEquals(List.of(3), FreebieAnnouncementCardsTest.gamesPerEmbed(message.getEmbeds()), "three Epic games share one embed");
        assertEquals("<@&" + ROLE + "> 3 new free games", message.getContent());
        assertEquals(Set.of(ROLE), message.getMentionedRoles());
        assertTrue(message.getAllowedMentions().stream().noneMatch(t -> t == Message.MentionType.EVERYONE || t == Message.MentionType.HERE));
        var posted = mock(Message.class);
        when(posted.getId()).thenReturn(MESSAGE);
        sent.complete(posted);
        drain();
        verify(routes).followPage(List.of(row(game(1)), row(game(2))), MESSAGE, now);

        // The other rows of the drop, claimed afterwards, follow the posted message: no second message, no second ping.
        when(store.pages(key, CHANNEL)).thenReturn(List.of(page(1, "SENT", three, null, List.of(ROLE))));
        for (int i = 1; i <= 2; i++) {
            var later = job(game(i), false);
            assertFalse(start(later));
            verify(repository).markSent(later, MESSAGE, now);
        }
        verify(channel, times(1)).sendMessage(any(MessageCreateData.class));
    }

    @Test void aDropMentionsEachSavedRoleOnceButNeverForQuietGamesOrDeletedRoles() {
        var first = job(game(0), false);
        repository.offerDocument(game(3).id()).orElseThrow().put("quiet", true);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(key), eq(CHANNEL), eq(game(0).id()), anyInt())).thenReturn(List.of(peer(game(1), ROLE_2, false),
                peer(game(2), ROLE, false), peer(game(3), ROLE_QUIET, false), peer(game(4), ROLE_DELETED, false)));
        var members = List.of(game(0), game(1), game(2), game(3), game(4));
        var created = page(1, "SUBMITTING", members, first.token(), List.of(ROLE, ROLE_2));
        when(store.createWithRoles(eq(key), any(), any(), eq(1), eq(List.of(ROLE, ROLE_2)), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(created));
        assertTrue(start(first));
        drain();
        var message = onlyMessage();
        assertEquals("<@&" + ROLE + "> <@&" + ROLE_2 + "> 5 new free games", message.getContent());
        assertEquals(Set.of(ROLE, ROLE_2), message.getMentionedRoles());
        // Audit finding 5: each peer is checked with one small status read; no full offer read, no separate quiet count.
        verify(repository, never()).quiet(anyString());
        for (int i = 1; i <= 4; i++) {
            verify(repository, never()).offer(game(i).id());
            verify(repository).offerStatus(game(i).id());
        }
    }

    @Test void theStatusReadDecidesEligibilityLikeTheFullOffer() {
        var status = new org.bson.Document("_id", "x").append("state", "APPROVED")
                .append("endsAt", java.util.Date.from(now.plusSeconds(60)));
        assertTrue(FreebieRepository.eligible(status, now));
        assertTrue(FreebieRepository.eligible(new org.bson.Document(status).append("state", "COMPLETED"), now));
        assertTrue(FreebieRepository.eligible(new org.bson.Document(status).append("endsAt", null), now), "no end date listed");
        assertFalse(FreebieRepository.eligible(new org.bson.Document(status).append("endsAt", java.util.Date.from(now)), now), "ends exactly now");
        assertFalse(FreebieRepository.eligible(new org.bson.Document(status).append("gone", true), now));
        for (String state : List.of("PENDING", "STOPPED", "REJECTED", "EXPIRED", "PURGED"))
            assertFalse(FreebieRepository.eligible(new org.bson.Document(status).append("state", state), now), state);
        assertFalse(FreebieRepository.eligible(null, now));
        assertEquals(List.of("_id", "state", "endsAt", "gone", "quiet"), FreebieRepository.STATUS_FIELDS);
    }

    @Test void concurrentClaimsOfOneDropSendOneMessage() {
        var first = job(game(0), false);
        var second = job(game(1), false);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(key), eq(CHANNEL), anyString(), anyInt())).thenReturn(List.of());
        var created = page(1, "SUBMITTING", List.of(game(0), game(1)), first.token(), List.of(ROLE));
        // The page _id is unique: only the first creation wins; the other worker's insert reports the duplicate.
        when(store.createWithRoles(eq(key), any(), any(), eq(1), any(), any(), any(), eq(first.token()), any(), any())).thenReturn(Optional.of(created));
        when(store.createWithRoles(eq(key), any(), any(), eq(1), any(), any(), any(), eq(second.token()), any(), any())).thenReturn(Optional.empty());
        assertTrue(start(first));
        assertFalse(start(second));
        verify(repository).release(second, now.plusSeconds(2), now);

        // Retried while the first worker still holds the page's lease: it waits instead of posting.
        when(store.pages(key, CHANNEL)).thenReturn(List.of(created));
        when(store.takeOver(any(), anyString(), any(), any())).thenReturn(Optional.empty());
        assertFalse(start(second));
        verify(repository).release(second, now.plusSeconds(10), now);
        // A game of the same drop that is not on that page waits too, then joins it silently; no second page meanwhile.
        var third = job(game(2), false);
        assertFalse(start(third));
        verify(repository).release(third, now.plusSeconds(10), now);
        verify(store, never()).createWithRoles(any(), any(), any(), eq(2), any(), any(), any(), any(), any(), any());
        drain();
        verify(channel, times(1)).sendMessage(any(MessageCreateData.class));
    }

    // ------------------------------------------------------------------ never pinged

    @Test void overflowPagesOfADropNeverPing() {
        List<FreebieOffer> full = games.subList(0, FreebieAnnouncementStore.DROP_PAGE_SIZE);
        when(store.pages(key, CHANNEL)).thenReturn(List.of(page(1, "SENT", full, null, List.of(ROLE))));
        var job = job(game(10), false);
        // Even a page document that wrongly carried roles would not ping after page 1.
        var second = page(2, "SUBMITTING", List.of(game(10)), job.token(), List.of(ROLE));
        when(store.createWithRoles(eq(key), any(), any(), eq(2), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(second));
        assertTrue(start(job));
        drain();
        var message = onlyMessage();
        assertTrue(message.getMentionedRoles().isEmpty());
        assertEquals("", message.getContent());
        verify(store, never()).join(any(), any(), any(), any());
    }

    @Test void aDropPageWithRoomTakesALateGameSilentlyUpToTen() {
        when(store.pages(key, CHANNEL)).thenReturn(List.of(page(1, "SENT", games.subList(0, 6), null, List.of(ROLE))));
        var late = job(game(6), false);
        when(store.join(FreebieAnnouncementStore.pageId(key, CHANNEL, 1), game(6).id(), late.id(), now)).thenReturn(true);
        assertFalse(start(late));
        verify(repository).markSent(late, MESSAGE, now);
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test void aCatchUpDropNeverPings() {
        var first = job(game(0), true);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(key), eq(CHANNEL), eq(game(0).id()), anyInt()))
                .thenReturn(List.of(peer(game(1), ROLE, true), peer(game(2), ROLE_2, true)));
        var created = page(1, "SUBMITTING", List.of(game(0), game(1), game(2)), first.token(), List.of());
        when(store.createWithRoles(eq(key), any(), any(), eq(1), eq(List.of()), any(), any(), any(), any(), any())).thenReturn(Optional.of(created));
        assertTrue(start(first));
        drain();
        var message = onlyMessage();
        assertEquals(List.of(3), FreebieAnnouncementCardsTest.gamesPerEmbed(message.getEmbeds()), "three Epic games share one embed");
        assertTrue(message.getMentionedRoles().isEmpty());
        assertEquals("", message.getContent());
    }

    // ------------------------------------------------------------------ ping budget (step 4)

    @Test void twoDropsWithinTheCooldownMentionOnce() {
        var ledger = mock(FreebiePings.class);
        when(ledger.take(eq(GUILD), eq(CHANNEL), anyString(), any()))
                .thenReturn(FreebiePings.Take.ALLOWED, new FreebiePings.Take(false, Optional.of(now.minusSeconds(600))));
        when(routes.announcementPeers(anyString(), eq(CHANNEL), anyString(), anyInt())).thenReturn(List.of());

        var first = job(game(0), false);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(store.createWithRoles(eq(key), any(), any(), eq(1), eq(List.of(ROLE)), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(page(1, "SUBMITTING", List.of(game(0)), first.token(), List.of(ROLE))));
        assertTrue(start(first, ledger));
        drain();

        // The next drop in the same channel, ten minutes after the first ping: it posts, without the mention.
        String next = FreebieDrops.key(CHANNEL, now);
        var second = new FreebieRepository.Claimed(row(game(1)), game(1).id(), GUILD, CHANNEL, Optional.of(ROLE), 1, false,
                "token-next", false, Optional.of(next));
        when(store.pages(next, CHANNEL)).thenReturn(List.of());
        var quiet = page(1, "SUBMITTING", List.of(game(1)), second.token(), List.of());
        quiet.put("_id", FreebieAnnouncementStore.pageId(next, CHANNEL, 1)); quiet.put("key", next);
        when(store.createWithRoles(eq(next), any(), any(), eq(1), eq(List.of()), any(), any(), any(), any(), any())).thenReturn(Optional.of(quiet));
        assertTrue(start(second, ledger));
        drain();

        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel, times(2)).sendMessage(captor.capture());
        assertEquals(Set.of(ROLE), captor.getAllValues().get(0).getMentionedRoles());
        assertTrue(captor.getAllValues().get(1).getMentionedRoles().isEmpty(), "pinged ten minutes ago: no second mention");
        assertFalse(captor.getAllValues().get(1).getContent().contains("<@&"));
        verify(ledger).take(GUILD, CHANNEL, key, now);
        verify(ledger).take(GUILD, CHANNEL, next, now);
    }

    @Test void pagesThatNeverPingNeverUseTheChannelsPing() {
        var ledger = mock(FreebiePings.class);
        // Catch-up: no roles to mention.
        var catchUp = job(game(0), true);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(key), eq(CHANNEL), anyString(), anyInt())).thenReturn(List.of(peer(game(1), ROLE, true)));
        when(store.createWithRoles(eq(key), any(), any(), eq(1), eq(List.of()), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(page(1, "SUBMITTING", List.of(game(0), game(1)), catchUp.token(), List.of())));
        assertTrue(start(catchUp, ledger));
        // An overflow page: page 1 is posted, so this page is not the drop's lead.
        when(store.pages(key, CHANNEL)).thenReturn(List.of(page(1, "SENT", games.subList(0, FreebieAnnouncementStore.DROP_PAGE_SIZE), null, List.of(ROLE))));
        var overflow = job(game(10), false);
        when(store.createWithRoles(eq(key), any(), any(), eq(2), eq(List.of()), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(page(2, "SUBMITTING", List.of(game(10)), overflow.token(), List.of())));
        assertTrue(start(overflow, ledger));
        verifyNoInteractions(ledger);
    }

    @Test void thePingCooldownSettingIsClampedAndInvalidValuesUseTheDefault() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Duration.ofMinutes(60), FreebieFeatures.load(Map.<String, String>of()::get, warnings::add).pingCooldown());
        assertEquals(Duration.ofMinutes(15), FreebieFeatures.load(Map.of("FREEBIE_PING_COOLDOWN_MINUTES", "15")::get, warnings::add).pingCooldown());
        assertEquals(Duration.ZERO, FreebieFeatures.load(Map.of("FREEBIE_PING_COOLDOWN_MINUTES", "0")::get, warnings::add).pingCooldown());
        assertTrue(warnings.isEmpty());
        assertEquals(Duration.ofMinutes(1440), FreebieFeatures.pingCooldown("5000", warnings::add));
        assertEquals(Duration.ZERO, FreebieFeatures.pingCooldown("-3", warnings::add));
        assertEquals(Duration.ofMinutes(60), FreebieFeatures.pingCooldown("soon", warnings::add));
        assertEquals(3, warnings.size());
    }

    @Test void catchUpKeysAreDropsOfTheirOwnAndOlderKeysAreNot() {
        String catchUp = FreebieDrops.catchUpKey(CHANNEL, now);
        assertTrue(FreebieDrops.isDrop(catchUp));
        assertTrue(FreebieDrops.isCatchUp(catchUp));
        assertNotEquals(FreebieDrops.key(CHANNEL, now), catchUp, "a catch-up batch never shares a fresh drop's ping");
        assertFalse(FreebieDrops.isDrop("epic|m|1792508400"));
        assertFalse(FreebieDrops.isDrop("offer:epic:abc"));
        assertEquals(FreebieAnnouncementStore.DROP_PAGE_SIZE, FreebieAnnouncementStore.capacity(catchUp));
        assertEquals(FreebieAnnouncementStore.PAGE_SIZE, FreebieAnnouncementStore.capacity("epic|m|1792508400"));
    }

    // ------------------------------------------------------------------ review findings 4-9

    @SuppressWarnings("unchecked") // Captor of a generic list.
    List<String> createdMembers(int page) {
        ArgumentCaptor<List<String>> members = ArgumentCaptor.forClass(List.class);
        verify(store).createWithRoles(eq(key), any(), any(), eq(page), any(), members.capture(), any(), any(), any(), any());
        return members.getValue();
    }

    /** Finding 4: page 1 is SENT but a member's row was not yet marked sent (another process, or a crash in between). */
    @Test void anOverflowPageNeverRepeatsAGameOfAnEarlierPage() {
        List<FreebieOffer> full = games.subList(0, FreebieAnnouncementStore.DROP_PAGE_SIZE);
        when(store.pages(key, CHANNEL)).thenReturn(List.of(page(1, "SENT", full, null, List.of(ROLE))));
        when(routes.announcementPeers(eq(key), eq(CHANNEL), eq(game(10).id()), anyInt()))
                .thenReturn(List.of(peer(game(1), ROLE, false), peer(game(11), ROLE, false)));
        var job = job(game(10), false);
        when(store.createWithRoles(eq(key), any(), any(), eq(2), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(page(2, "SUBMITTING", List.of(game(10), game(11)), job.token(), List.of())));
        assertTrue(start(job));
        assertEquals(List.of(game(10).id(), game(11).id()), createdMembers(2), "a page-1 game is never posted again on page 2");
    }

    /** Finding 5: nothing was posted on page 1, so the drop's ping goes on the first page that is. */
    @Test void aDropWhoseFirstPageWasWithdrawnPingsOnItsFirstPostedPage() {
        when(store.pages(key, CHANNEL)).thenReturn(List.of(page(1, "CANCELLED", List.of(game(0)), null, List.of(ROLE))));
        var job = job(game(1), false);
        var second = page(2, "SUBMITTING", List.of(game(1)), job.token(), List.of(ROLE)).append("lead", true);
        when(store.createWithRoles(eq(key), any(), any(), eq(2), eq(List.of(ROLE)), any(), any(), any(), any(), any())).thenReturn(Optional.of(second));
        assertTrue(start(job));
        drain();
        var message = onlyMessage();
        assertEquals(Set.of(ROLE), message.getMentionedRoles());
        assertEquals("<@&" + ROLE + "> 1 new free game", message.getContent());
    }

    /** Finding 6: a role whose only game fits on a later page is still mentioned once, on the first post. */
    @Test void aRoleWhoseGamesOnlyFitALaterPageIsPingedOnTheFirst() {
        var first = job(game(0), false);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        List<Document> peers = new ArrayList<>();
        for (int i = 1; i < 12; i++) peers.add(peer(game(i), i == 11 ? ROLE_2 : ROLE, false));
        when(routes.announcementPeers(eq(key), eq(CHANNEL), eq(game(0).id()), intThat(n -> n >= 12))).thenReturn(peers);
        when(store.createWithRoles(eq(key), any(), any(), eq(1), eq(List.of(ROLE, ROLE_2)), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(page(1, "SUBMITTING", games.subList(0, 10), first.token(), List.of(ROLE, ROLE_2))));
        assertTrue(start(first));
        assertEquals(games.subList(0, 10).stream().map(FreebieOffer::id).toList(), createdMembers(1), "page 1 still holds ten games");
        drain();
        assertEquals(Set.of(ROLE, ROLE_2), onlyMessage().getMentionedRoles());
    }

    /** Finding 7: the page's SENT write did not land (its lease was lost): no other row is recorded from it. */
    @Test void aLostPageLeaseNeverMarksTheOtherRowsSent() {
        var first = job(game(0), false);
        when(store.pages(key, CHANNEL)).thenReturn(List.of());
        when(routes.announcementPeers(eq(key), eq(CHANNEL), eq(game(0).id()), anyInt())).thenReturn(List.of(peer(game(1), ROLE, false)));
        var created = page(1, "SUBMITTING", List.of(game(0), game(1)), first.token(), List.of(ROLE));
        when(store.createWithRoles(eq(key), any(), any(), eq(1), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(created));
        when(store.sent(anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(false);
        assertTrue(start(first));
        drain();
        var posted = mock(Message.class);
        when(posted.getId()).thenReturn(MESSAGE);
        sent.complete(posted);
        drain();
        verify(deliveries).block(first, FreebieDeliveryStore.Block.UNCERTAIN, now);
        verify(routes, never()).followPage(any(), any(), any());
        verify(repository, never()).markSent(any(), any(), any());
    }

    /** Finding 8: the headline is recounted on edits; the mentions stay as posted and nobody is notified again. */
    @Test void editsRecountTheHeadlineAndKeepTheMentions() {
        var members = List.of(FreebieAnnouncementCardsTest.member("a", FreebieAnnouncementCards.Status.LIVE),
                FreebieAnnouncementCardsTest.member("b", FreebieAnnouncementCards.Status.ENDED),
                FreebieAnnouncementCardsTest.member("c", FreebieAnnouncementCards.Status.LIVE),
                FreebieAnnouncementCardsTest.member("d", FreebieAnnouncementCards.Status.LIVE));
        String posted = "<@&" + ROLE + "> <@&" + ROLE_2 + "> 3 new free games";
        var edit = FreebieAnnouncementCards.edit(members, false, now, Optional.of(posted));
        assertEquals("<@&" + ROLE + "> <@&" + ROLE_2 + "> 3 new free games", edit.getContent(), "a silent join and an ended game: still three");
        var one = FreebieAnnouncementCards.edit(members.subList(0, 2), false, now, Optional.of(posted));
        assertEquals("<@&" + ROLE + "> <@&" + ROLE_2 + "> 1 new free game", one.getContent());
        assertTrue(one.getMentionedRoles().isEmpty(), "an edit never pings");
        var ended = FreebieAnnouncementCards.edit(members.subList(1, 2), false, now, Optional.of(posted));
        assertEquals("", ended.getContent());
        var older = FreebieAnnouncementCards.edit(members, false, now, Optional.of("<@&" + ROLE + ">"));
        assertFalse(older.toData().hasKey("content"), "an older page's content is left as posted");
        assertTrue(one.toData().hasKey("content"));
    }

    /** Finding 9: even claim links near Discord's 512-character button limit never push ten cards over 6000. */
    @Test void tenCardsWithVeryLongClaimLinksStillFit() {
        List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
        for (int i = 0; i < FreebieAnnouncementStore.DROP_PAGE_SIZE; i++) {
            var offer = FreebieAnnouncementCardsTest.epic("p".repeat(460) + i, "Title ".repeat(45) + i);
            var facts = FreebieAnnouncementCardsTest.facts("o" + i, List.of(), Optional.empty());
            members.add(new FreebieAnnouncementCards.Member(offer, facts, offer.id() + "|" + CHANNEL, FreebieAnnouncementCards.Status.LIVE));
        }
        var embeds = FreebieAnnouncementCards.embeds(members, now);
        int total = embeds.stream().mapToInt(MessageEmbed::getLength).sum();
        assertTrue(total <= FreebieAnnouncementCards.EMBED_TOTAL, "embed total " + total);
        assertEquals(List.of(3, 3, 2, 2), FreebieAnnouncementCardsTest.gamesPerEmbed(embeds));
        FreebieAnnouncementCardsTest.everyEmbedCarriesAMemberFooter(embeds, members);
        assertTrue(embeds.stream().flatMap(e -> e.getFields().stream()).allMatch(f -> f.getValue().contains("Ends")
                && f.getValue().length() <= MessageEmbed.VALUE_MAX_LENGTH), "every game still shows its end");
    }

    // ------------------------------------------------------------------ ten games, Discord's limits

    @Test void tenGamesWithLongTextFitOneMessage() {
        List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
        for (int i = 0; i < FreebieAnnouncementStore.DROP_PAGE_SIZE; i++) {
            // Long store links and titles: an equal share per card is not enough on the first pass.
            var offer = FreebieAnnouncementCardsTest.epic("long-product-page-" + "x".repeat(220) + i, "Title ".repeat(45) + i);
            var facts = FreebieAnnouncementCardsTest.facts("o" + i, List.of(), Optional.empty());
            members.add(new FreebieAnnouncementCards.Member(offer, facts, offer.id() + "|" + CHANNEL, FreebieAnnouncementCards.Status.LIVE));
        }
        var data = FreebieAnnouncementCards.create(members, List.of(ROLE), true, now);
        assertEquals(List.of(3, 3, 2, 2), FreebieAnnouncementCardsTest.gamesPerEmbed(data.getEmbeds()), "ten Epic games in four embeds");
        int total = data.getEmbeds().stream().mapToInt(MessageEmbed::getLength).sum();
        assertTrue(total <= FreebieAnnouncementCards.EMBED_TOTAL, "embed total " + total);
        FreebieAnnouncementCardsTest.everyEmbedCarriesAMemberFooter(data.getEmbeds(), members);
        var rows = data.getComponents().stream().map(c -> (ActionRow) c).toList();
        assertTrue(rows.size() <= 5);
        var labels = FreebieAnnouncementCardsTest.labels(rows);
        assertEquals(11, labels.size(), "a claim per game plus the combined checkout");
        assertEquals("Claim all PC games (10)", labels.getFirst());
        assertTrue(rows.stream().flatMap(r -> r.getComponents().stream()).allMatch(c -> ((Button) c).getUrl() != null));
    }

    // ------------------------------------------------------------------ settings and rollback

    @Test void theDropWindowSettingIsClampedAndInvalidValuesUseTheDefault() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Duration.ofSeconds(120), FreebieFeatures.dropWindow(null, warnings::add));
        assertEquals(Duration.ofSeconds(45), FreebieFeatures.dropWindow(" 45 ", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(Duration.ofSeconds(10), FreebieFeatures.dropWindow("1", warnings::add));
        assertEquals(Duration.ofSeconds(600), FreebieFeatures.dropWindow("3600", warnings::add));
        assertEquals(Duration.ofSeconds(120), FreebieFeatures.dropWindow("two minutes", warnings::add));
        assertEquals(3, warnings.size());
        var loaded = FreebieFeatures.load(Map.of("FREEBIE_DROP_WINDOW_SECONDS", "30")::get, warnings::add);
        assertEquals(Optional.of(Duration.ofSeconds(30)), loaded.drops());
        var rollback = FreebieFeatures.load(Map.of("FREEBIE_GROUPED_ANNOUNCEMENTS", "false", "FREEBIE_DROP_WINDOW_SECONDS", "30")::get, warnings::add);
        assertEquals(Optional.empty(), rollback.drops(), "the rollback plans no drops");
    }

    @Test void withTheRollbackARowWithoutAPageKeyPostsPerGameWithTheSharedCard() throws Exception {
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            var offer = FreebieUnitTest.offer("Rollback", FreebieStore.STEAM); // live until 2026-10-01
            var clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
            var job = new FreebieRepository.Claimed(offer.id() + "|" + CHANNEL, offer.id(), GUILD, CHANNEL, Optional.of(ROLE), 1, false, "t");
            when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
            when(client.getGuildById(GUILD)).thenReturn(guild);
            when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
            when(repository.claim(any(), any(Duration.class))).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
            when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
            when(deliveries.submitting(any(), any())).thenReturn(true);
            var done = new CountDownLatch(1);
            when(channel.sendMessage(any(MessageCreateData.class))).thenAnswer(call -> { done.countDown(); return send; });
            var announcements = mock(FreebieAnnouncementSender.class);
            var sender = new FreebieSender(repository, () -> client, executor, clock, notifier)
                    .withAnnouncements(announcements, null, null);
            executor.submit(sender::tick).get(5, TimeUnit.SECONDS);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            verify(announcements, never()).start(any(), any(), any(), any(), any(), any());
            var message = onlyMessage();
            assertEquals("<@&" + ROLE + ">", message.getContent(), "a per-game post pings as before");
            var embed = message.getEmbeds().getFirst();
            assertEquals(List.of(FreebieCards.PRICE, FreebieCards.ENDS, FreebieCards.LAUNCHER),
                    embed.getFields().stream().map(MessageEmbed.Field::getName).limit(3).toList(), "the shared card's fields");
            var buttons = FreebieAnnouncementCardsTest.labels(message.getComponents().stream().map(c -> (ActionRow) c).toList());
            assertEquals(List.of(FreebieCards.CLAIM_LABEL), buttons, "one claim button, no spacers, no Offer Details");
        } finally { executor.shutdownNow(); }
    }

    @Test void aPerGameAlertWithinTheCooldownPostsWithoutItsMention() throws Exception {
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            var offer = FreebieUnitTest.offer("Rollback", FreebieStore.STEAM);
            var clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
            var job = new FreebieRepository.Claimed(offer.id() + "|" + CHANNEL, offer.id(), GUILD, CHANNEL, Optional.of(ROLE), 1, false, "t");
            when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
            when(client.getGuildById(GUILD)).thenReturn(guild);
            when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
            when(repository.claim(any(), any(Duration.class))).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
            when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
            when(deliveries.submitting(any(), any())).thenReturn(true);
            var done = new CountDownLatch(1);
            when(channel.sendMessage(any(MessageCreateData.class))).thenAnswer(call -> { done.countDown(); return send; });
            var ledger = mock(FreebiePings.class);
            when(ledger.take(any(), any(), any(), any())).thenReturn(new FreebiePings.Take(false, Optional.empty()));
            var sender = new FreebieSender(repository, () -> client, executor, clock, notifier)
                    .withAnnouncements(mock(FreebieAnnouncementSender.class), null, null).withPings(ledger);
            executor.submit(sender::tick).get(5, TimeUnit.SECONDS);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            var message = onlyMessage();
            assertTrue(message.getMentionedRoles().isEmpty());
            assertFalse(message.getContent().contains("<@&"), "the channel was pinged within the cooldown");
            verify(ledger).take(GUILD, CHANNEL, job.id(), clock.instant());
        } finally { executor.shutdownNow(); }
    }
}
