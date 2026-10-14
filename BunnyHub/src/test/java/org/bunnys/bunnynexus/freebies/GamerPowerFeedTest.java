package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class GamerPowerFeedTest {
    private final Instant now = Instant.parse("2026-10-13T00:00:00Z");

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
