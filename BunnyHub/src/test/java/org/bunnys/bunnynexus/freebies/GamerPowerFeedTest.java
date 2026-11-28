package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class GamerPowerFeedTest {
    private final Instant now = Instant.parse("2026-10-13T00:00:00Z");

    /** Serves the captured feed with a Last-Modified, and answers 304 when asked "if modified since" that time. */
    static final class FakeGamerPower {
        final java.util.List<String> asked = new java.util.ArrayList<>();
        String modified = "Thu, 29 Oct 2026 12:00:00 GMT";
        okhttp3.OkHttpClient client() throws Exception {
            byte[] feed;
            try (var input = GamerPowerFeedTest.class.getResourceAsStream("/gamerpower/all-giveaways-2026-10-29.json")) { feed = input.readAllBytes(); }
            return new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
                var request = chain.request();
                String since = request.header("If-Modified-Since");
                asked.add(String.valueOf(since));
                var response = new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1);
                if (modified.equals(since)) return response.code(304).message("Not Modified").body(okhttp3.ResponseBody.create(new byte[0], null)).build();
                return response.code(200).message("OK").header("Last-Modified", modified)
                        .body(okhttp3.ResponseBody.create(feed, okhttp3.MediaType.get("application/json"))).build();
            }).build();
        }
    }

    @Test void anUnchangedFeedIsNotDownloadedAgainButReadTheSame() throws Exception {
        var server = new FakeGamerPower();
        var clock = java.time.Clock.fixed(Instant.parse("2026-10-29T17:40:00Z"), java.time.ZoneOffset.UTC);
        try (var feed = new GamerPowerFeed(clock, server.client())) {
            var first = feed.poll();
            assertEquals(FreebieFeed.State.COMPLETE, first.state(), first.problem());
            var second = feed.poll();
            assertEquals(java.util.List.of("null", "Thu, 29 Oct 2026 12:00:00 GMT"), server.asked, "the second check asks only if modified");
            assertEquals(first.state(), second.state());
            assertEquals(first.candidates(), second.candidates(), "the kept feed gives the same games");
            server.modified = "Thu, 29 Oct 2026 13:00:00 GMT";
            assertEquals(FreebieFeed.State.COMPLETE, feed.poll().state(), "changed: downloaded again");
        }
    }

    @Test void retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(Optional.of(Duration.ofMinutes(30)), GamerPowerFeed.retryAfter(" 1800 ", now));
        assertEquals(Optional.of(Duration.ofHours(1)), GamerPowerFeed.retryAfter("Tue, 13 Oct 2026 01:00:00 GMT", now));
    }

    @Test void retryAfterRetainsBoundAndRejectsMalformedOrPastValues() {
        for (String value : new String[] {"0", "-5", "+5", "1.5", "later", "", "9".repeat(200),
                "9999999999999999999999", "Tue, 13 Oct 2026 00:00:00 GMT", "Mon, 12 Oct 2026 23:00:00 GMT"})
            assertTrue(GamerPowerFeed.retryAfter(value, now).isEmpty(), value);
        assertTrue(GamerPowerFeed.retryAfter(null, now).isEmpty());
        assertEquals(Optional.of(Duration.ofHours(6)), GamerPowerFeed.retryAfter("999999", now));
        assertEquals(Optional.of(Duration.ofHours(6)), GamerPowerFeed.retryAfter("Wed, 14 Oct 2026 00:00:00 GMT", now));
    }
}
