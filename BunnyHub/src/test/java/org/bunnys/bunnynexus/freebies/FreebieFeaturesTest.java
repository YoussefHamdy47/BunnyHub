package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** .env-only feature switches and the optional GamerPower redirect validation. */
class FreebieFeaturesTest {
    static final String HUB = "123456789012345678";

    FreebieFeatures load(Map<String, String> env, List<String> warnings) { return FreebieFeatures.load(env::get, warnings::add); }

    @Test void defaultsKeepOptionalFeaturesOffExceptGroupingAndEnrichment() {
        var f = load(Map.of(), new ArrayList<>());
        assertEquals(FreebieFeatures.DEFAULT, f);
        assertFalse(f.publishes(HUB));
        assertFalse(f.checkout());
        assertFalse(f.resolveGamerPowerLinks());
    }

    @Test void publishingNeedsBothTheSwitchAndAnExplicitAllowlist() {
        var warnings = new ArrayList<String>();
        assertFalse(load(Map.of("FREEBIE_PUBLISH_ENABLED", "true"), warnings).publishEnabled());
        assertFalse(warnings.isEmpty());
        assertFalse(load(Map.of("FREEBIE_PUBLISH_CHANNEL_IDS", HUB), new ArrayList<>()).publishes(HUB));
        var on = load(Map.of("FREEBIE_PUBLISH_ENABLED", "true", "FREEBIE_PUBLISH_CHANNEL_IDS", HUB + ", not-an-id ,"), new ArrayList<>());
        assertTrue(on.publishes(HUB));
        assertFalse(on.publishes("223456789012345678"));
        assertEquals(Set.of(HUB), on.publishChannels());
    }

    @Test void invalidFlagsWarnAndKeepTheDefault() {
        var warnings = new ArrayList<String>();
        var f = load(Map.of("FREEBIE_GROUPED_ANNOUNCEMENTS", "maybe", "FREEBIE_EPIC_CHECKOUT", "TRUE"), warnings);
        assertTrue(f.grouped());
        assertTrue(f.checkout());
        assertEquals(1, warnings.size());
    }

    @Test void onlyAnEpicProductPageGivesAGamerPowerReportAStorefrontIdentity() {
        var report = FreebieIdentityTest.gamerpower("3823", "Home (Epic Games) Giveaway", "PC, Epic Games Store", Optional.empty());
        var epic = new FreebieStorePages(new GamerPowerLinks(uri -> new GamerPowerLinks.Hop(302,
                Optional.of("https://store.epicgames.com/en-US/p/home-19212a"))));
        assertEquals(Optional.of("home-19212a"), epic.apply(report).productSlug());
        var steam = new FreebieStorePages(new GamerPowerLinks(uri -> new GamerPowerLinks.Hop(302,
                Optional.of("https://store.steampowered.com/app/1/"))));
        assertTrue(steam.apply(report).productSlug().isEmpty(), "another store is not an Epic identity");
        var failing = new FreebieStorePages(new GamerPowerLinks(uri -> { throw new java.io.IOException("520"); }));
        assertSame(report, failing.apply(report), "a failed redirect leaves the report unchanged (held, never merged)");
    }
}
