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
        var starters = new java.util.ArrayList<>(StarterAds.all("123456789012345678", now).keySet());
        for (Ad a : starters) {
            assertEquals(Optional.empty(), Ad.problems(a.id(), a.title().orElse(null), a.text(), a.image().map(Object::toString).orElse(null),
                    a.emoji().orElse(null), a.links().stream().map(l -> new String[]{l.label(), l.url(), l.emoji().orElse(null)}).toList()), a.id());
            assertTrue(a.emoji().isPresent(), "every ad leads with an emoji: " + a.id());
            assertFalse(a.emoji().orElseThrow().matches(".*[\\x{1F300}-\\x{1FAFF}].*"), "the bot's own emojis, not Unicode ones: " + a.id());
        }
        assertEquals(List.of("deal-spotlight", "also-free", "big-game", "month-value", "get-bunnynexus", "deals-board", "join-community"),
                starters.stream().map(Ad::id).toList());
        var get = starters.stream().filter(a -> a.id().equals("get-bunnynexus")).findFirst().orElseThrow();
        assertEquals("https://discord.com/oauth2/authorize?client_id=123456789012345678&permissions=268520464&scope=bot+applications.commands",
                get.link().orElseThrow().url());
        assertTrue(starters.stream().filter(a -> a.priority() >= 10).allMatch(Ad::live),
                "live ads outrank the evergreen ones");
    }

    // ---- Live ads ---------------------------------------------------------------------------------------------------

    static final AdContext.Deal RDR2 = new AdContext.Deal("Red Dead Redemption 2", "https://store.steampowered.com/app/1174180/", "Steam",
            "<:steam:705062577817780315>", "$14.99", "$59.99", 75, Optional.of(Instant.parse("2026-12-03T17:00:00Z")));
    static final AdContext.Free HADES = new AdContext.Free("Hades", "https://store.epicgames.com/en-US/p/hades", "Epic Games Store",
            "<:Epic:824679104220626954>", Optional.of("$24.99"), Optional.of(Instant.parse("2026-11-26T16:00:00Z")));

    AdContext context(Optional<AdContext.Deal> deal, Optional<AdContext.Free> free, Optional<AdContext.Game> game, int monthCount, long monthCents) {
        return new AdContext(deal, free, game, monthCount, monthCents, Optional.of("https://discord.com/invite-bot"),
                "https://discord.gg/gGsdHYhTFv", "https://top.gg/bot/795361755223556116/vote");
    }

    List<Ad> starters() { return new java.util.ArrayList<>(StarterAds.all("123456789012345678", now).keySet()); }

    @Test void aLiveDealAdIsFilledFromTheDealAndHiddenWithoutOne() {
        var spotlight = starters().getFirst();
        var filled = AdTemplate.render(spotlight, context(Optional.of(RDR2), Optional.empty(), Optional.empty(), 0, 0)).orElseThrow();
        assertEquals(Optional.of("Red Dead Redemption 2 Is 75% Off"), filled.title());
        assertEquals(Optional.of("<:steam:705062577817780315>"), filled.emoji());
        assertTrue(filled.text().startsWith("**$14.99** ~~$59.99~~ on Steam, ends <t:1796317200:R>."), filled.text());
        assertEquals("https://store.steampowered.com/app/1174180/", filled.links().getFirst().url());
        assertEquals("Open on Steam", filled.links().getFirst().label());
        assertEquals("https://discord.com/invite-bot", filled.links().get(1).url());
        assertTrue(AdTemplate.render(spotlight, AdContext.NONE).isEmpty(), "no deal running: not shown");
    }

    @Test void theRightAdForTheMoment() {
        var ads = starters();
        var none = UpdateAudience.PromoState.NONE;
        var deal = context(Optional.of(RDR2), Optional.of(HADES), Optional.empty(), 0, 0);
        assertEquals("deal-spotlight", UpdateAudience.ad(ads, Set.of(), none, now, deal).orElseThrow().id(), "a real deal for a server without a board");
        assertEquals("also-free", UpdateAudience.ad(ads, Set.of(ProductUpdate.Feature.DEALS_BOARD), none, now, deal).orElseThrow().id(),
                "a board already: the free game it does not get instead");
        var bigGame = context(Optional.empty(), Optional.empty(), Optional.of(new AdContext.Game("Hades", "https://x.example/h", "$24.99", 2499)), 0, 0);
        assertEquals("big-game", UpdateAudience.ad(ads, Set.of(), none, now, bigGame).orElseThrow().id());
        var smallGame = context(Optional.empty(), Optional.empty(), Optional.of(new AdContext.Game("Tiny", "https://x.example/t", "$4.99", 499)), 0, 0);
        assertNotEquals("big-game", UpdateAudience.ad(ads, Set.of(), none, now, smallGame).orElseThrow().id(), "only after a game worth $20 or more");
        var month = context(Optional.empty(), Optional.empty(), Optional.empty(), 12, 31241);
        var monthAd = UpdateAudience.ad(ads, Set.of(), none, now, month).orElseThrow();
        assertEquals("month-value", monthAd.id());
        assertEquals(Optional.of("$312.41 in Free Games This Month"), monthAd.title());
        assertEquals("get-bunnynexus", UpdateAudience.ad(ads, Set.of(ProductUpdate.Feature.DEALS_BOARD), none, now,
                context(Optional.empty(), Optional.empty(), Optional.empty(), 1, 999)).orElseThrow().id(), "nothing live applies: evergreen");
        var seenOut = new UpdateAudience.PromoState(Optional.empty(), Map.of("deal-spotlight", UpdateAudience.PROMO_TIMES));
        assertEquals("also-free", UpdateAudience.ad(ads, Set.of(), seenOut, now, deal).orElseThrow().id(), "each ad's limit still holds");
    }

    @Test void placeholdersAreCheckedWhenTheOwnerAddsAnAd() {
        assertTrue(Ad.problems("x1", "{deal.name} sale", "Text {nope}", null, null, List.of()).orElseThrow().contains("{nope}"));
        assertEquals(Optional.empty(), Ad.problems("x1", null, "Run {/free-games setup} for {deal}", null, "{deal.emoji}",
                List.<String[]>of(new String[]{"Open", "{deal.url}", null})));
        assertTrue(Ad.problems("x1", null, "Text", null, null, List.<String[]>of(new String[]{"Open", "{deal.name}", null})).isPresent(),
                "a button link must be a link placeholder");
    }

    @Test void otherFreeGamesAreOnlyThoseThisChannelDoesNotGet() {
        var epic = FreebieTestOffers.offer("epic:1", org.bunnys.bunnynexus.freebies.FreebieStore.EPIC, "Hades", "$24.99");
        var steam = FreebieTestOffers.offer("steam:1", org.bunnys.bunnynexus.freebies.FreebieStore.STEAM, "Celeste", "$19.99");
        var cheaper = FreebieTestOffers.offer("epic:2", org.bunnys.bunnynexus.freebies.FreebieStore.EPIC, "Small", "$4.99");
        var live = List.of(epic, steam, cheaper);
        assertEquals("Celeste", AdContexts.otherFree(live, List.of(), Set.of(org.bunnys.bunnynexus.freebies.FreebieStore.EPIC)).orElseThrow().title());
        assertEquals("Hades", AdContexts.otherFree(live, List.of(), Set.of()).orElseThrow().title(), "the most valuable one");
        assertEquals("Celeste", AdContexts.otherFree(live, List.of(epic), Set.of()).orElseThrow().title(), "not one already in this alert");
        assertEquals("$44.98", AdContext.dollars(AdContexts.total(List.of(epic, steam))[1]));
        assertEquals("$25", AdContext.dollars(2500));
    }

    @Test void starterAdsWriteCommandsAsLinks() {
        assertTrue(StarterAds.all("1", now).keySet().stream().noneMatch(a -> a.text().contains("`/")));
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
        var texts = tree.findAll(net.dv8tion.jda.api.components.textdisplay.TextDisplay.class);
        assertEquals("### Deals board\nBig discounts.", texts.getFirst().getContent());
        assertEquals("-# From BunnyNexus", texts.getLast().getContent(), "the source line is last, under the buttons");
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
