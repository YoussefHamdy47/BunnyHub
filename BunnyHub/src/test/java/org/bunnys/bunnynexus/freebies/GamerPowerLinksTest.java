package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Claim links open the store directly when GamerPower redirects there, and keep GamerPower's link otherwise. */
class GamerPowerLinksTest {
    static final URI OPEN = URI.create("https://www.gamerpower.com/open/deadshot-steam-giveaway");

    /** Answers each GamerPower URL from a script and records what was asked. */
    static final class Script implements GamerPowerLinks.Transport {
        final Map<String, GamerPowerLinks.Hop> answers = new HashMap<>();
        final List<URI> asked = new ArrayList<>();
        Script redirect(String from, String to) { answers.put(from, new GamerPowerLinks.Hop(302, Optional.of(to))); return this; }
        Script page(String at) { answers.put(at, new GamerPowerLinks.Hop(200, Optional.empty())); return this; }
        @Override public GamerPowerLinks.Hop get(URI uri) throws IOException {
            asked.add(uri);
            var hop = answers.get(uri.toString());
            if (hop == null) throw new IOException("connection reset");
            return hop;
        }
    }

    @Test void aRedirectToAStoreBecomesTheClaimLink() {
        var script = new Script().redirect(OPEN.toString(), "https://store.steampowered.com/app/123/Deadshot/");
        assertEquals(Optional.of(URI.create("https://store.steampowered.com/app/123/Deadshot/")), new GamerPowerLinks(script).resolve(OPEN));
        assertEquals(List.of(OPEN), script.asked, "the store itself is never fetched");
    }

    @Test void gamerPowerHopsAreFollowedThenTheStoreIsTaken() {
        var script = new Script().redirect(OPEN.toString(), "/go/123")
                .redirect("https://www.gamerpower.com/go/123", "https://www.gog.com/en/game/some_game");
        assertEquals(Optional.of(URI.create("https://www.gog.com/en/game/some_game")), new GamerPowerLinks(script).resolve(OPEN));
    }

    @Test void everythingElseKeepsTheGamerPowerLink() {
        // An interstitial page, an unknown site, plain http, a failure, and too many hops.
        assertEquals(Optional.empty(), new GamerPowerLinks(new Script().page(OPEN.toString())).resolve(OPEN));
        assertEquals(Optional.empty(), new GamerPowerLinks(new Script().redirect(OPEN.toString(), "https://free-keys.example/x")).resolve(OPEN));
        assertEquals(Optional.empty(), new GamerPowerLinks(new Script().redirect(OPEN.toString(), "http://store.steampowered.com/app/1")).resolve(OPEN));
        assertEquals(Optional.empty(), new GamerPowerLinks(new Script()).resolve(OPEN));
        var loop = new Script().redirect(OPEN.toString(), OPEN.toString());
        assertEquals(Optional.empty(), new GamerPowerLinks(loop).resolve(OPEN));
        assertEquals(GamerPowerLinks.MAX_HOPS, loop.asked.size());
        // Non-GamerPower links are never touched.
        var none = new Script();
        assertEquals(Optional.empty(), new GamerPowerLinks(none).resolve(URI.create("https://store.epicgames.com/p/x")));
        assertTrue(none.asked.isEmpty());
    }

    @Test void onlyKnownStoresOverHttpsFitOnAButton() {
        assertTrue(GamerPowerLinks.acceptable(URI.create("https://store.epicgames.com/en-US/p/game")).isPresent());
        assertTrue(GamerPowerLinks.acceptable(URI.create("https://someone.itch.io/game")).isPresent());
        assertTrue(GamerPowerLinks.acceptable(URI.create("https://evilsteampowered.com/app")).isEmpty(), "suffix must be a whole domain");
        assertTrue(GamerPowerLinks.acceptable(URI.create("https://user@store.steampowered.com/")).isEmpty());
        assertTrue(GamerPowerLinks.acceptable(URI.create("https://store.steampowered.com:8443/")).isEmpty());
        assertTrue(GamerPowerLinks.acceptable(URI.create("https://store.steampowered.com/" + "a".repeat(600))).isEmpty());
    }

    @Test void eachPollResolvesABoundedBatchAndRecordsFailures() {
        var repository = mock(FreebieRepository.class);
        var resolved = FreebieAutoSendTest.pending(FreebieStore.STEAM, false);
        var other = new FreebieOffer("gamerpower:8", "8", FreebieStore.GOG, "PC", "Other", "d", "i", Optional.empty(), Optional.empty(),
                URI.create("https://www.gamerpower.com/open/other"), URI.create("https://www.gamerpower.com/other"),
                Optional.empty(), Instant.EPOCH, FreebieOffer.State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(),
                false, false, 1);
        when(repository.unresolvedClaimLinks(FreebieClaimLinks.PER_POLL)).thenReturn(List.of(resolved, other));
        var script = new Script().redirect(resolved.claimUrl().toString(), "https://store.steampowered.com/app/7/");
        var now = Instant.parse("2026-10-14T12:00:00Z");
        new FreebieClaimLinks(repository, new GamerPowerLinks(script), Clock.fixed(now, ZoneOffset.UTC)).run();
        verify(repository).claimLinkResolved(resolved.id(), URI.create("https://store.steampowered.com/app/7/"), now);
        verify(repository).claimLinkUnresolved(other.id());
        verify(repository, never()).claimLinkResolved(eq(other.id()), any(), any());
    }
}
