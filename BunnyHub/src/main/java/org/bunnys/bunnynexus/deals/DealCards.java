package org.bunnys.bunnynexus.deals;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.handler.cards.Card;

import java.util.*;

/**
 * The deals cards (docs/deals/04 and 05): the paged /deals list, the deals board and the "not available" card. Plain
 * cards with launcher emojis; every card with prices says they are US prices in USD, and every link goes straight
 * to the store's own page. Pure.
 */
public final class DealCards {
    public static final int PER_PAGE = 8;
    public static final String PREFIX = "deals";
    static final String ALL = "all";
    /** Under every heading: what "good" means, and the order. */
    static final String SUBTITLE = "-# 75% off or more · well reviewed · cheapest first";
    /** Above every footer: the stores price by region, so a reader elsewhere may see another price or currency. */
    static final String REGIONAL = "-# Prices are US store prices in USD. Steam and GOG price by region, so yours may be different.";
    static final String CREDIT = "Prices from Steam and GOG";

    private DealCards() {}

    public static MessageCreateData list(DealSnapshot snapshot, Optional<DealStore> store, int page) {
        return listCard(snapshot, store, page).message();
    }

    public static MessageEditData listEdit(DealSnapshot snapshot, Optional<DealStore> store, int page) {
        return listCard(snapshot, store, page).edit();
    }

    public static int pages(DealSnapshot snapshot, Optional<DealStore> store) {
        return Math.max(1, (snapshot.forStore(store).size() + PER_PAGE - 1) / PER_PAGE);
    }

    static Card listCard(DealSnapshot snapshot, Optional<DealStore> store, int page) {
        List<Deal> deals = snapshot.forStore(store);
        String heading = "### " + store.map(s -> "Good deals on " + s.badge()).orElse("Good deals right now");
        long fetched = snapshot.fetchedAt().getEpochSecond();
        if (deals.isEmpty()) {
            return Card.builder().text(heading + "\nNo deals" + store.map(s -> " on " + s.label).orElse("")
                            + " pass the bar right now. Deals are checked every 3 hours.")
                    .text(REGIONAL + "\n-# Updated <t:" + fetched + ":R> · " + CREDIT).build();
        }
        int pages = pages(snapshot, store);
        int shown = Math.clamp(page, 0, pages - 1);
        var card = Card.builder().text(heading + "\n" + SUBTITLE).divider();
        for (Deal deal : deals.subList(shown * PER_PAGE, Math.min(deals.size(), (shown + 1) * PER_PAGE))) section(card, deal);
        card.divider().text(REGIONAL + "\n-# Page " + (shown + 1) + " of " + pages + " · updated <t:" + fetched + ":R> · " + CREDIT);
        if (pages > 1) card.buttons(paging(store, shown, pages));
        return card.build();
    }

    /** Previous / Next. A disabled button gets its own ID so the two never collide. */
    static List<Button> paging(Optional<DealStore> store, int page, int pages) {
        String filter = PREFIX + ":page:" + store.map(s -> s.choice).orElse(ALL) + ":";
        var previous = page == 0 ? Button.secondary(filter + "prev-off", "Previous").asDisabled()
                : Button.secondary(filter + (page - 1), "Previous");
        var next = page >= pages - 1 ? Button.secondary(filter + "next-off", "Next").asDisabled()
                : Button.secondary(filter + (page + 1), "Next");
        return List.of(previous, next);
    }

    /** Games per launcher on the board. */
    public static final int PER_LAUNCHER = 4;
    /** Launchers in board order. */
    static final List<DealStore> BOARD_ORDER = List.of(DealStore.STEAM, DealStore.GOG);

    /** Why a board is (re)posted: the new deals it names, and the role pinged for them, if any. */
    public record Notice(Optional<String> pingRole, List<Deal> newDeals) {
        public static final Notice NONE = new Notice(Optional.empty(), List.of());
    }

    /** What the board shows: the {@value #PER_LAUNCHER} cheapest of each launcher that has any, in board order. */
    public static Map<DealStore, List<Deal>> boardDeals(DealSnapshot snapshot) {
        Map<DealStore, List<Deal>> shown = new LinkedHashMap<>();
        for (DealStore store : BOARD_ORDER) {
            var deals = snapshot.forStore(Optional.of(store));
            if (!deals.isEmpty()) shown.put(store, deals.subList(0, Math.min(PER_LAUNCHER, deals.size())));
        }
        return shown;
    }

    /**
     * The deals board a server picks in setup: one block per launcher, and one button per launcher that shows anyone
     * that launcher's full list privately. Shared by everyone, so it has no paging of its own.
     */
    public static MessageCreateData board(DealSnapshot snapshot, Notice notice) { return boardCard(snapshot, notice).message(); }

    /** Edits drop the "new deals" line: it belongs to the post that announced them. */
    public static MessageEditData boardEdit(DealSnapshot snapshot) { return boardCard(snapshot, Notice.NONE).edit(); }

    static Card boardCard(DealSnapshot snapshot, Notice notice) {
        var card = Card.builder();
        String names = newNames(notice.newDeals());
        if (notice.pingRole().isPresent() && !names.isEmpty())
            card.above("<@&" + notice.pingRole().get() + "> New deals: " + names).mentionRoles(notice.pingRole().get());
        card.text("### Good deals right now\n" + SUBTITLE
                + (notice.pingRole().isEmpty() && !names.isEmpty() ? "\n**New:** " + names : "")).divider();
        var shown = boardDeals(snapshot);
        if (shown.isEmpty()) card.text("No deals pass the bar right now. This board updates every few hours.");
        String footer = REGIONAL + "\n-# Updated <t:" + snapshot.fetchedAt().getEpochSecond() + ":R> · " + CREDIT + " · Tap a launcher for all its deals";
        List<Button> launchers = new ArrayList<>();
        for (var entry : shown.entrySet()) {
            int total = snapshot.forStore(Optional.of(entry.getKey())).size();
            var block = new StringBuilder(entry.getKey().emoji.map(e -> e + " ").orElse("") + "**" + entry.getKey().label + "**");
            for (Deal deal : entry.getValue()) {
                String line = "\n" + compactLine(deal);
                // Keep room for the footer: the board must always fit Discord's 4000 characters.
                if (card.remainingText() - block.length() - line.length() - footer.length() < 50) break;
                block.append(line);
            }
            card.text(block.toString());
            var button = Button.secondary(PREFIX + ":store:" + entry.getKey().choice, entry.getKey().label + " (" + total + ")");
            launchers.add(entry.getKey().emoji.map(e -> button.withEmoji(Emoji.fromFormatted(e))).orElse(button));
        }
        card.divider().text(footer);
        for (int i = 0; i < launchers.size(); i += 5) card.buttons(launchers.subList(i, Math.min(launchers.size(), i + 5)));
        return card.build();
    }

    /** "[Title](link) · **$1.99** ~~$39.99~~ · 95% off · ends <t:…:R>". */
    static String compactLine(Deal deal) {
        return "[" + title(deal, 60) + "](" + deal.link() + ") · **" + money(deal.saleCents()) + "** ~~" + money(deal.normalCents())
                + "~~ · " + deal.percentOff() + "% off" + deal.endsAt().map(end -> " · ends <t:" + end.getEpochSecond() + ":R>").orElse("");
    }

    /** "A, B, C and 2 more". */
    static String newNames(List<Deal> deals) {
        var names = deals.stream().limit(3).map(d -> "**" + text(d.title(), 60) + "**").toList();
        return String.join(", ", names) + (deals.size() > 3 ? " and " + (deals.size() - 3) + " more" : "");
    }

    public static Card unavailable() {
        return Card.builder().text("### Deals\nDeals aren't available right now. Try again in a little while.").build();
    }

    private static void section(Card.Builder card, Deal deal) {
        String link = deal.link().toString();
        card.section("**[" + title(deal, 100) + "](" + link + ")**\n" + line(deal), Button.link(link, "Open on " + deal.store().label));
    }

    /** "<emoji> Steam · **$1.99** ~~$39.99~~ · 95% off · 86% positive · ends <t:…:R>". */
    static String line(Deal deal) {
        List<String> parts = new ArrayList<>(List.of(deal.store().badge(), "**" + money(deal.saleCents()) + "** ~~" + money(deal.normalCents()) + "~~",
                deal.percentOff() + "% off", deal.ratingText()));
        deal.endsAt().ifPresent(end -> parts.add("ends <t:" + end.getEpochSecond() + ":R>"));
        return String.join(" · ", parts);
    }

    /** Square brackets in a title would end the markdown link early. */
    private static String title(Deal deal, int max) {
        return text(deal.title().replace('[', '(').replace(']', ')'), max);
    }

    static String money(long cents) { return String.format(Locale.ROOT, "$%d.%02d", cents / 100, cents % 100); }

    /** Escapes markdown and caps length (same rules as the free-game cards). */
    static String text(String raw, int max) {
        if (raw == null || raw.isBlank()) return "—";
        String clean = MarkdownSanitizer.escape(raw.replace("\r", "").replace("\n", " ").strip());
        if (clean.length() <= max) return clean;
        int cut = max - 1;
        if (Character.isHighSurrogate(clean.charAt(cut - 1))) cut--;
        while (cut > 0 && clean.charAt(cut - 1) == '\\') cut--;
        return clean.substring(0, cut) + "…";
    }
}
