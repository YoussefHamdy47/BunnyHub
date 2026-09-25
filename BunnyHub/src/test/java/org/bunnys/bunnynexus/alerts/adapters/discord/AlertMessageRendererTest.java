package org.bunnys.bunnynexus.alerts.adapters.discord;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.bunnys.bunnynexus.alerts.application.AutomaticSendEngine;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertMessageRendererTest {
    final Instant now = Instant.parse("2026-09-23T12:00:00Z");
    final OfferKey key = new OfferKey(new StoreId("epic"), "campaign", new Market("EG"));
    final FreeGamePolicy policy = new FreeGamePolicy(Duration.ofMinutes(5), false);
    final AlertMessageRenderer renderer = new AlertMessageRenderer(Set.of("store.example.com", "www.gamerpower.com"));
    Offer offer(boolean knownEnd, Offer.Proof proof) {
        return new Offer(key, "edition", Offer.Kind.FREE_TO_KEEP_BASE_GAME,
                new Offer.Evidence(proof, proof, proof, proof, new SourceId("test"), "item", "test-rule"),
                Optional.of(new Offer.Money(0, Currency.getInstance("USD"))), Optional.of(now.minusSeconds(60)),
                knownEnd ? Optional.of(now.plusSeconds(3600)) : Optional.empty(), now, 2);
    }
    Subscription subscription(Set<String> roles) {
        return new Subscription("subscription", new DestinationRef("100", "200", "destination", "incarnation"),
                key.store(), key.market(), Topic.FREE_GAME, true, true, true, now.minusSeconds(60), 3, roles);
    }
    AlertDisplayContent content(String title, String description) {
        return new AlertDisplayContent(key, 2, title, description, URI.create("https://store.example.com/game"),
                "GamerPower", URI.create("https://www.gamerpower.com/"));
    }
    AlertMessageRenderer.FrozenMessage render(AlertDisplayContent content, Set<String> roles, String nonce) {
        return renderer.render(offer(true, Offer.Proof.VERIFIED), content, subscription(roles), policy, now, "attempt", nonce);
    }

    @Test void serializedMentionsAllowOnlyExactConfiguredRolesAndRetainAttribution() {
        var frozen = render(content("Example game", "Verified description"), Set.of("400", "300"), "nonce");
        var wire = DataObject.fromJson(frozen.bodyUtf8());
        assertEquals("<@&300> <@&400>", wire.getString("content"));
        var allowed = wire.getObject("allowed_mentions");
        assertEquals(0, allowed.getArray("parse").length());
        assertFalse(allowed.getBoolean("replied_user"));
        assertTrue(!allowed.hasKey("users") || allowed.getArray("users").length() == 0);
        assertEquals(2, allowed.getArray("roles").length());
        assertEquals("300", allowed.getArray("roles").getString(0));
        assertEquals("400", allowed.getArray("roles").getString(1));
        var embed = wire.getArray("embeds").getObject(0);
        assertEquals("https://store.example.com/game", embed.getString("url"));
        assertEquals("https://www.gamerpower.com/", embed.getObject("author").getString("url"));
        assertEquals("nonce", wire.getString("nonce"));
        assertEquals(Set.of("300", "400"), frozen.prepared().approvedRoles());
        assertEquals(2, frozen.prepared().contentRevision()); assertEquals(3, frozen.prepared().subscriptionRevision());
        assertFalse(wire.hasKey("message_reference"));
    }

    @Test void hostileTextCannotInjectMentionsFormattingOrLinkMarkup() {
        var frozen = render(content("@everyone **FREE**", "<@&777> <@123> [claim](https://evil.example/)\n```spoof``` \u202Ereversed"), Set.of("300"), "nonce");
        var embed = DataObject.fromJson(frozen.json()).getArray("embeds").getObject(0);
        assertFalse(embed.getString("title").contains("@everyone"));
        String text = embed.getString("description");
        assertFalse(text.contains("<@&777>")); assertFalse(text.contains("https://")); assertFalse(text.contains("\u202E"));
        assertTrue(text.contains("\\[claim\\]")); assertTrue(text.contains("\\`\\`\\`"));
        assertEquals(Set.of("300"), frozen.prepared().approvedRoles());
    }

    @Test void equivalentRoleOrderHasStableExactHashAndFrozenBytesCannotBeMutated() throws Exception {
        var data = content("Example", "Description");
        var first = render(data, new LinkedHashSet<>(List.of("400", "300")), "nonce");
        var second = render(data, new LinkedHashSet<>(List.of("300", "400")), "nonce");
        assertEquals(first.json(), second.json());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first.bodyUtf8())), first.prepared().payloadHash());
        byte[] mutable = first.bodyUtf8(); mutable[0] = 0;
        assertEquals(first.json(), new String(first.bodyUtf8(), java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(UnsupportedOperationException.class, () -> first.prepared().approvedRoles().add("500"));
        assertNotEquals(first.prepared().payloadHash(), render(data, Set.of("300", "400"), "other").prepared().payloadHash());
        assertNotEquals(first.prepared().payloadHash(), render(content("Changed", "Description"), Set.of("300", "400"), "nonce").prepared().payloadHash());
        assertNotEquals(first.prepared().payloadHash(), render(data, Set.of("300"), "nonce").prepared().payloadHash());
    }

    @Test void staleForeignOrUnverifiedContentCannotBePrepared() {
        var c = content("Example", "Description");
        var stale = new AlertDisplayContent(key, 1, c.title(), c.description(), c.claimUrl(), c.sourceName(), c.attributionUrl());
        var foreign = new AlertDisplayContent(new OfferKey(key.store(), "other", key.market()), 2,
                c.title(), c.description(), c.claimUrl(), c.sourceName(), c.attributionUrl());
        assertThrows(IllegalArgumentException.class, () -> render(stale, Set.of(), "nonce"));
        assertThrows(IllegalArgumentException.class, () -> render(foreign, Set.of(), "nonce"));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(offer(true, Offer.Proof.UNKNOWN), c, subscription(Set.of()), policy, now, "attempt", "nonce"));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(offer(true, Offer.Proof.VERIFIED), c, subscription(Set.of()), policy, now.plusSeconds(301), "attempt", "nonce"));
        var disabled = new Subscription("subscription", subscription(Set.of()).destination(), key.store(), key.market(), Topic.FREE_GAME,
                false, true, true, now, 3, Set.of());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(offer(true, Offer.Proof.VERIFIED), c, disabled, policy, now, "attempt", "nonce"));
    }

    @Test void unsafeAndLookalikeLinkHostsAreRejected() {
        for (String link : List.of("http://store.example.com/game", "https://user@store.example.com/game", "https://store.example.com:443/game", "https://store.example.com/game#fragment"))
            assertThrows(IllegalArgumentException.class, () -> new AlertDisplayContent(key, 2, "Game", "Description", URI.create(link), "Source", URI.create("https://www.gamerpower.com/")));
        var lookalike = new AlertDisplayContent(key, 2, "Game", "Description", URI.create("https://store.example.com.evil.example/game"), "Source", URI.create("https://www.gamerpower.com/"));
        assertThrows(IllegalArgumentException.class, () -> render(lookalike, Set.of(), "nonce"));
        var foreignAttribution = new AlertDisplayContent(key, 2, "Game", "Description", URI.create("https://store.example.com/game"), "Source", URI.create("https://evil.example/"));
        assertThrows(IllegalArgumentException.class, () -> render(foreignAttribution, Set.of(), "nonce"));
    }

    @Test void outputIsBoundedWithoutBrokenUnicodeAndNoRolesMeansNoPings() {
        var result = render(content("*".repeat(256), "🎮".repeat(2000)), Set.of(), "nonce");
        var wire = DataObject.fromJson(result.json()); var embed = wire.getArray("embeds").getObject(0);
        assertTrue(embed.getString("title").length() <= 256); assertTrue(embed.getString("description").length() <= 2048);
        assertFalse(embed.getString("description").codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF));
        assertEquals(0, wire.getObject("allowed_mentions").getArray("roles").length());
        assertTrue(!wire.hasKey("content") || wire.getString("content").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> content("x".repeat(257), "Description"));
        assertThrows(IllegalArgumentException.class, () -> content("Game", "x".repeat(4001)));
        assertThrows(IllegalArgumentException.class, () -> content("\uD800", "Description"));
    }

    @Test void roleOverflowAndInvalidNonceRejectBeforeAnyMessageIsReturned() {
        var roles = new HashSet<String>();
        for (long i = 0; i < 100; i++) roles.add(Long.toString(9_000_000_000_000_000_000L + i));
        assertThrows(IllegalArgumentException.class, () -> render(content("Game", "Description"), roles, "nonce"));
        for (String nonce : List.of("", "x".repeat(26), "bad nonce", "\u202E"))
            assertThrows(IllegalArgumentException.class, () -> render(content("Game", "Description"), Set.of(), nonce));
    }

    @Test void unknownEndRequiresExplicitPolicyAndNeverInventsADeadline() {
        var content = content("Game", "Description"); var unknownEnd = offer(false, Offer.Proof.VERIFIED);
        assertThrows(IllegalArgumentException.class, () -> renderer.render(unknownEnd, content, subscription(Set.of()), policy, now, "attempt", "nonce"));
        var result = renderer.render(unknownEnd, content, subscription(Set.of()), new FreeGamePolicy(Duration.ofMinutes(5), true), now, "attempt", "nonce");
        assertTrue(result.json().contains("End time not supplied")); assertFalse(result.json().contains("<t:"));
    }

    @Test void preparedPayloadStillRequiresCurrentSendAuthorization() {
        var subscription = subscription(Set.of("300")); var offer = offer(true, Offer.Proof.VERIFIED);
        var frozen = render(content("Game", "Description"), Set.of("300"), "nonce");
        var job = DeliveryJob.ready("job", new DeliveryKey("event", "200"), subscription.destination(), subscription.id(), now)
                .claim("worker", now, now.plusSeconds(30));
        var engine = new AutomaticSendEngine(policy);
        var current = new AutomaticSendEngine.Snapshot(job, subscription, offer, "event", new FreeGameKey(key), now, now, true, now.plusSeconds(20));
        var sending = assertInstanceOf(AutomaticSendEngine.Sending.class, engine.authorize(current, job.lease().orElseThrow(), frozen.prepared()));
        assertEquals(frozen.prepared().payloadHash(), sending.job().attempt().orElseThrow().payloadHash());
        var changedRoles = new AutomaticSendEngine.Snapshot(job, subscription(Set.of("400")), offer, "event", new FreeGameKey(key), now, now, true, now.plusSeconds(20));
        assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.REFRESH_PAYLOAD),
                engine.authorize(changedRoles, job.lease().orElseThrow(), frozen.prepared()));
    }
    @Test void renderedMessageCannotAuthorizeAnotherGameWithIdenticalRevisionsAndRoles() {
        var sub = subscription(Set.of("300")); var a = offer(true, Offer.Proof.VERIFIED);
        var bKey = new OfferKey(key.store(), "game-b", key.market());
        var b = new Offer(bKey, a.editionId(), a.kind(), a.evidence(), a.price(), a.startsAt(), a.endsAt(), a.verifiedAt(), a.contentRevision());
        var frozen = render(content("Game A", "Description"), sub.roleIds(), "nonce");
        var job = DeliveryJob.ready("job", new DeliveryKey("event", "200"), sub.destination(), sub.id(), now)
                .claim("worker", now, now.plusSeconds(30));
        var current = new AutomaticSendEngine.Snapshot(job, sub, b, "event", new FreeGameKey(bKey), now, now, true, now.plusSeconds(20));
        assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.REFRESH_PAYLOAD),
                new AutomaticSendEngine(policy).authorize(current, job.lease().orElseThrow(), frozen.prepared()));
    }
}
