package org.bunnys.bunnynexus.deals;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** PlayStation, Xbox and Nintendo deals, from the stores' responses captured 2026-11-27 (trimmed to the fields read). */
class DealConsoleTest {
    final Instant now = Instant.parse("2026-11-27T20:00:00Z");

    static byte[] resource(String name) {
        try (var input = DealConsoleTest.class.getResourceAsStream("/deals/" + name)) { return Objects.requireNonNull(input).readAllBytes(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @Test void xboxGamesOnSaleCarryTheirStarsRatingCountAndEnd() throws Exception {
        var page = DealConsoleIntake.xbox(resource("xbox-browse.json"));
        assertTrue(page.next().isPresent(), "the next page's token");
        var rdr2 = page.deals().stream().filter(d -> d.id().equals("xbox:9N2ZDN7NWQKV")).findFirst().orElseThrow();
        assertEquals("Red Dead Redemption 2", rdr2.title());
        assertEquals(1499, rdr2.saleCents());
        assertEquals(5999, rdr2.normalCents());
        assertEquals(75, rdr2.percentOff());
        assertEquals(44, rdr2.rating(), "4.4 stars, in tenths");
        assertEquals(33761, rdr2.reviews());
        assertEquals(Optional.of(Instant.parse("2026-12-03T09:59:59Z")), rdr2.endsAt());
        assertEquals("https://www.xbox.com/en-US/games/store/p/9N2ZDN7NWQKV", rdr2.link().toString());
        assertTrue(DealRules.qualifies(rdr2));
        assertEquals("rated 4.4/5", rdr2.ratingText());
        assertTrue(page.deals().stream().allMatch(d -> d.saleCents() < d.normalCents()));
        assertThrows(DealIntake.Invalid.class, () -> DealConsoleIntake.xbox("{\"other\":1}".getBytes()));
    }

    @Test void playstationRowsNeedTheirGamePageForStarsAndEnd() throws Exception {
        var rows = DealConsoleIntake.playstation(resource("playstation-deals.json"));
        var rdr2 = rows.stream().filter(r -> r.productId().equals("UP1004-CUSA03041_00-REDEMPTIONFULL02")).findFirst().orElseThrow();
        assertEquals(1499, rdr2.saleCents());
        assertEquals(5999, rdr2.normalCents());
        assertEquals(75, rdr2.percentOff());
        var rating = DealConsoleIntake.playstationRating(resource("playstation-product.html")).orElseThrow();
        assertEquals(48, rating.stars(), "4.76 stars, in tenths");
        assertEquals(503628, rating.count());
        var deal = rdr2.withRating(rating);
        assertEquals("https://store.playstation.com/en-us/product/UP1004-CUSA03041_00-REDEMPTIONFULL02", deal.link().toString());
        assertEquals(Optional.of(Instant.ofEpochMilli(1796111940000L)), deal.endsAt());
        assertTrue(DealRules.qualifies(deal));
        assertTrue(DealConsoleIntake.playstationRating("<html>no rating</html>".getBytes()).isEmpty());
    }

    @Test void nintendoKeepsDiscountedGamesAndBundlesButNotAddOns() throws Exception {
        var rows = DealConsoleIntake.nintendo(resource("nintendo-deals.html"));
        var mlb = rows.stream().filter(r -> r.nsuid().equals("70010000099939")).findFirst().orElseThrow();
        assertEquals(899, mlb.saleCents());
        assertEquals(5999, mlb.normalCents());
        assertEquals(85, mlb.percentOff());
        assertEquals("https://www.nintendo.com/us/store/products/mlb-the-show-26-switch/", mlb.link().toString());
        assertEquals(Optional.of(Instant.parse("2026-12-03T07:59:59Z")), mlb.ends());
        assertTrue(rows.stream().anyMatch(r -> r.nsuid().equals("70070000025275")), "a game bundle is a game");
        assertTrue(rows.stream().noneMatch(r -> r.title().contains("Season Pass")), "add-ons are not games");
        assertTrue(rows.stream().noneMatch(r -> r.nsuid().equals("70070000030830")), "not on sale");
        var deal = mlb.withRating(91, 4200);
        assertEquals("91% positive on Steam", deal.ratingText());
        assertTrue(DealRules.qualifies(deal));
        assertThrows(DealIntake.Invalid.class, () -> DealConsoleIntake.nintendo("<html></html>".getBytes()));
    }

    @Test void nintendoTitlesAreLookedUpOnSteamWithoutMarksOrSwitchEditions() {
        assertEquals("MLB The Show 26", DealRefresh.steamTitle("MLB® The Show™ 26"));
        assertEquals("Kirby and the Forgotten Land", DealRefresh.steamTitle("Kirby™ and the Forgotten Land – Nintendo Switch™ 2 Edition + Star-Crossed World"));
        assertEquals("Hades", DealRefresh.steamTitle("Hades"));
    }

    /** Steam and GOG from the 2026-11-23 captures, the consoles from 2026-11-27; counts game page reads. */
    static final class Source implements DealRefresh.Source {
        int playstationPages, steamLookups;
        @Override public DealFeed.Fetch steamSearch(String order) { return new DealFeed.Failed("off", Optional.empty()); }
        @Override public DealFeed.Fetch steamItems(List<Long> appIds) { return new DealFeed.Failed("off", Optional.empty()); }
        @Override public DealFeed.Fetch gog() { return new DealFeed.Failed("off", Optional.empty()); }
        @Override public boolean consoles() { return true; }
        @Override public DealFeed.Fetch xbox(Optional<String> next) {
            return next.isPresent() ? new DealFeed.Failed("HTTP 500", Optional.empty()) : new DealFeed.Ok(resource("xbox-browse.json"));
        }
        @Override public DealFeed.Fetch playstation() { return new DealFeed.Ok(resource("playstation-deals.json")); }
        @Override public DealFeed.Fetch playstationGame(String productId) { playstationPages++; return new DealFeed.Ok(resource("playstation-product.html")); }
        @Override public DealFeed.Fetch nintendo() { return new DealFeed.Ok(resource("nintendo-deals.html")); }
        @Override public Optional<DealRefresh.SteamScore> steamRating(String title, Optional<String> publisher) {
            steamLookups++;
            return title.equals("MLB The Show 26") ? Optional.of(new DealRefresh.SteamScore(91, 4200)) : Optional.empty();
        }
    }

    @Test void aRefreshAddsTheConsolesAndReadsEachGamePageOnce() {
        var source = new Source();
        List<DealSnapshot> published = new ArrayList<>();
        var refresh = new DealRefresh(source, snapshot -> {}, Clock.fixed(now, ZoneOffset.UTC),
                () -> published.isEmpty() ? DealSnapshot.EMPTY : published.getLast(), published::add);
        refresh.run();
        var snapshot = published.getLast();
        assertTrue(snapshot.deals().stream().anyMatch(d -> d.id().equals("xbox:9N2ZDN7NWQKV")), "Xbox, though its second page failed");
        assertTrue(snapshot.deals().stream().anyMatch(d -> d.id().equals("playstation:UP1004-CUSA03041_00-REDEMPTIONFULL02")));
        assertTrue(snapshot.deals().stream().anyMatch(d -> d.id().equals("nintendo:70010000099939")), "rated by Steam players");
        assertTrue(snapshot.deals().stream().allMatch(DealRules::qualifies));
        int pages = source.playstationPages, lookups = source.steamLookups;
        assertTrue(pages > 0 && pages <= DealRefresh.NEW_RATINGS_PER_REFRESH);
        assertTrue(pages <= 100, "only games past the discount bar");
        refresh.run();
        assertEquals(pages, source.playstationPages, "game pages are not read again within three days");
        assertEquals(lookups, source.steamLookups, "nor Steam asked again");
    }
}
