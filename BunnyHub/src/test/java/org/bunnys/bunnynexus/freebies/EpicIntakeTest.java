package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EpicIntakeTest {
    static final Instant NOW = Instant.parse("2026-10-13T08:59:53Z");
    static byte[] captured() throws Exception {
        try (var input = EpicIntakeTest.class.getResourceAsStream("/epic/captured-2026-10-13-US.json")) { return input.readAllBytes(); }
    }
    static Document root() throws Exception { return Document.parse(new String(captured(), StandardCharsets.UTF_8)); }
    static Document search(Document root) { return root.get("data", Document.class).get("Catalog", Document.class).get("searchStore", Document.class); }
    static Document active(Document root) { return search(root).getList("elements", Document.class).stream().filter(d -> d.getString("title").equals("Out of Sight")).findFirst().orElseThrow(); }
    static FreebieFeed.Result decode(Document root) { return new EpicIntake().decode(root.toJson().getBytes(StandardCharsets.UTF_8), NOW); }
    static FreebieFeed.Candidate candidate() throws Exception { return new EpicIntake().decode(captured(), NOW).candidates().getFirst(); }
    static Document promotion(Document row) { return row.get("promotions", Document.class).getList("promotionalOffers", Document.class).getFirst().getList("promotionalOffers", Document.class).getFirst(); }

    @Test void capturedResponseHasTwoCurrentFreeBaseGames() throws Exception {
        var result = new EpicIntake().decode(captured(), NOW);
        assertEquals(FreebieFeed.State.COMPLETE, result.state());
        assertEquals(List.of("Out of Sight", "TerraScape"), result.candidates().stream().map(FreebieFeed.Candidate::title).toList());
        assertEquals(Instant.parse("2026-10-15T15:00:00Z"), result.candidates().getFirst().endsAt().orElseThrow());
        assertTrue(result.candidates().getFirst().region().startsWith("US"));
    }
    // Every mutation below is synthetic edge-case data, not an additional captured provider response.
    @Test void upcomingAndExpiredWindowsNeverBecomeCandidates() throws Exception {
        var root = root(); promotion(active(root)).put("startDate", "2026-10-14T15:00:00Z");
        assertEquals(1, decode(root).candidates().size());
        promotion(active(root)).put("startDate", "2026-10-01T15:00:00Z");
        promotion(active(root)).put("endDate", NOW.toString());
        assertEquals(1, decode(root).candidates().size());
    }
    @Test void discountsVouchersAndPermanentFreeGamesAreExcluded() throws Exception {
        for (String field : List.of("discountPrice", "voucherDiscount")) {
            var root = root(); active(root).get("price", Document.class).get("totalPrice", Document.class).put(field, 100);
            assertEquals(1, decode(root).candidates().size());
        }
        var root = root(); promotion(active(root)).get("discountSetting", Document.class).put("discountPercentage", 50);
        assertEquals(1, decode(root).candidates().size());
        // A free-to-play listing with no timed promotion of its own is just the store's free catalogue.
        root = root(); active(root).get("price", Document.class).get("totalPrice", Document.class).put("originalPrice", 0);
        active(root).get("promotions", Document.class).put("promotionalOffers", List.of());
        assertEquals(1, decode(root).candidates().size());
        // ...and a "promotion" running far longer than a giveaway is not one either.
        root = root(); active(root).get("price", Document.class).get("totalPrice", Document.class).put("originalPrice", 0);
        promotion(active(root)).put("endDate", "2027-01-15T15:00:00Z");
        assertEquals(1, decode(root).candidates().size());
    }

    static final Instant RELEASE = Instant.parse("2026-11-26T16:00:30Z");
    static byte[] november() throws Exception {
        try (var input = EpicIntakeTest.class.getResourceAsStream("/epic/captured-2026-11-26-US.json")) { return input.readAllBytes(); }
    }

    @Test void aTimedBonusForAnAlreadyFreeGameIsPartOfTheRelease() throws Exception {
        var result = new EpicIntake().decode(november(), RELEASE);
        assertEquals(FreebieFeed.State.COMPLETE, result.state());
        assertEquals(List.of("Firestone – Idle Clicker Online RPG", "Bread and Fred"), result.candidates().stream().map(FreebieFeed.Candidate::title).toList(),
                "paid games still on sale, add-ons and bundles stay out");
        var firestone = result.candidates().getFirst();
        var bread = result.candidates().get(1);
        // Labelled as the bonus it is, never as a paid game turned free.
        assertEquals(Optional.empty(), firestone.worth());
        assertEquals(FreebieOffer.BONUS_STEPS, firestone.instructions());
        assertEquals(Optional.of("$14.99"), bread.worth());
        assertEquals("", bread.instructions());
        // Same promotion window, so the same release group: one post for both.
        assertEquals(bread.startsAt(), firestone.startsAt());
        assertEquals(FreebieFields.groupKey(FreebieStore.EPIC, bread.startsAt()), FreebieFields.groupKey(FreebieStore.EPIC, firestone.startsAt()));
        assertNotEquals(bread.identity(), firestone.identity());
        assertEquals(java.net.URI.create("https://store.epicgames.com/en-US/p/firestone-online-idle-rpg-bfd04b"), firestone.claimPage());
    }
    @Test void epicGamesCarryTheirListPrice() throws Exception {
        var candidates = decode(root()).candidates();
        assertFalse(candidates.isEmpty());
        candidates.forEach(c -> assertTrue(c.worth().orElse("").matches("\\$[0-9]+\\.[0-9]{2}"), c.title() + " " + c.worth()));
        assertEquals("$19.99", EpicIntake.usd(1999));
        assertEquals("$3.99", EpicIntake.usd(399));
        assertEquals("$0.05", EpicIntake.usd(5));
        assertEquals("$1000.00", EpicIntake.usd(100000));
    }
    @Test void dlcBundlesAndCodeRedemptionAreExcluded() throws Exception {
        for (String type : List.of("DLC", "ADD_ON", "BUNDLE", "EDITION")) {
            var root = root(); active(root).put("offerType", type); assertEquals(1, decode(root).candidates().size());
        }
        var root = root(); active(root).put("isCodeRedemptionOnly", true); assertEquals(1, decode(root).candidates().size());
    }
    @Test void regionalExclusionsAreNotGlobalAvailability() throws Exception {
        var root = root(); active(root).put("countriesBlacklist", List.of("US")); assertEquals(1, decode(root).candidates().size());
        root = root(); active(root).put("countriesWhitelist", List.of("EG")); assertEquals(1, decode(root).candidates().size());
    }
    @Test void missingPartialTruncatedAndErrorEnvelopesCannotEndOffers() throws Exception {
        var root = root(); search(root).get("paging", Document.class).put("total", 99); assertEquals(FreebieFeed.State.FAILED, decode(root).state());
        // Item errors next to valid data: the games are still found, but the feed is never "complete".
        root = root(); root.put("errors", List.of(new Document("message", "price lookup failed")));
        var degraded = decode(root);
        assertEquals(FreebieFeed.State.PARTIAL, degraded.state());
        assertEquals(decode(root()).candidates().size(), degraded.candidates().size(), "same games as a clean response");
        assertFalse(degraded.candidates().isEmpty());
        // Errors with no data at all still fail.
        assertEquals(FreebieFeed.State.FAILED, new EpicIntake().decode("{\"errors\":[{\"message\":\"x\"}]}".getBytes(StandardCharsets.UTF_8), NOW).state());
        assertEquals(FreebieFeed.State.FAILED, new EpicIntake().decode("{}".getBytes(StandardCharsets.UTF_8), NOW).state());
        assertEquals(FreebieFeed.State.FAILED, new EpicIntake().decode(Arrays.copyOf(captured(), 500), NOW).state());
    }
    @Test void malformedRowKeepsValidRowsButDisablesAbsenceEvidence() throws Exception {
        var root = root(); active(root).remove("promotions"); var result = decode(root);
        assertEquals(FreebieFeed.State.PARTIAL, result.state()); assertEquals(1, result.candidates().size());
    }
    @Test void duplicateCatalogIdentityFailsWholeFeed() throws Exception {
        var root = root(); var rows = search(root).getList("elements", Document.class);
        rows.add(active(root)); search(root).get("paging", Document.class).put("total", rows.size());
        assertEquals(FreebieFeed.State.FAILED, decode(root).state());
    }
    @Test void unsafeMappingsCannotBecomeClaimLinks() throws Exception {
        for (String slug : List.of("https://evil.example/", "../checkout", "foo?offers=bad", "foo%2fbar", "foo#bar")) {
            var root = root(); active(root).getList("offerMappings", Document.class).getFirst().put("pageSlug", slug);
            assertEquals(FreebieFeed.State.PARTIAL, decode(root).state()); assertEquals(1, decode(root).candidates().size());
        }
    }
    @Test void unsafeImagesAreDropped() {
        for (String url : List.of("http://cdn1.epicgames.com/a.png", "https://cdn1.epicgames.com.evil/a.png", "https://cdn1.epicgames.com@evil/a.png", "https://cdn1.epicgames.com/../a.png"))
            assertTrue(EpicIntake.safeImage(url).isEmpty());
    }
    @Test void boundedBodyAndDuplicatePropertiesFailClosed() {
        assertEquals(FreebieFeed.State.FAILED, new EpicIntake().decode(new byte[GamerPowerIntake.MAX_BYTES + 1], NOW).state());
        assertEquals(FreebieFeed.State.FAILED, new EpicIntake().decode("{\"data\":{},\"data\":{}}".getBytes(StandardCharsets.UTF_8), NOW).state());
    }
    @Test void repeatedCatalogPromotionHasDistinctSourceIdentity() throws Exception {
        var root = root(); promotion(active(root)).put("startDate", "2026-10-09T15:00:00Z");
        assertNotEquals(candidate().identity(), decode(root).candidates().getFirst().identity());
    }
    @Test void malformedUpcomingScheduleAndMissingRedemptionFlagArePartial() throws Exception {
        var root = root(); active(root).get("promotions", Document.class).put("upcomingPromotionalOffers",
                List.of(new Document("promotionalOffers", List.of(new Document("startDate", "bad")))));
        assertEquals(FreebieFeed.State.PARTIAL, decode(root).state());
        root = root(); active(root).remove("isCodeRedemptionOnly"); assertEquals(FreebieFeed.State.PARTIAL, decode(root).state());
    }
}
