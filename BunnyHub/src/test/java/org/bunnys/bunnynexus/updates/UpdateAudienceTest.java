package org.bunnys.bunnynexus.updates;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.bunnys.bunnynexus.updates.UpdateAudience.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UpdateAudienceTest {
    final Instant now = Instant.parse("2026-11-23T12:00:00Z");
    final ProductUpdate deals = ProductUpdate.DEALS_BOARD;

    Server server(String id, Set<ProductUpdate.Feature> uses, Prefs prefs) { return new Server(id, "c" + id, uses, prefs); }

    @Test void onlyServersThatCanAndShouldHearAboutItGetIt() {
        var servers = List.of(
                server("1", Set.of(), Prefs.NONE),
                server("2", Set.of(), new Prefs(true, Optional.empty(), Set.of())),
                server("3", Set.of(), new Prefs(false, Optional.of(now.minus(Duration.ofDays(30))), Set.of("deals-board"))),
                server("4", Set.of(), new Prefs(false, Optional.of(now.minus(Duration.ofDays(3))), Set.of("older"))),
                server("5", Set.of(ProductUpdate.Feature.DEALS_BOARD), Prefs.NONE),
                server("6", Set.of(), new Prefs(false, Optional.of(now.minus(UpdateAudience.COOLDOWN)), Set.of("older"))));
        var plan = UpdateAudience.plan(deals, servers, now);
        assertEquals(List.of("1", "6"), plan.send().stream().map(Server::guildId).toList(), "14 days after the last one is allowed");
        assertEquals(Map.of(Skip.TURNED_OFF, 1, Skip.ALREADY_SENT, 1, Skip.COOLDOWN, 1, Skip.USES_IT, 1), plan.skipped());
    }

    @Test void theOwnerPreviewShowsCountsReasonsAndTheMessage() {
        var plan = new Plan(List.of(server("1", Set.of(), Prefs.NONE)), Map.of(Skip.USES_IT, 3, Skip.TURNED_OFF, 1));
        var card = ProductUpdates.previewCard(deals, plan, "abc");
        String text = card.plainText();
        assertTrue(text.contains("Goes to **1** server(s)") && text.contains("Skipped 3: already use it") && text.contains("Skipped 1: turned product updates off"));
        assertTrue(text.contains("New: a live game Deals Board"), "the message exactly as servers get it");
        assertEquals(List.of("updates:send:abc", "updates:cancel:abc"),
                MessageComponentTree.of(card.components()).findAll(Button.class).stream().map(Button::getCustomId).toList());
        var nobody = ProductUpdates.previewCard(deals, new Plan(List.of(), Map.of()), null);
        assertTrue(nobody.plainText().contains("Nobody to send to") && MessageComponentTree.of(nobody.components()).findAll(Button.class).isEmpty());
    }

    static Ad ad(String id, Ad.Audience audience, boolean on) {
        return new Ad(id, Optional.empty(), "Text " + id, Optional.empty(), Optional.empty(), audience, on, Instant.EPOCH);
    }

    @Test void adsRotateLeastSeenFirstEveryThreeDaysAndRespectTheirAudience() {
        var dealsAd = ad("deals", Ad.Audience.NO_DEALS_BOARD, true);
        var support = ad("support", Ad.Audience.ALL, true);
        var active = List.of(dealsAd, support);
        var none = UpdateAudience.PromoState.NONE;
        assertEquals(Optional.of(dealsAd), UpdateAudience.ad(active, Set.of(), none, now), "newest first on a tie");
        assertEquals(Optional.of(support), UpdateAudience.ad(active, Set.of(ProductUpdate.Feature.DEALS_BOARD), none, now),
                "a server with a board never sees the deals ad");
        var recent = new UpdateAudience.PromoState(Optional.of(now.minus(Duration.ofDays(2))), Map.of("deals", 1));
        assertEquals(Optional.empty(), UpdateAudience.ad(active, Set.of(), recent, now), "at most one every 3 days");
        var due = new UpdateAudience.PromoState(Optional.of(now.minus(UpdateAudience.PROMO_EVERY)), Map.of("deals", 1));
        assertEquals(Optional.of(support), UpdateAudience.ad(active, Set.of(), due, now), "the one it has seen least");
        var worn = new UpdateAudience.PromoState(Optional.of(now.minus(Duration.ofDays(9))),
                Map.of("deals", UpdateAudience.PROMO_TIMES, "support", UpdateAudience.PROMO_TIMES));
        assertEquals(Optional.empty(), UpdateAudience.ad(active, Set.of(), worn, now), "each ad has a limit per server");
        assertEquals(Optional.empty(), UpdateAudience.ad(List.of(ad("x", Ad.Audience.ALL, false)), Set.of(), none, now), "paused ads never show");
    }

    @Test void theStarterAdsAreValidAndPointAtTheBot() {
        var starters = StarterAds.all("123456789012345678", now);
        for (Ad a : starters)
            assertEquals(Optional.empty(), Ad.problem(a.id(), a.title().orElse(null), a.text(), a.image().map(Object::toString).orElse(null),
                    a.link().map(Ad.Link::label).orElse(null), a.link().map(l -> l.url().toString()).orElse(null)), a.id());
        var get = starters.getFirst();
        assertEquals(Ad.Audience.ALL, get.audience());
        assertEquals("https://discord.com/oauth2/authorize?client_id=123456789012345678&permissions=268520464&scope=bot+applications.commands",
                get.link().orElseThrow().url().toString());
        assertEquals(Ad.Audience.NO_DEALS_BOARD, starters.get(1).audience());
    }

    @Test void starterAdsWriteCommandsAsLinks() {
        assertTrue(StarterAds.all("1", now).stream().allMatch(a -> a.text().contains("{/free-games ") && !a.text().contains("`/")));
    }

    @Test void adValuesAreChecked() {
        assertEquals(Optional.empty(), Ad.problem("deals-board", "Title", "Text", "https://cdn.example/a.png", "Open", "https://example.com"));
        assertTrue(Ad.problem("Deals Board", null, "Text", null, null, null).isPresent(), "bad id");
        assertTrue(Ad.problem("ok", null, " ", null, null, null).isPresent(), "no text");
        assertTrue(Ad.problem("ok", null, "Text", "http://insecure/a.png", null, null).isPresent(), "http image");
        assertTrue(Ad.problem("ok", null, "Text", null, "Open", null).isPresent(), "label without link");
        assertTrue(Ad.problem("ok", null, "Text", null, "Open", "javascript:alert(1)").isPresent(), "not https");
    }

    @Test void theAdCardHasImageTitleTextButtonAndSaysWhoItIsFrom() {
        var full = new Ad("deals", Optional.of("Deals board"), "Big discounts.", Optional.of(java.net.URI.create("https://cdn.example/a.png")),
                Optional.of(new Ad.Link("Learn more", java.net.URI.create("https://example.com"))), Ad.Audience.ALL, true, Instant.EPOCH);
        var card = AdCards.card(full);
        var tree = MessageComponentTree.of(List.of(card));
        assertEquals(1, tree.findAll(net.dv8tion.jda.api.components.mediagallery.MediaGallery.class).size());
        String text = tree.findAll(net.dv8tion.jda.api.components.textdisplay.TextDisplay.class).getFirst().getContent();
        assertEquals("### Deals board\nBig discounts.\n-# From BunnyNexus", text);
        assertEquals("https://example.com", tree.findAll(Button.class).getFirst().getUrl());
        var plain = MessageComponentTree.of(List.of(AdCards.card(ad("p", Ad.Audience.ALL, true))));
        assertTrue(plain.findAll(Button.class).isEmpty() && plain.findAll(net.dv8tion.jda.api.components.mediagallery.MediaGallery.class).isEmpty());
    }

    @Test void turningOffWinsOverEveryOtherReason() {
        var off = server("1", Set.of(ProductUpdate.Feature.DEALS_BOARD), new Prefs(true, Optional.of(now), Set.of("deals-board")));
        assertEquals(Optional.of(Skip.TURNED_OFF), UpdateAudience.skip(deals, off, now));
    }

    @Test void whatsNewIsTheNewestUnusedFeature() {
        assertEquals(Optional.of(deals), UpdateAudience.whatsNew(Set.of()));
        assertEquals(Optional.empty(), UpdateAudience.whatsNew(EnumSet.allOf(ProductUpdate.Feature.class)));
    }

    @Test void theCardIsPlainPingsNobodyAndOffersOptOut() {
        var card = UpdateCards.update(deals);
        String text = card.plainText();
        assertTrue(text.contains("New: a live game Deals Board") && text.contains("/free-games setup") && text.contains("For server admins"));
        assertFalse(text.contains("@everyone") || text.contains("<@"));
        var buttons = MessageComponentTree.of(card.components()).findAll(Button.class);
        assertEquals(List.of("updates:off"), buttons.stream().map(Button::getCustomId).toList());
        assertTrue(card.message().getMentionedRoles().isEmpty() && card.message().getMentionedUsers().isEmpty());
        for (var update : ProductUpdate.values()) {
            assertTrue(update.pitch().length() <= ProductUpdate.MAX_PITCH);
            assertEquals(Optional.of(update), ProductUpdate.byId(update.id()));
        }
    }
}
