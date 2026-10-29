package org.bunnys.bunnynexus.freebies;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteamIntakeTest {
    final SteamIntake intake = new SteamIntake();
    final Instant now = Instant.parse("2026-10-29T17:40:00Z");

    byte[] resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/steam/" + name)) { return input.readAllBytes(); }
    }
    String items() throws Exception { return new String(resource("items-2026-10-29.json"), StandardCharsets.UTF_8); }
    FreebieFeed.Result decode(String text) {
        return intake.decode(text.getBytes(StandardCharsets.UTF_8), List.of(785890L, 5340370L, 4693960L), true, now);
    }

    /** Captured when Hexologic was free to keep on Steam but missing from GamerPower's cached game list. */
    @Test void capturedSearchListsTheFreeToKeepApps() throws Exception {
        var search = intake.search(resource("search-2026-10-29.json")).orElseThrow();
        assertEquals(List.of(785890L, 4693960L), search.appIds());
        assertTrue(search.complete());
    }

    @Test void onlyTheFreeToKeepGameBecomesACandidateWithSteamsExactEnd() throws Exception {
        var result = decode(items());
        assertEquals(FreebieFeed.State.COMPLETE, result.state(), result.problem());
        assertEquals(1, result.candidates().size(), "the two free DLCs are skipped, not rejected");
        var c = result.candidates().getFirst();
        assertEquals(FreebieFeed.Provider.STEAM, c.provider());
        assertEquals("steam:785890:1901258", c.identity());
        assertEquals(FreebieStore.STEAM, c.store());
        assertEquals("Hexologic", c.title());
        assertEquals(Instant.parse("2026-10-31T17:00:00Z"), c.endsAt().orElseThrow());
        assertEquals("$4.99", c.worth().orElseThrow());
        assertEquals("https://store.steampowered.com/app/785890/", c.claimPage().toString());
        assertEquals("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/785890/header.jpg", c.image().orElseThrow().toString());
        assertTrue(c.description().startsWith("Immerse yourself"));
    }

    @Test void anythingShortOfStrictFreeToKeepIsNeverACandidate() throws Exception {
        String text = items();
        // A paid or partly discounted package, or one Steam does not mark free to keep, is not a giveaway.
        assertTrue(decode(text.replace("\"is_free_to_keep\": true", "\"is_free_to_keep\": false")).candidates().isEmpty());
        assertEquals(FreebieFeed.State.PARTIAL, decode(text.replace("\"discount_pct\": 100", "\"discount_pct\": 90")).state());
        assertTrue(decode(text.replace("\"discount_pct\": 100", "\"discount_pct\": 90")).candidates().isEmpty());
        // Already over: rejected, so the feed is partial and cannot end anything.
        var ended = intake.decode(text.getBytes(StandardCharsets.UTF_8), List.of(785890L, 5340370L, 4693960L), true,
                Instant.parse("2026-11-01T00:00:00Z"));
        assertEquals(FreebieFeed.State.PARTIAL, ended.state()); assertTrue(ended.candidates().isEmpty());
    }

    @Test void missingOrUnrequestedItemsNeverLookComplete() throws Exception {
        String text = items();
        var missing = intake.decode(text.getBytes(StandardCharsets.UTF_8), List.of(785890L, 5340370L, 4693960L, 1L), true, now);
        assertEquals(FreebieFeed.State.PARTIAL, missing.state());
        assertEquals(1, missing.candidates().size());
        assertEquals(FreebieFeed.State.FAILED, intake.decode(text.getBytes(StandardCharsets.UTF_8), List.of(785890L), true, now).state(),
                "an item that was not asked for is an unexpected response");
        assertEquals(FreebieFeed.State.PARTIAL, intake.decode(text.getBytes(StandardCharsets.UTF_8),
                List.of(785890L, 5340370L, 4693960L), false, now).state(), "a search with more pages is never complete");
        assertEquals(FreebieFeed.State.FAILED, decode("[]").state());
        assertEquals(FreebieFeed.State.FAILED, decode("{\"response\":{}} {}").state());
    }

    @Test void searchEnvelopeIsStrict() {
        assertTrue(intake.search("{\"success\":2,\"results_html\":\"\",\"total_count\":0}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(intake.search("{\"success\":1,\"total_count\":0}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        var none = intake.search("{\"success\":1,\"results_html\":\"\",\"total_count\":0,\"start\":0}".getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertTrue(none.appIds().isEmpty()); assertTrue(none.complete());
        var more = intake.search(("{\"success\":1,\"total_count\":5,\"results_html\":"
                + "\"<a href=\\\"x\\\" data-ds-appid=\\\"10\\\">\"}").getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertEquals(List.of(10L), more.appIds()); assertFalse(more.complete());
        var bundle = intake.search(("{\"success\":1,\"total_count\":1,\"results_html\":"
                + "\"<a href=\\\"x\\\" data-ds-appid=\\\"10,11\\\">\"}").getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertTrue(bundle.appIds().isEmpty(), "a multi-app package row is not a single game");
    }

    @Test void itemsRequestStaysOnSteamsApiHost() {
        var uri = SteamIntake.items(List.of(785890L));
        assertEquals("api.steampowered.com", uri.getHost());
        assertTrue(uri.getRawQuery().startsWith("input_json="));
        assertTrue(java.net.URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8).contains("{\"appid\":785890}"));
    }
}
