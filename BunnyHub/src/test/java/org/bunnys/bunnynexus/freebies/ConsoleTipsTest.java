package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Which headlines become tips, and what the Microsoft Store price check accepts. Pure. */
class ConsoleTipsTest {
    static byte[] fixture(String name) throws IOException {
        try (InputStream in = ConsoleTipsTest.class.getResourceAsStream("/consoles/" + name)) { return in.readAllBytes(); }
    }

    @Test void giveawayHeadlinesAreTipsAndEverythingElseIsNot() {
        for (String yes : List.of(
                "PS5 Game Free to Keep for a Limited Time, No PS Plus Required",
                "Xbox Makes 2021 Game Free For Limited Time, No Game Pass Required",
                "Graveyard Keeper Is Free to Keep on PS5 and PS4 Right Now",
                "Grab This Xbox Series X|S Game for Free Before It's Gone",
                "Claim PS5 Game Buffet Boss for Free Without Any Subscriptions"))
            assertTrue(ConsoleHeadlines.giveaway(yes), yes);
        for (String no : List.of(
                "PS Plus Essential Games for December 2026 Announced",
                "Free Play Days: Try These Xbox Games For Free (November 26-29)",
                "Helldivers 2 Free to Keep for PS Plus Extra Members",
                "Marathon Free Trial Weekend Starts Today on PS5",
                "Warframe Free-to-Play Update Out Now",
                "Free PS5 Upgrade for Ghost of Yotei Available Now",
                "Feature: 80+ PS5, PS4 Games to Buy in PS Store's Updated Black Friday Sale"))
            assertFalse(ConsoleHeadlines.giveaway(no), no);
    }

    @Test void todaysRealNewsHasNoFalseTips() throws IOException {
        var source = ConsoleHeadlines.SOURCES.get(1);
        assertEquals("Push Square", source.name());
        assertEquals(List.of(), ConsoleHeadlines.parse(fixture("pushsquare.xml"), source), "25 real headlines, none a giveaway");
        assertEquals(List.of(), ConsoleHeadlines.parse(fixture("purexbox.xml"), ConsoleHeadlines.SOURCES.get(2)));
    }

    @Test void aHeadlineOnTheFeedsOwnSiteIsReadWithItsDate() {
        String rss = "<?xml version=\"1.0\"?><rss><channel><item><title>PS5 Game Free to Keep for a Limited Time, No PS Plus Required</title>"
                + "<link>https://www.pushsquare.com/news/2026/12/free-game</link><pubDate>Tue, 01 Dec 2026 10:00:00 GMT</pubDate></item>"
                + "<item><title>Grab This PS5 Game for Free Now</title><link>https://evil.example/news</link>"
                + "<pubDate>Tue, 01 Dec 2026 10:00:00 GMT</pubDate></item></channel></rss>";
        var found = ConsoleHeadlines.parse(rss.getBytes(), ConsoleHeadlines.SOURCES.get(1));
        assertEquals(1, found.size(), "a link to another site is ignored");
        assertEquals(Instant.parse("2026-12-01T10:00:00Z"), found.getFirst().published());
        assertEquals(FreebieStore.PLAYSTATION, found.getFirst().source().store());
    }

    @Test void storeLinksGiveTheProductId() {
        assertEquals(Optional.of("9NV8BWGH15GN"), XboxStore.productId(URI.create("https://www.xbox.com/en-US/games/store/sniper-ghost-warrior-contracts-2/9NV8BWGH15GN")));
        assertEquals(Optional.of("9NV8BWGH15GN"), XboxStore.productId(URI.create("https://www.microsoft.com/en-us/p/sniper/9nv8bwgh15gn")));
        assertEquals(Optional.empty(), XboxStore.productId(URI.create("https://evil.example/games/store/x/9NV8BWGH15GN")));
        assertEquals(Optional.empty(), XboxStore.productId(URI.create("http://www.xbox.com/en-US/games/store/x/9NV8BWGH15GN")));
    }

    @Test void onlyTheFullGamesPublicPriceCounts() throws IOException {
        // The real catalog entry: $3.99 (normally $39.99), with many $0 entries for Game Pass, trials and licences.
        var onSale = XboxStore.price(fixture("catalog-2-products.json"), "9NV8BWGH15GN").orElseThrow();
        assertEquals(3.99, onSale.listPrice());
        assertEquals(39.99, onSale.msrp());
        assertFalse(onSale.freeToKeep(), "the $0 Game Pass and licence entries never make it free");
        assertEquals("Sniper Ghost Warrior Contracts 2", onSale.title());
        assertEquals(Optional.of(Instant.parse("2026-12-03T09:59:59Z")), onSale.ends());
        assertTrue(onSale.image().orElseThrow().toString().startsWith("https://store-images.s-microsoft.com/"));

        var free = XboxStore.price(new String(fixture("catalog-2-products.json")).replace("\"ListPrice\":3.99", "\"ListPrice\":0").getBytes(),
                "9NV8BWGH15GN").orElseThrow();
        assertTrue(free.freeToKeep());
        assertEquals(Optional.empty(), XboxStore.price(fixture("catalog-2-products.json"), "9ZZZZZZZZZZZ"));
    }

    @Test void typedEndTimesAreUtc() {
        assertEquals(Optional.of(Instant.parse("2026-12-03T23:59:00Z")), ConsoleTips.ends("2026-12-03"));
        assertEquals(Optional.of(Instant.parse("2026-12-03T16:00:00Z")), ConsoleTips.ends(" 2026-12-03 16:00 "));
        assertEquals(Optional.empty(), ConsoleTips.ends(""));
        assertThrows(RuntimeException.class, () -> ConsoleTips.ends("next tuesday"));
    }
}
