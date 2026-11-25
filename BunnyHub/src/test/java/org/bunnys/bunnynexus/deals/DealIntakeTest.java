package org.bunnys.bunnynexus.deals;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DealIntakeTest {
    static byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    @Test void steamSearchPagesGiveAppIdsInOrder() throws Exception {
        var ids = DealIntake.steamSearch(DealFixtures.steamSearch("topsellers"), 0);
        assertEquals(100, ids.size());
        assertEquals(3321460L, ids.getFirst(), "Crimson Desert Enhanced tops the page");
        var big = DealIntake.steamSearch(DealFixtures.steamSearch("topsellers"), 75);
        assertFalse(big.contains(3321460L), "Crimson Desert is only 20% off, so it is not looked up");
        assertTrue(ids.containsAll(big) && !big.isEmpty() && big.size() < 100);
        assertEquals(List.of(7L, 8L), DealIntake.steamSearch(bytes("{\"success\":1,\"results_html\":\"<a data-ds-appid=\\\"7\\\"><div data-discount=\\\"80\\\"></div></a>"
                + "<a data-ds-appid=\\\"9\\\"><div data-discount=\\\"10\\\"></div></a><a data-ds-appid=\\\"8\\\"></a>\"}"), 75),
                "a row without a shown discount is kept for the details to decide");
        assertThrows(DealIntake.Invalid.class, () -> DealIntake.steamSearch(bytes("{\"success\":2,\"results_html\":\"\"}"), 75));
    }

    @Test void steamItemsCarryPriceReviewsAndTheSaleEnd() throws Exception {
        var deals = DealIntake.steamItems(DealFixtures.steamItems("topsellers"));
        var crimson = deals.stream().filter(d -> d.id().equals("steam:3321460")).findFirst().orElseThrow();
        assertEquals("Crimson Desert Enhanced", crimson.title());
        assertEquals(URI.create("https://store.steampowered.com/app/3321460/"), crimson.link());
        assertEquals(5599, crimson.saleCents());
        assertEquals(6999, crimson.normalCents());
        assertEquals(20, crimson.percentOff());
        assertEquals(83, crimson.rating());
        assertEquals(120395, crimson.reviews());
        assertEquals(Optional.of(Instant.ofEpochSecond(1796025600)), crimson.endsAt());
        assertTrue(deals.stream().allMatch(d -> d.store() == DealStore.STEAM && d.link().getHost().equals("store.steampowered.com")));
    }

    @Test void gogProductsLinkToGogItself() throws Exception {
        var deals = DealIntake.gog(DealFixtures.gog());
        var morrowind = deals.stream().filter(d -> d.id().equals("gog:1440163901")).findFirst().orElseThrow();
        assertEquals("The Elder Scrolls III: Morrowind GOTY Edition", morrowind.title());
        assertEquals(URI.create("https://www.gog.com/en/game/the_elder_scrolls_iii_morrowind_goty_edition"), morrowind.link());
        assertEquals(375, morrowind.saleCents());
        assertEquals(1499, morrowind.normalCents());
        assertEquals(75, morrowind.percentOff());
        assertEquals(47, morrowind.rating());
        assertEquals("rated 4.7/5", morrowind.ratingText());
        assertTrue(morrowind.endsAt().isEmpty());
        assertTrue(deals.stream().allMatch(d -> d.link().getHost().equals("www.gog.com")));
    }

    @Test void aGogLinkToAnotherSiteIsNeverTaken() throws Exception {
        String product = "{\"id\":\"1\",\"title\":\"X\",\"productType\":\"game\",\"storeLink\":\"%s\",\"reviewsRating\":47,\"reviewsCount\":500,"
                + "\"price\":{\"finalMoney\":{\"amount\":\"1.99\",\"currency\":\"USD\"},\"baseMoney\":{\"amount\":\"19.99\",\"currency\":\"USD\"}}}";
        String good = product.formatted("https://www.gog.com/en/game/x"), evil = product.formatted("https://evil.example/game/x");
        assertEquals(2, DealIntake.gog(bytes("{\"products\":[" + good + "," + good + "," + evil + "]}")).size());
        assertThrows(DealIntake.Invalid.class, () -> DealIntake.gog(bytes("{\"products\":[" + evil + "," + evil + "," + good + "]}")),
                "mostly unreadable fails the store");
    }

    @Test void moneyIsWholeCents() {
        assertEquals(199, DealIntake.cents("1.99"));
        assertEquals(5, DealIntake.cents("0.05"));
        assertEquals(1000, DealIntake.cents("10"));
    }

    @Test void unusableBodiesFail() {
        assertThrows(DealIntake.Invalid.class, () -> DealIntake.steamItems(bytes("[]")));
        assertThrows(DealIntake.Invalid.class, () -> DealIntake.gog(bytes("{\"nope\":1}")));
        assertThrows(DealIntake.Invalid.class, () -> DealIntake.gog(new byte[DealIntake.MAX_BYTES + 1]));
        assertThrows(DealIntake.Invalid.class, () -> DealIntake.steamSearch(null, 75));
    }
}
