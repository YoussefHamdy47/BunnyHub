package org.bunnys.bunnynexus.deals;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.bunnys.handler.cards.Card;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DealCardsTest {
    static List<Button> buttons(Card card) {
        return MessageComponentTree.of(card.components()).findAll(Button.class);
    }

    static List<String> paging(Card card) {
        return buttons(card).stream().filter(b -> b.getCustomId() != null).map(b -> b.getCustomId() + (b.isDisabled() ? " (off)" : "")).toList();
    }

    /** Every link on a card goes to Steam or GOG itself. */
    static void onlyStoreLinks(Card card) {
        String text = card.plainText();
        assertFalse(text.toLowerCase().contains("cheapshark"));
        for (var b : buttons(card)) if (b.getUrl() != null)
            assertTrue(Set.of("store.steampowered.com", "www.gog.com").contains(URI.create(b.getUrl()).getHost()), b.getUrl());
    }

    @Test void theFirstPageShowsTopDealsWithStoreLinks() throws Exception {
        var card = DealCards.listCard(DealFixtures.snapshot(), Optional.empty(), 0);
        String text = card.plainText();
        for (String part : List.of("58 good deals right now", "most loved first", "then biggest discount, then lowest price", "reviews)",
                "Stores price by region, so yours may be different.", "Prices from each store", "Page 1 of 8"))
            assertTrue(text.contains(part), part);
        assertFalse(text.contains("cheapest first"));
        assertTrue(text.contains(DealFixtures.snapshot().deals().getFirst().title()));
        assertEquals(8, buttons(card).stream().filter(b -> b.getUrl() != null).count());
        assertTrue(buttons(card).stream().anyMatch(b -> "Open on Steam".equals(b.getLabel())));

        assertEquals(List.of("deals:page:all:prev-off (off)", "deals:page:all:1"), paging(card));
        onlyStoreLinks(card);
    }

    @Test void theLastPageAndEveryPageFitDiscordsLimits() throws Exception {
        var snapshot = DealFixtures.snapshot();
        assertEquals(8, DealCards.pages(snapshot, Optional.empty()));
        assertEquals(List.of("deals:page:all:6", "deals:page:all:next-off (off)"), paging(DealCards.listCard(snapshot, Optional.empty(), 7)));
        assertEquals(paging(DealCards.listCard(snapshot, Optional.empty(), 7)), paging(DealCards.listCard(snapshot, Optional.empty(), 99)),
                "an out-of-range page shows the last one");
        for (int page = 0; page < 8; page++) {
            var card = DealCards.listCard(snapshot, Optional.empty(), page);
            assertTrue(card.componentCount() <= Card.MAX_COMPONENTS, "components on page " + page + ": " + card.componentCount());
            assertTrue(card.textLength() <= Card.MAX_TEXT);
            onlyStoreLinks(card);
        }
    }

    @Test void aStoreFilterShowsOnlyThatStore() throws Exception {
        var snapshot = DealFixtures.snapshot();
        var gog = DealCards.listCard(snapshot, Optional.of(DealStore.GOG), 0);
        assertTrue(gog.plainText().contains("33 good deals on <:GOG:1572185523924115587> GOG"));
        assertTrue(buttons(gog).stream().filter(b -> b.getUrl() != null).allMatch(b -> b.getUrl().startsWith("https://www.gog.com/")));
        var empty = DealCards.listCard(new DealSnapshot(List.of(), DealFixtures.FETCHED, 0), Optional.of(DealStore.STEAM), 0);
        assertTrue(empty.plainText().contains("No deals on Steam pass the bar right now"));
        assertTrue(buttons(empty).isEmpty());
    }

    @Test void titlesCannotBreakTheMarkdown() {
        var deal = new Deal("steam:1", DealStore.STEAM, "*Bold* [x](https://evil.example) _y_", URI.create("https://store.steampowered.com/app/1/"),
                199, 1999, 90, 90, 5000, Optional.empty());
        String text = DealCards.listCard(new DealSnapshot(List.of(deal), DealFixtures.FETCHED, 1), Optional.empty(), 0).plainText();
        assertFalse(text.contains("[x]"));
        assertTrue(text.contains("\\*Bold\\*"));
    }

    @Test void aBoardWithAPingRoleOffersAGetPingedButton() throws Exception {
        var snapshot = DealFixtures.snapshot();
        var card = DealCards.boardCard(snapshot, DealCards.Notice.NONE, Optional.of("500000000000000001"));
        var last = buttons(card).getLast();
        assertEquals("freebie_role:500000000000000001", last.getCustomId());
        assertEquals("Ping Me for Deals", last.getLabel());
        assertEquals(4, buttons(card).size(), "See all, Steam, GOG, Get pinged");
        assertTrue(buttons(DealCards.boardCard(snapshot, DealCards.Notice.NONE)).stream().noneMatch(b -> b.getCustomId().startsWith("freebie_role")));
    }

    @Test void reviewCountsAreShort() {
        assertEquals("950", DealCards.count(950));
        assertEquals("1K", DealCards.count(1000));
        assertEquals("1.2K", DealCards.count(1250));
        assertEquals("12K", DealCards.count(12_900));
        assertEquals("120K", DealCards.count(120_400));
        assertEquals("1.3M", DealCards.count(1_340_000));
    }

    @Test void moneyIsDollarsAndCents() {
        assertEquals("$1.99", DealCards.money(199));
        assertEquals("$0.05", DealCards.money(5));
        assertEquals("$10.00", DealCards.money(1000));
    }

    @Test void theBoardHasABlockAndAButtonPerLauncher() throws Exception {
        var snapshot = DealFixtures.snapshot();
        var card = DealCards.boardCard(snapshot, DealCards.Notice.NONE);
        String text = card.plainText();
        assertTrue(text.contains("Prices from each store") && text.contains("price by region"));
        var shown = DealCards.boardDeals(snapshot);
        for (var store : List.of(DealStore.STEAM, DealStore.GOG)) {
            var top = snapshot.forStore(Optional.of(store)).getFirst();
            assertEquals(top, shown.get(store).getFirst());
            assertTrue(text.contains(top.title()));
        }
        assertTrue(shown.values().stream().allMatch(list -> list.size() == DealCards.PER_LAUNCHER));
        assertEquals(List.of("deals:store:all", "deals:store:steam", "deals:store:gog"), buttons(card).stream().map(Button::getCustomId).toList());
        assertEquals("See All 58 Deals", buttons(card).getFirst().getLabel());
        assertEquals("Steam (25)", buttons(card).get(1).getLabel());
        assertTrue(buttons(card).stream().skip(1).allMatch(b -> b.getEmoji() != null), "every launcher button has its emoji");
        // Nobody should think the four shown are all there is.
        for (String part : List.of("58 good deals right now", "top 4 of 25", "top 4 of 33", "+ 21 more on Steam", "+ 29 more on GOG",
                "tap **Steam (25)** below", "The buttons open every deal"))
            assertTrue(text.contains(part), part + " in " + text);
        assertTrue(card.componentCount() <= Card.MAX_COMPONENTS && card.textLength() <= Card.MAX_TEXT);
        onlyStoreLinks(card);
        var empty = DealCards.boardCard(new DealSnapshot(List.of(), DealFixtures.FETCHED, 0), DealCards.Notice.NONE).plainText();
        assertTrue(empty.contains("No deals pass the bar right now"));
    }

    @Test void aNoticeNamesTheNewGamesAndPingsOnlyWithARole() throws Exception {
        var snapshot = DealFixtures.snapshot();
        var fresh = snapshot.deals().subList(0, 5);
        var pinged = DealCards.board(snapshot, new DealCards.Notice(Optional.of("500000000000000001"), fresh));
        String json = pinged.toData().toString();
        assertTrue(json.contains("<@&500000000000000001> New deals: **" + fresh.getFirst().title() + "**"), json);
        assertTrue(json.contains(" and 2 more"));
        assertEquals(Set.of("500000000000000001"), pinged.getMentionedRoles());
        var quiet = DealCards.board(snapshot, new DealCards.Notice(Optional.empty(), fresh));
        assertTrue(quiet.getMentionedRoles().isEmpty());
        assertTrue(quiet.toData().toString().contains("**New:**"));
    }
}
