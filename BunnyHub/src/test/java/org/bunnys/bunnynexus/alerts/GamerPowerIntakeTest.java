package org.bunnys.bunnynexus.alerts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake.*;

class GamerPowerIntakeTest {
    @Test void capturedProviderResponseMatchesTheObservedContractWithoutInventingExpiry() throws Exception {
        try (var input = getClass().getResourceAsStream("/gamerpower/live-games-2026-09-21.json")) {
            var batch = new GamerPowerIntake().decode(200, input.readAllBytes(), Instant.parse("2026-09-21T13:55:50Z"));
            assertEquals(State.CANDIDATES, batch.state()); assertEquals(20, batch.candidates().size());
            assertEquals(0, batch.rejectedItems()); assertEquals(9, batch.candidates().stream().filter(c -> c.endsLocal().isEmpty()).count());
            assertTrue(batch.candidates().stream().allMatch(c -> c.attribution().equals(ATTRIBUTION)));
        }
    }
    final GamerPowerIntake intake = new GamerPowerIntake();
    final Instant fetched = Instant.parse("2026-09-21T00:00:00Z");
    String fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/gamerpower/candidate.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    Batch decode(String text) { return intake.decode(200, text.getBytes(StandardCharsets.UTF_8), fetched); }
    @Test void candidateRetainsSourceAttributionWithoutInventingMarketPriceOrTimezone() throws Exception {
        var result = decode(fixture()); assertEquals(State.CANDIDATES, result.state());
        var c = result.candidates().getFirst(); assertEquals("123", c.sourceItemId());
        assertEquals("PC, Steam", c.platforms()); assertTrue(c.endsLocal().isEmpty());
        assertEquals(ATTRIBUTION, c.attribution()); assertEquals(fetched, c.fetchedAt());
        assertEquals(10, c.publishedLocal().getHour());
        assertThrows(UnsupportedOperationException.class, () -> result.candidates().clear());
    }
    @Test void emptyArrayIsNotConfusedWithHttpOrEnvelopeFailures() {
        assertEquals(State.EMPTY, decode("[]").state());
        for (int status : new int[]{404, 429, 500})
            assertEquals(Problem.HTTP_STATUS, intake.decode(status, "[]".getBytes(StandardCharsets.UTF_8), fetched).problem());
        assertEquals(State.FAILED, intake.decode(201, "[]".getBytes(StandardCharsets.UTF_8), fetched).state());
        assertEquals(State.FAILED, decode("{\"status\":201,\"status_message\":\"No active giveaways\"}").state());
    }
    @Test void capturedEmptyAndNotFoundEnvelopesHaveDifferentOutcomes() throws Exception {
        byte[] empty, missing;
        try (var stream = getClass().getResourceAsStream("/gamerpower/ps4-games-2026-09-22.json")) { empty = stream.readAllBytes(); }
        try (var stream = getClass().getResourceAsStream("/gamerpower/missing-id-2026-09-22.json")) { missing = stream.readAllBytes(); }
        var batch = intake.decode(201, empty, fetched);
        assertEquals(State.EMPTY, batch.state()); assertEquals(Problem.NONE, batch.problem()); assertTrue(batch.candidates().isEmpty());
        assertEquals(State.FAILED, intake.decode(404, missing, fetched).state());
        assertEquals(State.FAILED, intake.decode(201, missing, fetched).state());
        assertEquals(State.FAILED, intake.decode(200, empty, fetched).state());
    }
    @Test void noResultsEnvelopeRejectsDriftDuplicatesTruncationAndExtraContent() throws Exception {
        String valid;
        try (var stream = getClass().getResourceAsStream("/gamerpower/ps4-games-2026-09-22.json")) {
            valid = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String invalid : new String[]{valid.replace("\"status\":0", "\"status\":1"),
                valid.replace("\"status\":0", "\"status\":\"0\""), valid.replace("\"status\":0", "\"status\":0,\"status\":0"),
                valid.replace("\"status\":0,", ""), valid.replace("No active", "Error retrieving"),
                valid.substring(0, valid.length() - 1), valid + "{}", valid.replace("}", ",\"items\":[]}"),
                "{}", "[]", "null"}) {
            assertEquals(State.FAILED, intake.decode(201, invalid.getBytes(StandardCharsets.UTF_8), fetched).state());
        }
    }
    @Test void capturedMobileListingRemainsSourceMetadataWithoutStoreEligibility() throws Exception {
        try (var stream = getClass().getResourceAsStream("/gamerpower/ios-games-2026-09-22.json")) {
            var batch = intake.decode(200, stream.readAllBytes(), fetched);
            assertEquals(State.CANDIDATES, batch.state()); assertEquals(1, batch.candidates().size());
            assertTrue(batch.candidates().getFirst().platforms().contains("iOS"));
            assertEquals(ATTRIBUTION, batch.candidates().getFirst().attribution());
        }
    }
    @Test void partialRowsCannotMasqueradeAsCompleteEmptyFeed() throws Exception {
        var batch = decode(fixture().strip().replaceFirst("\\[", "[null,{},"));
        assertEquals(State.PARTIAL, batch.state()); assertEquals(2, batch.rejectedItems()); assertEquals(1, batch.candidates().size());
        assertEquals(State.PARTIAL, decode("[null,{}]").state());
    }
    @Test void truncationTrailingDataAndDuplicateFieldsDiscardAllCandidates() throws Exception {
        String valid = fixture().strip();
        for (String invalid : new String[]{valid.substring(0, valid.length() - 1), valid + "[]",
                valid.replace("\"id\": 123", "\"id\":123,\"id\":456")}) {
            var result = decode(invalid); assertEquals(State.FAILED, result.state()); assertTrue(result.candidates().isEmpty());
        }
    }
    @Test void duplicateSourceIdsFailInsteadOfInventingAnotherCampaign() throws Exception {
        String item = fixture().strip(); item = item.substring(1, item.length() - 1);
        assertEquals(Problem.DUPLICATE_ID, decode("[" + item + "," + item + "]").problem());
    }
    @Test void duplicateIdentityInMalformedRowQuarantinesWholeBatchInEitherOrder() throws Exception {
        String array = fixture().strip(); String valid = array.substring(1, array.length() - 1);
        String malformed = valid.replace("\"Active\"", "null");
        for (String body : new String[]{"[" + valid + "," + malformed + "]", "[" + malformed + "," + valid + "]"}) {
            var result = decode(body); assertEquals(Problem.DUPLICATE_ID, result.problem()); assertTrue(result.candidates().isEmpty());
        }
    }
    @Test void unusedProviderMetadataDoesNotChangeCandidateOrBypassStructureChecks() throws Exception {
        // "worth" is now retained (display only); an unknown field alongside it must still be ignored.
        String extended = fixture().replace("\"worth\": \"$19.99\"", "\"worth\": \"$19.99\", \"unused\": {\"text\":\"" + "x".repeat(8000) + "\"}");
        assertEquals(decode(fixture()).candidates(), decode(extended).candidates());
        assertEquals(java.util.Optional.of("$19.99"), decode(fixture()).candidates().getFirst().worth());
        assertEquals(State.FAILED, decode(extended.substring(0, extended.length() / 2)).state());
    }
    @Test void payloadItemStringAndNestingLimitsFailClosed() throws Exception {
        assertEquals(Problem.TOO_LARGE, intake.decode(200, new byte[MAX_BYTES + 1], fetched).problem());
        assertEquals(Problem.TOO_MANY_ITEMS, decode("[" + "null,".repeat(MAX_ITEMS) + "null]").problem());
        assertEquals(State.FAILED, decode("[".repeat(20) + "0" + "]".repeat(20)).state());
        assertEquals(State.FAILED, decode(fixture().replace("Synthetic fixture game", "x".repeat(20_000))).state());
    }
    @Test void unsafeAndUnapprovedSourceLinksRemainRejected() throws Exception {
        for (String link : new String[]{"http://www.gamerpower.com/open/a", "https://localhost/open/a",
                "https://www.gamerpower.com.evil.example/open/a", "https://user@www.gamerpower.com/open/a",
                "https://www.gamerpower.com:443/open/a", "https://www.gamerpower.com/open/a?redirect=evil",
                "https://www.gamerpower.com/open/%2e%2e/", "https://127.0.0.1/open/a"}) {
            var result = decode(fixture().replace("https://www.gamerpower.com/open/synthetic-giveaway", link));
            assertEquals(State.PARTIAL, result.state()); assertTrue(result.candidates().isEmpty());
        }
    }
    @Test void invalidDatesInactiveOrNonGameRowsArePartialRatherThanWithdrawals() throws Exception {
        for (String text : new String[]{fixture().replace("2026-09-20", "2026-02-30"),
                fixture().replace("N/A", "2026-09-19 10:00:00"), fixture().replace("Active", "Expired"),
                fixture().replace("\"Game\"", "\"Loot\"")}) assertEquals(State.PARTIAL, decode(text).state());
        assertTrue(decode(fixture().replace("N/A", "2026-09-25 10:00:00")).candidates().getFirst().endsLocal().isPresent());
    }
}
