package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fanatical and GOG as sources. Fanatical's responses were captured on 2026-11-27 during the Vertical Drop Heroes HD
 * giveaway (trimmed to the fields read). GOG's home page list and catalog are captured too; its giveaway section is
 * built from the fields GOG's storefront code reads, since the section was not served to the capturing machine.
 */
class StoreSourcesTest {
    final Instant now = Instant.parse("2026-11-27T13:00:00Z");

    static byte[] resource(String name) {
        try (var input = StoreSourcesTest.class.getResourceAsStream("/" + name)) { return Objects.requireNonNull(input).readAllBytes(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** Answers from fixtures; "unchanged" after the first committed read when {@code notModified} is set. */
    static final class Fake implements ConsoleTips.Http {
        final Map<String, byte[]> pages = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        final Set<String> committed = new HashSet<>(), pending = new HashSet<>();
        boolean notModified = true;
        Fake put(URI url, String resource) { pages.put(url.toString(), resource(resource)); return this; }
        Fake put(URI url, byte[] body) { pages.put(url.toString(), body); return this; }
        @Override public Optional<byte[]> ifChanged(URI url) throws IOException {
            asked.add(url.toString());
            if (notModified && committed.contains(url.toString())) return Optional.empty();
            pending.add(url.toString());
            return Optional.of(get0(url));
        }
        @Override public byte[] get(URI url) throws IOException { asked.add(url.toString()); return get0(url); }
        private byte[] get0(URI url) throws IOException {
            byte[] body = pages.get(url.toString());
            if (body == null) throw new IOException("HTTP 404 from " + url.getHost());
            return body;
        }
        @Override public void commit() { committed.addAll(pending); pending.clear(); }
        @Override public void rollback() { pending.clear(); }
        long gets(URI url) { return asked.stream().filter(url.toString()::equals).count(); }
    }

    static final URI VDH = FanaticalIntake.product("vertical-drop-heroes-hd");

    static Fake fanatical() {
        return new Fake().put(FanaticalIntake.PROMOTIONS, "fanatical/all-promotions.json").put(FanaticalIntake.SITE, "fanatical/all.json")
                .put(VDH, "fanatical/vertical-drop-heroes-hd.json");
    }

    // ------------------------------------------------------------------ Fanatical

    @Test void bothListingsNameTheGiveawayGameAndNothingThatNeedsAPurchase() {
        var promoted = FanaticalIntake.promoted(resource("fanatical/all-promotions.json")).orElseThrow();
        assertEquals(Set.of("vertical-drop-heroes-hd"), promoted, "spend-to-get bonuses, coupons and mystery games are left out");
        assertEquals(Set.of("vertical-drop-heroes-hd"), FanaticalIntake.bannered(resource("fanatical/all.json")).orElseThrow());
        assertTrue(FanaticalIntake.promoted("{\"other\":[]}".getBytes()).isEmpty(), "another shape is refused");
    }

    @Test void theGamePageMakesASteamKeyCandidateWithFanaticalsExactWindow() {
        var c = FanaticalIntake.candidate(resource("fanatical/vertical-drop-heroes-hd.json"), "vertical-drop-heroes-hd", now).orElseThrow();
        assertEquals(FreebieFeed.Provider.FANATICAL, c.provider());
        assertEquals(FreebieStore.STEAM, c.store());
        assertEquals("Vertical Drop Heroes HD", c.title());
        assertEquals("$6.99", c.worth().orElseThrow());
        assertEquals(Instant.parse("2026-11-27T11:00:00Z"), c.startsAt().orElseThrow());
        assertEquals(Instant.parse("2026-11-28T23:59:00Z"), c.endsAt().orElseThrow());
        assertEquals("https://www.fanatical.com/en/game/vertical-drop-heroes-hd", c.claimPage().toString());
        assertEquals("https://store.steampowered.com/app/311480/", c.storePage().orElseThrow().toString());
        assertEquals(Optional.of("Digerati Distribution"), c.seller());
        assertTrue(c.instructions().contains("newsletter"), c.instructions());
        assertTrue(c.region().startsWith("Not claimable in AR"), c.region());
        assertTrue(c.image().orElseThrow().toString().startsWith("https://fanatical.imgix.net/product/original/868948c3"));
        assertEquals("vertical-drop-heroes-hd:" + Instant.parse("2026-11-27T11:00:00Z").getEpochSecond(), c.sourceId());
    }

    @Test void outsideItsWindowOrAtAPriceItIsNoCandidate() {
        byte[] page = resource("fanatical/vertical-drop-heroes-hd.json");
        assertTrue(FanaticalIntake.candidate(page, "vertical-drop-heroes-hd", Instant.parse("2026-11-27T10:59:00Z")).isEmpty(), "not started");
        assertTrue(FanaticalIntake.candidate(page, "vertical-drop-heroes-hd", Instant.parse("2026-11-28T23:59:00Z")).isEmpty(), "ended");
        byte[] paid = new String(page).replaceFirst("\"currentPrice\":\\s*\\{\\s*\"GBP\":\\s*0,\\s*\"USD\":\\s*0", "\"currentPrice\": {\"GBP\": 0, \"USD\": 99").getBytes();
        assertTrue(FanaticalIntake.candidate(paid, "vertical-drop-heroes-hd", now).isEmpty(), "on sale, not free");
        assertThrows(IllegalArgumentException.class, () -> FanaticalIntake.candidate(page, "another-game", now), "a page for another game");
    }

    @Test void aCheckReadsTheGamePageOnlyWhenAListingChanged() {
        var http = fanatical();
        var feed = new FanaticalFeed(Clock.fixed(now, ZoneOffset.UTC), http, new FreebieHttp.Calls());
        var first = feed.poll();
        assertEquals(FreebieFeed.State.COMPLETE, first.state(), first.problem());
        assertEquals(1, first.candidates().size());
        var second = feed.poll();
        assertEquals(FreebieFeed.State.COMPLETE, second.state());
        assertEquals(first.candidates(), second.candidates(), "an unchanged listing keeps the game present");
        assertEquals(1, http.gets(VDH), "the game page is not read again while nothing changed");
    }

    @Test void anUnreadableGamePageIsAPartialCheckSoNothingEndsByAbsence() {
        var http = fanatical();
        http.pages.remove(VDH.toString());
        var result = new FanaticalFeed(Clock.fixed(now, ZoneOffset.UTC), http, new FreebieHttp.Calls()).poll();
        assertEquals(FreebieFeed.State.PARTIAL, result.state());
        assertTrue(result.candidates().isEmpty());
    }

    @Test void aBrokenListingFailsTheCheck() {
        var http = fanatical().put(FanaticalIntake.SITE, "<html>".getBytes());
        var result = new FanaticalFeed(Clock.fixed(now, ZoneOffset.UTC), http, new FreebieHttp.Calls()).poll();
        assertEquals(FreebieFeed.State.FAILED, result.state());
        assertTrue(http.committed.isEmpty(), "nothing is remembered from a failed check");
    }

    // ------------------------------------------------------------------ GOG

    static final String SECTION = "0f1e2d3c-4b5a-4968-8776-655443322110";

    static Fake gog() {
        return new Fake().put(GogIntake.HOME_SECTIONS, "gog/home-sections.json").put(GogIntake.section(SECTION), "gog/giveaway-section.json")
                .put(GogIntake.catalog("Dead Age 2"), "gog/catalog-dead-age-2.json");
    }

    @Test void gogsHomePageGiveawayBecomesACandidateWithCatalogPriceAndArt() {
        var result = new GogFeed(Clock.fixed(now, ZoneOffset.UTC), gog(), new FreebieHttp.Calls()).poll();
        assertEquals(FreebieFeed.State.COMPLETE, result.state(), result.problem());
        var c = result.candidates().getFirst();
        assertEquals(FreebieFeed.Provider.GOG, c.provider());
        assertEquals(FreebieStore.GOG, c.store());
        assertEquals("Dead Age 2", c.title());
        assertEquals("$17.99", c.worth().orElseThrow());
        assertEquals(Instant.parse("2026-11-30T23:59:00Z"), c.endsAt().orElseThrow());
        assertEquals("https://www.gog.com/en/", c.claimPage().toString(), "claimed from the home page banner");
        assertEquals("https://www.gog.com/en/game/dead_age_2", c.sourcePage().toString());
        assertTrue(c.image().orElseThrow().toString().startsWith("https://images.gog-statics.com/"));
    }

    @Test void withoutAGiveawaySectionTheCheckIsCompleteAndEmpty() throws Exception {
        var page = new String(resource("gog/home-sections.json")).replace("GIVEAWAY_SECTION", "PROMO_BANNER_SECTION");
        var http = gog().put(GogIntake.HOME_SECTIONS, page.getBytes());
        var result = new GogFeed(Clock.fixed(now, ZoneOffset.UTC), http, new FreebieHttp.Calls()).poll();
        assertEquals(FreebieFeed.State.COMPLETE, result.state());
        assertTrue(result.candidates().isEmpty());
        assertEquals(0, http.gets(GogIntake.section(SECTION)));
    }

    @Test void anEndedGiveawayIsNoCandidateAndACatalogOutageStillPosts() {
        var later = new GogFeed(Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC), gog(), new FreebieHttp.Calls()).poll();
        assertTrue(later.candidates().isEmpty());
        var http = gog();
        http.pages.remove(GogIntake.catalog("Dead Age 2").toString());
        var bare = new GogFeed(Clock.fixed(now, ZoneOffset.UTC), http, new FreebieHttp.Calls()).poll();
        assertEquals(FreebieFeed.State.COMPLETE, bare.state());
        assertTrue(bare.candidates().getFirst().worth().isEmpty());
    }

    @Test void gogEndDatesAreReadLikeTheBrowserDoes() {
        assertEquals(Instant.parse("2026-11-30T23:59:00Z"), GogIntake.instant("2026-11-30T23:59:00Z").orElseThrow());
        assertEquals(Instant.parse("2026-11-30T22:59:00Z"), GogIntake.instant("2026-11-30T23:59:00+01:00").orElseThrow());
        assertEquals(Instant.parse("2026-11-30T23:59:00Z"), GogIntake.instant("2026-11-30T23:59:00").orElseThrow());
        assertTrue(GogIntake.instant("soon").isEmpty());
    }

    // ------------------------------------------------------------------ shared rules

    @Test void gamerPowersSteamKeyTitleIsTheSameGame() {
        assertTrue(FreebieIdentity.titlesRelated("Vertical Drop Heroes HD Steam Key Giveaway", "Vertical Drop Heroes HD"));
        assertTrue(FreebieIdentity.titlesRelated("Dead Age 2 (GOG) Giveaway", "Dead Age 2"));
        assertFalse(FreebieIdentity.titlesRelated("Steam Key Collector", "Collector"), "only a trailing \"Steam Key\" is dropped");
    }

    @Test void deliveryButtonsCarryEverySourcesOfferId() {
        for (String id : List.of("gamerpower:3859", "epic:0123456789abcdef", "steam:0123456789abcdef", "fanatical:0123456789abcdef",
                "gog:0123456789abcdef", "console:0123456789abcdef"))
            assertEquals(Optional.of(id), FreebieDeliveryAdmin.offer(FreebieDeliveryAdmin.token(id)), id);
        assertTrue(FreebieDeliveryAdmin.offer("x0123456789abcdef").isEmpty());
        assertTrue(FreebieDeliveryAdmin.offer("e0123").isEmpty());
    }
}
