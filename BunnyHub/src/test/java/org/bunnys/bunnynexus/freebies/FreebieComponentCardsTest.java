package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.freebies.FreebieAnnouncementCards.Status.*;
import static org.bunnys.bunnynexus.freebies.FreebieAnnouncementCardsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Free-game pages as Components V2 cards: layout, ping line, buttons, Discord's limits and history recovery. */
class FreebieComponentCardsTest {
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    static List<MessageTopLevelComponent> top(net.dv8tion.jda.api.utils.messages.MessageCreateData data) {
        return new ArrayList<>(data.getComponents());
    }

    static Container card(List<MessageTopLevelComponent> top) {
        return (Container) top.stream().filter(c -> c instanceof Container).findFirst().orElseThrow();
    }

    static <T> List<T> children(Container card, Class<T> type) {
        return card.getComponents().stream().filter(type::isInstance).map(type::cast).toList();
    }

    static List<String> texts(Container card) {
        List<String> texts = new ArrayList<>();
        for (var child : card.getComponents()) {
            if (child instanceof TextDisplay t) texts.add(t.getContent());
            if (child instanceof Section s) s.getContentComponents().forEach(c -> texts.add(((TextDisplay) c).getContent()));
        }
        return texts;
    }

    /** History recovery reads a posted V2 message the way Discord returns it. */
    static Message posted(net.dv8tion.jda.api.utils.messages.MessageCreateData data) {
        var message = mock(Message.class);
        when(message.getEmbeds()).thenReturn(List.of());
        when(message.isUsingComponentsV2()).thenReturn(true);
        when(message.getComponentTree()).thenReturn(MessageComponentTree.of(data.getComponents()));
        return message;
    }

    @Test void oneGameIsAPlainCardWithArtHeadingDealButtonsAndItsReference() {
        var m = member("a", LIVE);
        var data = FreebieComponentCards.create(List.of(m), List.of(ROLE), true, now);
        assertTrue(data.isUsingComponentsV2());
        assertTrue(data.getEmbeds().isEmpty());
        var top = top(data);
        assertEquals("<@&" + ROLE + "> 1 new free game", ((TextDisplay) top.getFirst()).getContent());
        assertEquals(Set.of(ROLE), data.getMentionedRoles());
        assertTrue(data.getAllowedMentions().stream().noneMatch(t -> t == Message.MentionType.EVERYONE || t == Message.MentionType.HERE));
        var card = card(top);
        assertNull(card.getAccentColorRaw(), "no accent colour");
        assertEquals("https://cdn1.epicgames.com/a.jpg", children(card, MediaGallery.class).getFirst().getItems().getFirst().getUrl());
        var texts = texts(card);
        assertTrue(texts.getFirst().startsWith("## [Game a](https://store.epicgames.com/en-US/p/a)"), texts.getFirst());
        var fields = texts.stream().filter(t -> t.startsWith("**The deal**")).findFirst().orElseThrow();
        assertTrue(fields.contains("**The deal** **Free**"), fields);
        assertTrue(fields.contains("\n**Claim window** Ends <t:") && fields.contains(":R> · <t:"), fields);
        assertTrue(fields.contains("\n**Platforms** PC, Epic Games Store"), fields);
        assertFalse(fields.contains("**Region**"), "no region line without a known restriction");
        assertEquals("-# " + m.footer(), texts.getLast());
        var labels = children(card, ActionRow.class).stream().flatMap(r -> r.getComponents().stream()).map(c -> ((Button) c).getLabel()).toList();
        assertEquals(List.of(FreebieCards.CLAIM_LABEL, "Instant Checkout"), labels, "the same button policy as embed pages");
        assertTrue(FreebieMessages.carriesReference(posted(data), m.reference()));
        assertFalse(FreebieMessages.carriesReference(posted(data), member("b", LIVE).reference()));
    }

    @Test void severalGamesShareOneCardWithAClaimButtonEach() {
        var members = List.of(member("a", LIVE), member("b", LIVE));
        var data = FreebieComponentCards.create(members, List.of(ROLE), true, now);
        assertEquals("<@&" + ROLE + "> 2 new free games", ((TextDisplay) top(data).getFirst()).getContent());
        var card = card(top(data));
        assertEquals(1, top(data).stream().filter(c -> c instanceof Container).count(), "one card");
        assertEquals(2, children(card, MediaGallery.class).getFirst().getItems().size());
        assertTrue(texts(card).contains("## 2 free games on Epic Games Store"));
        var sections = children(card, Section.class);
        assertEquals(2, sections.size());
        for (int i = 0; i < 2; i++) {
            var button = (Button) sections.get(i).getAccessory();
            assertEquals("Claim", button.getLabel());
            assertEquals(members.get(i).offer().claimUrl().toString(), button.getUrl());
        }
        var rows = children(card, ActionRow.class);
        assertEquals("Claim all PC games (2)", ((Button) rows.getFirst().getComponents().getFirst()).getLabel());
        assertTrue(FreebieMessages.carriesReference(posted(data), members.getFirst().reference()));
    }

    @Test void endedGamesLoseTheirButtonAndAFullyEndedPageLosesItsPingLine() {
        var members = List.of(member("a", LIVE), member("b", ENDED));
        String posted = "<@&" + ROLE + "> 2 new free games";
        var edit = FreebieComponentCards.edit(members, false, now, Optional.of(posted));
        assertTrue(edit.isUsingComponentsV2());
        var top = new ArrayList<MessageTopLevelComponent>(edit.getComponents());
        assertEquals("<@&" + ROLE + "> 1 new free game", ((TextDisplay) top.getFirst()).getContent(), "recounted, mentions kept");
        assertTrue(edit.getAllowedMentions().isEmpty(), "edits never ping");
        var card = card(top);
        assertEquals(1, children(card, Section.class).size());
        assertTrue(texts(card).stream().anyMatch(t -> t.startsWith("### ~~Game b~~") && t.contains(FreebieLifecycleMessages.ENDED_TEXT)));

        var ended = FreebieComponentCards.edit(List.of(member("a", ENDED), member("b", WITHDRAWN)), true, now, Optional.of(posted));
        var endedTop = new ArrayList<MessageTopLevelComponent>(ended.getComponents());
        assertTrue(endedTop.stream().noneMatch(c -> c instanceof TextDisplay), "no ping line once nothing is claimable");
        var endedCard = card(endedTop);
        assertTrue(children(endedCard, Section.class).isEmpty());
        assertTrue(children(endedCard, ActionRow.class).isEmpty(), "no checkout either");
        assertTrue(texts(endedCard).contains("## Ended: Free games on Epic Games Store"));
        assertTrue(children(endedCard, MediaGallery.class).isEmpty(), "no art without a live game");
    }

    @Test void aSingleEndedGameKeepsItsReferenceAndHasNoButtons() {
        var m = member("a", WITHDRAWN);
        var edit = FreebieComponentCards.edit(List.of(m), true, now, Optional.empty());
        var card = card(new ArrayList<>(edit.getComponents()));
        assertTrue(texts(card).getFirst().startsWith("## Ended: Game a"));
        assertTrue(texts(card).getFirst().contains(FreebieLifecycleMessages.WITHDRAWN_TEXT));
        assertTrue(children(card, ActionRow.class).isEmpty());
        assertEquals("-# " + m.footer(), texts(card).getLast());
    }

    @Test void catchUpPagesHaveNoPingLine() {
        var data = FreebieComponentCards.create(List.of(member("a", LIVE), member("b", LIVE)), List.of(), false, now);
        assertTrue(top(data).getFirst() instanceof Container);
        assertTrue(data.getMentionedRoles().isEmpty());
    }

    /** Ten games with long titles, links and descriptions stay inside 40 components and 4000 characters. */
    @Test void tenLongGamesStayInsideDiscordsLimits() {
        List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
        for (int i = 0; i < FreebieAnnouncementStore.DROP_PAGE_SIZE; i++) {
            var offer = epic("p".repeat(400) + i, "Title ".repeat(45) + i);
            members.add(new FreebieAnnouncementCards.Member(offer, facts("o" + i, List.of(), Optional.empty()),
                    offer.id() + "|" + CHANNEL, LIVE));
        }
        var data = FreebieComponentCards.create(members, List.of(ROLE), true, now);
        var top = top(data);
        assertTrue(FreebieComponentCards.componentCount(top) <= FreebieComponentCards.MAX_COMPONENTS, "components " + FreebieComponentCards.componentCount(top));
        assertTrue(FreebieComponentCards.textLength(top) <= FreebieComponentCards.MAX_TEXT, "text " + FreebieComponentCards.textLength(top));
        var card = card(top);
        assertEquals(10, children(card, Section.class).size(), "every game keeps its Claim button");
        assertTrue(children(card, MediaGallery.class).getFirst().getItems().size() <= FreebieComponentCards.MAX_ART);
        assertTrue(FreebieMessages.carriesReference(posted(data), members.getFirst().reference()));
    }

    @Test void aMixedPageNamesEachGamesStore() {
        var data = FreebieComponentCards.create(List.of(member("a", LIVE), steamMember("s")), List.of(), false, now);
        var texts = texts(card(top(data)));
        assertTrue(texts.contains("## 2 free games"));
        assertTrue(texts.stream().anyMatch(t -> t.contains("Steam s") && t.contains("Steam")));
        assertTrue(texts.stream().anyMatch(t -> t.contains("Game a") && t.contains("Epic Games Store")));
    }

    static FreebieAnnouncementCards.Member restricted(String id, List<String> blocked) {
        var evidence = new FreebieRegions.Evidence("egdata", Instant.parse("2026-10-23T11:00:00Z"), Optional.of(blocked), Optional.empty(), false);
        return new FreebieAnnouncementCards.Member(epic(id, "Game " + id), facts(id + "0", List.of(), Optional.of(evidence)),
                "epic:" + id + "|" + CHANNEL, LIVE);
    }

    static final String ID = "🇮🇩", SA = "🇸🇦", TW = "🇹🇼";

    @Test void restrictionsShowAsFlagsOnTheCardAndInAList() {
        var single = texts(card(top(FreebieComponentCards.create(List.of(restricted("a", List.of("TW", "ID", "SA"))), List.of(), false, now))));
        assertTrue(single.stream().anyMatch(t -> t.contains("\n**Region** 🚫 Not claimable in " + ID + " " + SA + " " + TW)),
                String.join("|", single));
        var list = texts(card(top(FreebieComponentCards.create(List.of(restricted("a", List.of("TW", "ID")), member("b", LIVE)), List.of(), false, now))));
        assertTrue(list.stream().anyMatch(t -> t.startsWith("### [Game a]") && t.endsWith("\n-# 🚫 Not claimable in " + ID + " " + TW)),
                String.join("|", list));
        assertTrue(list.stream().filter(t -> t.startsWith("### [Game b]")).noneMatch(t -> t.contains("claimable") || t.contains("may differ")),
                "no generic region text");
    }

    /** Mobile versions: one short line, their own end or restriction only when it differs from the PC game's. */
    @Test void mobileVersionsAreOneShortLine() {
        var regions = new FreebieRegions.Evidence("egdata", Instant.parse("2026-10-23T11:00:00Z"), Optional.of(List.of("SA", "TW")), Optional.empty(), false);
        var end = Instant.parse("2026-10-29T15:00:00Z");
        var android = new FreebieFacts.Variant(EgDataIntake.Platform.ANDROID, new EpicLinks.Offer("ns", "and"), "game-android", now.minusSeconds(60), end,
                Optional.of(regions), true);
        var other = new FreebieRegions.Evidence("egdata", Instant.parse("2026-10-23T11:00:00Z"), Optional.of(List.of("ID")), Optional.empty(), false);
        var ios = new FreebieFacts.Variant(EgDataIntake.Platform.IOS, new EpicLinks.Offer("ns", "ios"), "game-ios", now.minusSeconds(60),
                end.minusSeconds(3600), Optional.of(other), true);
        var m = new FreebieAnnouncementCards.Member(epic("a", "Game a"), facts("a0", List.of(android, ios), Optional.of(regions)),
                "epic:a|" + CHANNEL, LIVE);
        var fields = texts(card(top(FreebieComponentCards.create(List.of(m), List.of(), false, now)))).stream()
                .filter(t -> t.startsWith("**The deal**")).findFirst().orElseThrow();
        String mobile = fields.lines().filter(l -> l.startsWith("**Mobile** ")).findFirst().orElseThrow();
        assertTrue(mobile.startsWith("**Mobile** [Android](https://store.epicgames.com/en-US/p/game-android) · [iOS]("), mobile);
        assertFalse(mobile.substring(0, mobile.indexOf("[iOS]")).contains("until") || mobile.substring(0, mobile.indexOf("[iOS]")).contains("🚫"),
                "Android shares the PC end and restriction: nothing repeated");
        assertTrue(mobile.contains("until <t:" + end.minusSeconds(3600).getEpochSecond() + ":f> 🚫 Not claimable in " + ID), mobile);
        assertTrue(fields.endsWith("\n-# 📱 A separate claim from PC"));
        assertFalse(fields.contains("Indonesia") || fields.contains("Saudi Arabia"), "flags only, no country names");
    }

    @Test void flagsComeFromCountryCodes() {
        assertEquals("🇺🇸", FreebieRegions.flag("US"));
        assertEquals("", FreebieRegions.flag("XX"));
        assertEquals("", FreebieRegions.flag("USA"));
        List<String> many = new ArrayList<>(List.of("AR", "AU", "BR", "CA", "DE", "EG", "FR", "GB", "IN", "IT", "JP", "MX", "NL", "US"));
        String flags = FreebieRegions.flags(many);
        assertEquals(13, flags.split(" ").length, flags);
        assertTrue(flags.endsWith(" +2"), flags);
    }

    @Test void descriptionsAreCutAtASentenceOrWordNeverMidWord() {
        String text = "Recruit units, place them strategically, and find the synergies in this Roguelike AutoBattler!\n"
                + "Knightica lets you guide a warband through challenges to free the Kingdoms.";
        assertEquals("Recruit units, place them strategically, and find the synergies in this Roguelike AutoBattler!",
                FreebieComponentCards.excerpt(text, 110));
        String word = FreebieComponentCards.excerpt("Lead your army through dangerous lands and weave powerful enchants", 40);
        assertTrue(word.endsWith("…") && !word.contains("enchan…") && word.length() <= 41, word);
        assertEquals("Short and sweet.", FreebieComponentCards.excerpt("Short and sweet.", 110));
    }

    @Test void theSwitchDefaultsToComponentsV2AndCanRollBack() {
        assertTrue(FreebieFeatures.load(key -> null, warning -> {}).componentsV2());
        assertFalse(FreebieFeatures.load(key -> key.equals("FREEBIE_COMPONENTS_V2") ? "false" : null, warning -> {}).componentsV2());
        assertTrue(FreebieFeatures.DEFAULT.componentsV2());
    }

    @Test void liveCardsInviteToTheBotAndKeepTheirReference() {
        String url = "https://discord.com/oauth2/authorize?client_id=1&scope=bot";
        FreebieInvite.set(url);
        try {
            var m = member("a", LIVE);
            var one = FreebieComponentCards.create(List.of(m), List.of(), false, now);
            assertTrue(one.toData().toString().contains(url), "one game");
            assertTrue(FreebieMessages.carriesReference(posted(one), m.reference()), "the reference line is untouched");
            var two = FreebieComponentCards.create(List.of(member("a", LIVE), member("b", LIVE)), List.of(), false, now);
            assertTrue(two.toData().toString().contains(FreebieInvite.LABEL), "several games");
            var ended = FreebieComponentCards.create(List.of(member("a", ENDED), member("b", ENDED)), List.of(), false, now);
            assertFalse(ended.toData().toString().contains(url), "an ended page no longer advertises");
            var dm = FreebieDm.card(FreebieLifecycleTest.live(), FreebieDm.jobId("gamerpower:1", "400000000000000000"));
            assertTrue(dm.toData().toString().contains(url), "DM alerts too");
        } finally { FreebieInvite.set(null); }
        assertEquals("", FreebieInvite.line(), "nothing before the bot is ready");
    }
}
