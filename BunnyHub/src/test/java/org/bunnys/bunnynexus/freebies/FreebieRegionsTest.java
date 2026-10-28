package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.freebies.FreebieRegions.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Country evidence: explicit lists can block; anything missing, malformed or stale stays unknown. */
class FreebieRegionsTest {
    final Instant now = Instant.parse("2026-10-24T16:00:00Z");

    FreebieRegions.Evidence observe(Document row) { return FreebieRegions.observe("egdata", now, row); }

    @Test void blacklistOnlyBlocksListedCountries() {
        var e = Optional.of(observe(new Document("countriesBlacklist", List.of("TW", "SA")).append("countriesWhitelist", null)));
        assertEquals(BLOCKED, FreebieRegions.status(e, "SA", false, now));
        assertEquals(UNKNOWN, FreebieRegions.status(e, "EG", false, now), "not listed is not confirmation");
        assertEquals(CONFIRMED_FREE, FreebieRegions.status(e, "US", true, now));
        assertEquals("Not claimable in: Saudi Arabia, Taiwan. Free in the checked US storefront. "
                + "Other regions: only the restrictions listed by the source are known.", FreebieRegions.describe(e, true, now));
    }

    @Test void whitelistExcludesOthersAndBlacklistWinsWithinOneResponse() {
        var e = Optional.of(observe(new Document("countriesWhitelist", List.of("US", "CA")).append("countriesBlacklist", List.of("CA"))));
        assertEquals(BLOCKED, FreebieRegions.status(e, "EG", false, now));
        assertEquals(BLOCKED, FreebieRegions.status(e, "CA", false, now));
        assertEquals(UNKNOWN, FreebieRegions.status(e, "US", false, now));
        assertTrue(FreebieRegions.describe(e, false, now).contains("Only claimable in: Canada, United States"));
    }

    @Test void emptyAbsentAndNullListsRecordNoExclusionsButNeverWorldwideAvailability() {
        for (var row : List.of(new Document(), new Document("countriesBlacklist", List.of()), new Document("countriesBlacklist", null))) {
            var e = Optional.of(observe(row));
            assertEquals(UNKNOWN, FreebieRegions.status(e, "EG", false, now));
            assertFalse(FreebieRegions.describe(e, true, now).toLowerCase(Locale.ROOT).contains("no restrictions"));
        }
        assertTrue(FreebieRegions.field(new Document(), "countriesBlacklist").isEmpty());
        assertEquals(Optional.of(List.of()), FreebieRegions.field(new Document("countriesBlacklist", List.of()), "countriesBlacklist"));
    }

    @Test void malformedValuesAndPlatformTokensAreUnknownNotBans() {
        for (Object bad : List.of("TW", List.of("IOS"), List.of(1), List.of("tw"), List.of("XX")))
            assertEquals(UNKNOWN, FreebieRegions.status(Optional.of(observe(new Document("countriesBlacklist", bad))), "TW", false, now), bad.toString());
        assertTrue(observe(new Document("countriesBlacklist", List.of("IOS"))).malformed());
        assertEquals("Regional availability not fully verified.", FreebieRegions.describe(
                Optional.of(observe(new Document("countriesBlacklist", "TW"))), false, now));
    }

    @Test void missingEvidenceFailedRequestsAndStaleDataStayUnknown() {
        assertEquals(UNKNOWN, FreebieRegions.status(Optional.empty(), "SA", false, now));
        assertEquals("Free in the checked US storefront. Regional availability not fully verified.",
                FreebieRegions.describe(Optional.empty(), true, now));
        var old = Optional.of(new FreebieRegions.Evidence("egdata", now.minus(Duration.ofHours(7)), Optional.of(List.of("SA")), Optional.empty(), false));
        assertEquals(STALE, FreebieRegions.status(old, "SA", false, now));
        assertTrue(FreebieRegions.blocked(old, now).isEmpty(), "stale bans are history, not current restrictions");
    }

    @Test void aBanForACountryWeObservedFreeIsAConflict() {
        var e = Optional.of(observe(new Document("countriesBlacklist", List.of("US"))));
        assertEquals(CONFLICT, FreebieRegions.status(e, "US", true, now));
    }

    @Test void evidenceRoundTripsThroughStorageDistinguishingAbsentFromEmpty() {
        var absent = observe(new Document());
        var empty = observe(new Document("countriesBlacklist", List.of()));
        assertEquals(absent, FreebieRegions.Evidence.read(absent.toDocument()).orElseThrow());
        assertEquals(empty, FreebieRegions.Evidence.read(empty.toDocument()).orElseThrow());
        assertNotEquals(absent, empty);
    }
}
