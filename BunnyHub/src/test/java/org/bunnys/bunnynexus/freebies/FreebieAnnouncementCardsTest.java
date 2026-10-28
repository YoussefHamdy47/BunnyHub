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
        assertEquals(List.of("Claim Epic offer", "Offer details"), labels(data.getComponents().stream().map(c -> (ActionRow) c).toList()));
        String region = data.getEmbeds().getFirst().getFields().stream().filter(f -> "REGION".equals(f.getName())).findFirst().orElseThrow().getValue();
        assertTrue(region.contains("not fully verified"));
    }

    @Test void aReleaseOfFiveFitsOneMessageWithEachFooterAndAClaimPerGame() {
        List<FreebieAnnouncementCards.Member> members = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d", "e")) members.add(member(id, LIVE));
        var data = FreebieAnnouncementCards.create(members, Optional.of(ROLE), true, now);
        assertEquals(5, data.getEmbeds().size());
        assertTrue(data.getEmbeds().stream().mapToInt(MessageEmbed::getLength).sum() <= FreebieAnnouncementCards.EMBED_TOTAL);
        for (var m : members)
            assertTrue(data.getEmbeds().stream().anyMatch(e -> FreebieMessages.isAlertFooter(e, m.reference())), m.deliveryId());
        var labels = labels(data.getComponents().stream().map(c -> (ActionRow) c).toList());
        assertEquals("Claim all PC games (5)", labels.getFirst());
        assertEquals(6, labels.size());
    }

    @Test void endedMembersLoseTheirClaimAndShrinkTheCombinedCheckout() {
        var members = List.of(member("a", LIVE), member("b", ENDED), member("c", LIVE));
        var edit = FreebieAnnouncementCards.edit(members, true, now);
        var labels = labels(edit.getComponents().stream().map(c -> (ActionRow) c).toList());
        assertEquals(List.of("Claim eligible PC games (2)", "Claim Game a", "Claim Game c"), labels);
        assertTrue(edit.getEmbeds().get(1).getTitle().startsWith("Ended: "));
        assertTrue(FreebieMessages.isAlertFooter(edit.getEmbeds().get(1), members.get(1).reference()), "the footer reference survives the edit");
        assertTrue(edit.getAllowedMentions().isEmpty());
        assertFalse(edit.toData().hasKey("content"), "content (the original ping text) is untouched while a game is live");
    }

    @Test void whenEveryGameEndedTheContentIsClearedAndNoClaimRemains() {
        var edit = FreebieAnnouncementCards.edit(List.of(member("a", ENDED), member("b", WITHDRAWN)), true, now);
        assertTrue(edit.toData().hasKey("content"));
        assertEquals("", edit.getContent());
        assertTrue(labels(edit.getComponents().stream().map(c -> (ActionRow) c).toList()).stream().noneMatch(l -> l.startsWith("Claim")));
        assertTrue(edit.getEmbeds().get(1).getDescription().contains(FreebieLifecycleMessages.WITHDRAWN_TEXT));
    }

    @Test void checkoutStaysOffUnlessEnabledAndNeedsTwoExactIdentities() {
        var members = List.of(member("a", LIVE), member("b", LIVE));
        assertTrue(labels(FreebieAnnouncementCards.components(members, false, now)).stream().noneMatch(l -> l.contains("PC games")));
        var noIdentity = new FreebieAnnouncementCards.Member(epic("b", "Game b"), FreebieFacts.NONE, "epic:b|" + CHANNEL, LIVE);
        assertTrue(FreebieAnnouncementCards.combined(List.of(member("a", LIVE), noIdentity), now).isEmpty());
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
        String mobile = data.getEmbeds().getFirst().getFields().stream().filter(f -> f.getName().equals("ALSO FREE ON MOBILE")).findFirst().orElseThrow().getValue();
        assertTrue(mobile.contains("a PC claim does not include mobile") && mobile.contains("Saudi Arabia, Taiwan"));
        assertEquals("", data.getContent(), "no ping without a saved role");
    }
}
