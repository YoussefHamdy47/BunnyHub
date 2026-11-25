package org.bunnys.bunnynexus.deals;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** No network: the DNS guard, the request URLs and the response handling. */
class DealFeedTest {
    final DealFeed feed = new DealFeed(Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC), "BunnyNexus-Deals/1.0 (Discord bot)");

    static Response response(int code, String retryAfter, byte[] body) {
        var builder = new Response.Builder().request(new Request.Builder().url(DealFeed.GOG).build()).protocol(Protocol.HTTP_1_1)
                .code(code).message("x").body(ResponseBody.create(body, null));
        if (retryAfter != null) builder.header("Retry-After", retryAfter);
        return builder.build();
    }

    @Test void onlyTheStoresOwnHostsResolve() {
        assertThrows(UnknownHostException.class, () -> DealFeed.lookup("localhost"));
        assertThrows(UnknownHostException.class, () -> DealFeed.lookup("www.cheapshark.com"));
        assertThrows(UnknownHostException.class, () -> DealFeed.lookup("store.steampowered.com.evil.example"));
        assertEquals(java.util.Set.of("store.steampowered.com", "api.steampowered.com", "catalog.gog.com"), DealFeed.HOSTS);
    }

    @Test void theRequestsAreTheDocumentedOnes() {
        assertEquals("https://store.steampowered.com/search/results/?specials=1&filter=topsellers&infinite=1&count=100&cc=us&l=english",
                DealFeed.steamSearchUrl("filter=topsellers"));
        assertThrows(IllegalArgumentException.class, () -> DealFeed.steamSearchUrl("sort_by=Price_ASC"));
        String items = DealFeed.steamItemsUrl(List.of(3480L, 3390L));
        assertEquals("api.steampowered.com", URI.create(items).getHost());
        String input = URLDecoder.decode(items.substring(items.indexOf("input_json=") + 11), StandardCharsets.UTF_8);
        assertTrue(input.contains("{\"appid\":3480},{\"appid\":3390}") && input.contains("\"country_code\":\"US\"") && input.contains("\"include_reviews\":true"));
        assertTrue(DealFeed.GOG.startsWith("https://catalog.gog.com/v1/catalog?") && DealFeed.GOG.contains("countryCode=US&currencyCode=USD"));
    }

    @Test void responsesBecomeFetches() throws Exception {
        assertEquals(new DealFeed.Failed("HTTP 429", Optional.of(Duration.ofSeconds(120))), feed.read(response(429, "120", new byte[0])));
        assertEquals(new DealFeed.Failed("HTTP 503", Optional.empty()), feed.read(response(503, "120", new byte[0])));
        assertEquals(new DealFeed.Failed("response too large", Optional.empty()), feed.read(response(200, null, new byte[DealIntake.MAX_BYTES + 1])));
        assertInstanceOf(DealFeed.Ok.class, feed.read(response(200, null, "{}".getBytes())));
    }

    @Test void itemDetailsTakeOneToAHundredApps() {
        List<Long> tooMany = new ArrayList<>();
        for (long i = 0; i < 101; i++) tooMany.add(i);
        assertThrows(IllegalArgumentException.class, () -> DealFeed.steamItemsUrl(List.of()));
        assertThrows(IllegalArgumentException.class, () -> DealFeed.steamItemsUrl(tooMany));
    }

    @Test void configDefaultsAndBadValues() {
        List<String> warnings = new ArrayList<>();
        assertEquals(new DealConfig(true, "BunnyNexus-Deals/1.0 (Discord bot)"), DealConfig.load(key -> null, warnings));
        assertTrue(warnings.isEmpty());
        var custom = DealConfig.load(key -> switch (key) {
            case "DEALS_ENABLED" -> " FALSE ";
            case "DEALS_CONTACT" -> "ops@example.com\nX-Evil: 1";
            default -> null;
        }, warnings);
        assertFalse(custom.enabled());
        assertEquals("BunnyNexus-Deals/1.0 (Discord bot)", custom.userAgent(), "a contact with a newline is ignored");
        assertEquals(1, warnings.size());
    }
}
