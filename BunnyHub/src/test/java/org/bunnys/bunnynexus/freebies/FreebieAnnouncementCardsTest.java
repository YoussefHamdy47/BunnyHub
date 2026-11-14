package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.freebies.FreebieAnnouncementCards.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Announcement page rendering: one ping, exact footers, only live claims, honest region text, bounded size. */
class FreebieAnnouncementCardsTest {
    static final String CHANNEL = "200000000000000000", ROLE = "300000000000000000";
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    static FreebieOffer epic(String id, String title) {
        URI page = URI.create("https://store.epicgames.com/en-US/p/" + id);
        return new FreebieOffer("epic:" + id, id, FreebieStore.EPIC, "PC, Epic Games Store", title, "D".repeat(900), "",
                Optional.empty(), Optional.of(URI.create("https://cdn1.epicgames.com/" + id + ".jpg")), page, page,
                Optional.of(Instant.parse("2026-10-29T15:00:00Z")), Instant.parse("2026-10-22T15:00:00Z"), FreebieOffer.State.APPROVED,
                Optional.empty(), Optional.empty(), Optional.of("owner"), true, false, 2);
    }

    static FreebieFacts facts(String offerId, List<FreebieFacts.Variant> variants, Optional<FreebieRegions.Evidence> regions) {
        return new FreebieFacts(Optional.of(new EpicLinks.Offer("ns" + offerId.length(), offerId)), Optional.of("epic|m|1"), regions,
                variants, Optional.empty(), true);
    }

    static FreebieAnnouncementCards.Member member(String id, FreebieAnnouncementCards.Status status) {
        return new FreebieAnnouncementCards.Member(epic(id, "Game " + id), facts(id + "0", List.of(), Optional.empty()),
                "epic:" + id + "|" + CHANNEL, status);
    }

    static List<String> labels(List<ActionRow> rows) {
        return rows.stream().flatMap(r -> r.getComponents().stream()).map(c -> ((Button) c).getLabel()).toList();
    }

    @Test void aSingleGameKeepsTheFamiliarCardFooterAndPingsOnlyTheSavedRole() {
        var m = member("a", LIVE);
        var data = FreebieAnnouncementCards.create(List.of(m), Optional.of(ROLE), false, now);
        assertEquals(1, data.getEmbeds().size());
        assertEquals("Powered by Epic Games • ref " + FreebieMessages.reference(m.deliveryId()), data.getEmbeds().getFirst().getFooter().getText());
        assertEquals("<@&" + ROLE + ">", data.getContent());
        assertEquals(Set.of(ROLE), data.getMentionedRoles());
        assertTrue(data.getAllowedMentions().stream().noneMatch(t -> t == Message.MentionType.EVERYONE || t == Message.MentionType.HERE));
        assertEquals(List.of(FreebieCards.claimButton(m.offer()).getLabel()), labels(data.getComponents().stream().map(c -> (ActionRow) c).toList()));
        String region = data.getEmbeds().getFirst().getFields().stream().filter(f -> "Region".equals(f.getName())).findFirst().orElseThrow().getValue();
        assertTrue(region.contains("not fully verified"));
    }

    static FreebieOffer steam(String id, String title) {
        URI page = URI.create("https://store.steampowered.com/app/" + id);
        return new FreebieOffer("steam:" + id, id, FreebieStore.STEAM, "PC, Steam", title, "D".repeat(900), "",
                Optional.empty(), Optional.of(URI.create("https://cdn.steam.com/" + id + ".jpg")), page, page,
                Optional.of(Instant.parse("2026-10-29T15:00:00Z")), Instant.parse("2026-10-22T15:00:00Z"), FreebieOffer.State.APPROVED,
                Optional.empty(), Optional.empty(), Optional.of("owner"), true, false, 2);
    }

    static FreebieAnnouncementCards.Member steamMember(String id) {
        return new FreebieAnnouncementCards.Member(steam(id, "Steam " + id), FreebieFacts.NONE, "steam:" + id + "|" + CHANNEL, LIVE);
    }

    /** Every embed carries the exact footer of one of its games, so history recovery finds the message. */
    static void everyEmbedCarriesAMemberFooter(List<MessageEmbed> embeds, List<FreebieAnnouncementCards.Member> members) {
        for (var e : embeds)
            assertTrue(members.stream().anyMatch(m -> FreebieMessages.isAlertFooter(e, m.reference())), String.valueOf(e.getFooter()));
    }

    static List<Integer> gamesPerEmbed(List<MessageEmbed> embeds) {
        return embeds.stream().map(e -> e.getFields().isEmpty() ? 1 : e.getFields().size()).toList();
    }

    @Test void aReleaseOfFiveFitsOneMessageWithEachFooterAndAClaimPerGame() {
        List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d", "e")) members.add(member(id, LIVE));
        var data = FreebieAnnouncementCards.create(members, Optional.of(ROLE), true, now);
        assertEquals(List.of(3, 2), gamesPerEmbed(data.getEmbeds()), "five Epic games share two embeds");
        assertTrue(data.getEmbeds().stream().mapToInt(MessageEmbed::getLength).sum() <= FreebieAnnouncementCards.EMBED_TOTAL);
        assertTrue(FreebieMessages.isAlertFooter(data.getEmbeds().get(0), members.get(0).reference()));
        assertTrue(FreebieMessages.isAlertFooter(data.getEmbeds().get(1), members.get(3).reference()));
        assertEquals(List.of("Game a", "Game b", "Game c"), data.getEmbeds().getFirst().getFields().stream().map(MessageEmbed.Field::getName).toList());
        assertTrue(data.getEmbeds().getFirst().getTitle().contains("3 free games on Epic Games Store"));
        var labels = labels(data.getComponents().stream().map(c -> (ActionRow) c).toList());
        assertEquals("Claim all PC games (5)", labels.getFirst());
        assertEquals(6, labels.size());
    }

    @Test void endedMembersLoseTheirClaimAndShrinkTheCombinedCheckout() {
        var members = List.of(member("a", LIVE), member("b", ENDED), member("c", LIVE));
        var edit = FreebieAnnouncementCards.edit(members, true, now);
        var labels = labels(edit.getComponents().stream().map(c -> (ActionRow) c).toList());
        assertEquals(List.of("Claim eligible PC games (2)", "Claim Game a", "Claim Game c"), labels);
        var group = edit.getEmbeds().getFirst();
        assertTrue(group.getFields().get(1).getName().startsWith("Ended: "));
        assertTrue(group.getFields().get(0).getValue().contains("Claim on PC"));
        assertFalse(group.getFields().get(1).getValue().contains("Claim on PC"), "an ended game loses its claim link");
        assertTrue(FreebieMessages.isAlertFooter(group, members.get(0).reference()), "the footer reference survives the edit");
        assertTrue(edit.getAllowedMentions().isEmpty());
        assertFalse(edit.toData().hasKey("content"), "content (the original ping text) is untouched while a game is live");
    }

    @Test void whenEveryGameEndedTheContentIsClearedAndNoClaimRemains() {
        var edit = FreebieAnnouncementCards.edit(List.of(member("a", ENDED), member("b", WITHDRAWN)), true, now);
        assertTrue(edit.toData().hasKey("content"));
        assertEquals("", edit.getContent());
        assertTrue(labels(edit.getComponents().stream().map(c -> (ActionRow) c).toList()).stream().noneMatch(l -> l.startsWith("Claim")));
        var group = edit.getEmbeds().getFirst();
        assertTrue(group.getTitle().contains("Ended: "));
        assertEquals(FreebieLifecycleMessages.ENDED_COLOUR, group.getColorRaw());
        assertTrue(group.getFields().get(1).getValue().contains(FreebieLifecycleMessages.WITHDRAWN_TEXT));
        assertNull(group.getThumbnail(), "no live game, no artwork");
    }

    @Test void epicGamesAreSplitEvenlyIntoEmbedsOfAtMostThree() {
        for (int n = 2; n <= 10; n++) {
            List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
            for (int i = 0; i < n; i++) members.add(member("g" + i, LIVE));
            var units = FreebieAnnouncementCards.units(members);
            var sizes = units.stream().map(List::size).toList();
            assertEquals(n, sizes.stream().mapToInt(Integer::intValue).sum());
            assertTrue(sizes.stream().allMatch(s -> s <= FreebieAnnouncementCards.EPIC_GROUP), n + ": " + sizes);
            assertEquals((n + 2) / 3, sizes.size(), n + ": fewest embeds");
            assertTrue(Collections.max(sizes) - Collections.min(sizes) <= 1, n + ": even split " + sizes);
            assertEquals(members, units.stream().flatMap(List::stream).toList(), "games keep their order");
        }
        assertEquals(List.of(2, 2), FreebieAnnouncementCards.units(List.of(member("a", LIVE), member("b", LIVE), member("c", LIVE),
                member("d", LIVE))).stream().map(List::size).toList());
    }

    @Test void otherStoresKeepTheirOwnCardBesideAnEpicGroup() {
        var members = List.of(steamMember("s1"), member("a", LIVE), steamMember("s2"), member("b", LIVE));
        var embeds = FreebieAnnouncementCards.embeds(members, now);
        assertEquals(3, embeds.size(), "Steam, Epic (a + b), Steam");
        assertTrue(embeds.get(0).getTitle().contains("Steam s1"));
        assertEquals(List.of("Game a", "Game b"), embeds.get(1).getFields().stream().map(MessageEmbed.Field::getName).toList());
        assertTrue(embeds.get(2).getTitle().contains("Steam s2"));
        everyEmbedCarriesAMemberFooter(embeds, members);
        // A lone Epic game among other stores keeps the compact card.
        var lone = FreebieAnnouncementCards.embeds(List.of(steamMember("s1"), member("a", LIVE)), now);
        assertTrue(lone.get(1).getTitle().contains("Game a") && lone.get(1).getFields().isEmpty());
        assertTrue(FreebieMessages.isAlertFooter(lone.get(1), member("a", LIVE).reference()));
    }

    @Test void aGroupListsEachGamesClaimLinkPriceAndEnd() {
        var withWorth = new FreebieAnnouncementCards.Member(new FreebieOffer("epic:w", "w", FreebieStore.EPIC, "PC, Epic Games Store", "Worth",
                "Short.", "", Optional.of("$19.99"), Optional.empty(), URI.create("https://store.epicgames.com/en-US/p/w"),
                URI.create("https://store.epicgames.com/en-US/p/w"), Optional.of(Instant.parse("2026-10-29T15:00:00Z")),
                Instant.parse("2026-10-22T15:00:00Z"), FreebieOffer.State.APPROVED, Optional.empty(), Optional.empty(),
                Optional.of("owner"), true, false, 2), facts("w0", List.of(), Optional.empty()), "epic:w|" + CHANNEL, LIVE);
        var embeds = FreebieAnnouncementCards.embeds(List.of(withWorth, member("b", LIVE)), now);
        assertEquals(1, embeds.size());
        String value = embeds.getFirst().getFields().getFirst().getValue();
        assertTrue(value.contains("~~$19.99~~ → **FREE**"), value);
        assertTrue(value.contains("[Claim on PC](https://store.epicgames.com/en-US/p/w)"), value);
        assertTrue(value.contains("Ends <t:" + Instant.parse("2026-10-29T15:00:00Z").getEpochSecond() + ":R>"), value);
        assertTrue(embeds.getFirst().getFields().stream().allMatch(f -> f.getValue().length() <= MessageEmbed.VALUE_MAX_LENGTH));
        assertEquals("https://cdn1.epicgames.com/b.jpg", embeds.getFirst().getThumbnail().getUrl(), "the first live game with artwork");
    }

    @Test void checkoutStaysOffUnlessEnabledAndNeedsAnExactIdentity() {
        var members = List.of(member("a", LIVE), member("b", LIVE));
        assertTrue(labels(FreebieAnnouncementCards.components(members, false, now)).stream().noneMatch(l -> l.contains("PC games")));
        var noIdentity = new FreebieAnnouncementCards.Member(epic("b", "Game b"), FreebieFacts.NONE, "epic:b|" + CHANNEL, LIVE);
        assertTrue(FreebieAnnouncementCards.combined(List.of(noIdentity), now).isEmpty());
        assertEquals("Instant Checkout: Game a", FreebieAnnouncementCards.combined(List.of(member("a", LIVE), noIdentity), now).orElseThrow().getLabel());
    }

    @Test void aMultiGamePageNamesTheOneGameItsCheckoutBuys() {
        var members = List.of(member("a", ENDED), member("b", LIVE), member("c", ENDED));
        var labels = labels(FreebieAnnouncementCards.edit(members, true, now).getComponents().stream().map(c -> (ActionRow) c).toList());
        assertEquals(List.of("Instant Checkout: Game b", "Claim Game b"), labels);
    }

    @Test void aSingleGameGetsInstantCheckoutAfterItsClaimButton() {
        var m = member("a", LIVE);
        var labels = labels(FreebieAnnouncementCards.components(List.of(m), true, now));
        assertEquals(List.of(FreebieCards.claimButton(m.offer()).getLabel(), "Instant Checkout"), labels);
        assertTrue(labels(FreebieAnnouncementCards.components(List.of(m), false, now)).stream().noneMatch(l -> l.contains("Checkout")));
        assertTrue(labels(FreebieAnnouncementCards.components(List.of(member("a", ENDED)), true, now)).stream().noneMatch(l -> l.contains("Checkout")));
    }

    @Test void reviewedMobileOffersGetTheirOwnLinksAndNeverImplyPcOwnership() {
        var android = new FreebieFacts.Variant(EgDataIntake.Platform.ANDROID, new EpicLinks.Offer("ns", "android1"), "home-android-9ba07f",
                Instant.parse("2026-10-22T15:55:00Z"), Instant.parse("2026-10-29T15:00:00Z"),
                Optional.of(new FreebieRegions.Evidence("egdata", now, Optional.of(List.of("SA", "TW")), Optional.empty(), false)), true);
        var unreviewed = new FreebieFacts.Variant(EgDataIntake.Platform.IOS, new EpicLinks.Offer("ns", "ios1"), "home-ios-69cfcf",
                Instant.parse("2026-10-22T15:48:00Z"), Instant.parse("2026-10-29T15:00:00Z"), Optional.empty(), false);
        var m = new FreebieAnnouncementCards.Member(epic("home", "Home - A Unique Horror Adventure"),
                facts("pc1", List.of(android, unreviewed), Optional.empty()), "epic:home|" + CHANNEL, LIVE);
        var data = FreebieAnnouncementCards.create(List.of(m), Optional.empty(), false, now);
        var labels = labels(data.getComponents().stream().map(c -> (ActionRow) c).toList());
        assertTrue(labels.contains("Claim Android version"));
        assertFalse(labels.contains("Claim iOS version"), "a variant found after approval is not added automatically");
        String mobile = data.getEmbeds().getFirst().getFields().stream().filter(f -> f.getName().equals("Mobile")).findFirst().orElseThrow().getValue();
        assertTrue(mobile.contains("a PC claim does not include mobile") && mobile.contains("Saudi Arabia, Taiwan"));
        assertEquals("", data.getContent(), "no ping without a saved role");
    }
}
