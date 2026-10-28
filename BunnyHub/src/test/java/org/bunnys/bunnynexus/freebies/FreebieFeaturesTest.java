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

    @Test void redirectHopsStayOnApprovedHttpsHosts() {
        URI from = URI.create("https://www.gamerpower.com/open/home");
        assertEquals(Optional.of(URI.create("https://store.epicgames.com/p/home-19212a")),
                GamerPowerLinks.next(from, "https://store.epicgames.com/p/home-19212a"));
        assertEquals(Optional.of(URI.create("https://www.gamerpower.com/go/home")), GamerPowerLinks.next(from, "/go/home"));
        for (String bad : List.of("http://store.epicgames.com/p/home", "https://evil.example/p/home", "https://u@store.epicgames.com/p/x",
                "https://store.epicgames.com:444/p/x", "javascript:alert(1)"))
            assertTrue(GamerPowerLinks.next(from, bad).isEmpty(), bad);
        assertTrue(GamerPowerLinks.next(from, null).isEmpty());
    }
}
