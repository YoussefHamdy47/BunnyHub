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
    static final String SUBTITLE = "-# 75% off or more · most loved first (high ratings from lots of players), then biggest discount, then lowest price";
    /** Above every footer: the stores price by region, so a reader elsewhere may see another price or currency. */
    static final String REGIONAL = "-# Prices are US store prices in USD. Stores price by region, so yours may be different.";
    static final String CREDIT = "Prices from each store";

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
        String heading = "### " + (deals.isEmpty() ? store.map(s -> "Good deals on " + s.badge()).orElse("Good deals right now")
                : count(deals.size()) + store.map(s -> " good deals on " + s.badge()).orElse(" good deals right now"));
        long fetched = snapshot.fetchedAt().getEpochSecond();
        if (deals.isEmpty()) {
            return Card.builder().text(heading + "\nNo deals" + store.map(s -> " on " + s.label).orElse("")
                            + " pass the bar right now. Deals are checked every 3 hours.")
                    .text(REGIONAL + "\n-# Updated <t:" + fetched + ":R> · " + CREDIT).build();
        }
        int pages = pages(snapshot, store);
        int shown = Math.clamp(page, 0, pages - 1);
        var card = Card.builder().text(heading + "\n" + SUBTITLE).divider();
        for (Deal deal : deals.subList(shown * PER_PAGE, Math.min(deals.size(), (shown + 1) * PER_PAGE))) section(card, deal, snapshot.lowest(deal));
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
    static final List<DealStore> BOARD_ORDER = List.of(DealStore.values());

    /** Why a board is (re)posted: the new deals it names, and the role pinged for them, if any. */
    public record Notice(Optional<String> pingRole, List<Deal> newDeals) {
        public static final Notice NONE = new Notice(Optional.empty(), List.of());
    }

    /** What the board shows: the {@value #PER_LAUNCHER} highest-ranked deals of each launcher that has any, in board order. */
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
    public static MessageCreateData board(DealSnapshot snapshot, Notice notice) { return board(snapshot, notice, Optional.empty()); }

    /** {@code role}: the board's ping role, offered as a "Ping Me for Deals" button; a second press removes it. */
    public static MessageCreateData board(DealSnapshot snapshot, Notice notice, Optional<String> role) {
        return boardCard(snapshot, notice, role).message();
    }

    /** Edits drop the "new deals" line: it belongs to the post that announced them. */
    public static MessageEditData boardEdit(DealSnapshot snapshot) { return boardEdit(snapshot, Optional.empty()); }

    public static MessageEditData boardEdit(DealSnapshot snapshot, Optional<String> role) { return boardCard(snapshot, Notice.NONE, role).edit(); }

    static Card boardCard(DealSnapshot snapshot, Notice notice) { return boardCard(snapshot, notice, Optional.empty()); }

    static Card boardCard(DealSnapshot snapshot, Notice notice, Optional<String> role) {
        var card = Card.builder();
        String names = newNames(notice.newDeals());
        if (notice.pingRole().isPresent() && !names.isEmpty())
            card.above("<@&" + notice.pingRole().get() + "> New deals: " + names).mentionRoles(notice.pingRole().get());
        int all = snapshot.deals().size();
        card.text("### " + (all == 0 ? "Good deals right now" : count(all) + " good deals right now") + "\n" + SUBTITLE
                + (notice.pingRole().isEmpty() && !names.isEmpty() ? "\n**New:** " + names : "")).divider();
        var shown = boardDeals(snapshot);
        if (shown.isEmpty()) card.text("No deals pass the bar right now. This board updates every few hours.");
        String footer = REGIONAL + "\n-# Updated <t:" + snapshot.fetchedAt().getEpochSecond() + ":R> · " + CREDIT
                + " · The buttons open every deal, only for you";
        List<Button> launchers = new ArrayList<>();
        if (!shown.isEmpty()) launchers.add(Button.primary(PREFIX + ":store:" + ALL, "See All " + all + " Deals"));
        for (var entry : shown.entrySet()) {
            int total = snapshot.forStore(Optional.of(entry.getKey())).size();
            var block = new StringBuilder(entry.getKey().emoji.map(e -> e + " ").orElse("") + "**" + entry.getKey().label + "**"
                    + (total > entry.getValue().size() ? " · top " + entry.getValue().size() + " of " + total : ""));
            int listed = 0;
            for (Deal deal : entry.getValue()) {
                String line = "\n" + compactLine(deal, snapshot.lowest(deal));
                // Keep room for the "more" line and the footer: the board must always fit Discord's 4000 characters.
                if (card.remainingText() - block.length() - line.length() - footer.length() < 120) break;
                block.append(line);
                listed++;
            }
            if (total > listed)
                block.append("\n-# + ").append(total - listed).append(" more on ").append(entry.getKey().label)
                        .append(" · tap **").append(entry.getKey().label).append(" (").append(total).append(")** below");
            card.text(block.toString());
            var button = Button.secondary(PREFIX + ":store:" + entry.getKey().choice, entry.getKey().label + " (" + total + ")");
            launchers.add(entry.getKey().emoji.map(e -> button.withEmoji(Emoji.fromFormatted(e))).orElse(button));
        }
        if (!shown.isEmpty()) role.ifPresent(r -> launchers.add(Button.secondary(
                org.bunnys.bunnynexus.freebies.FreebieSubscriptions.ROLE_BUTTON + ":" + r,
                org.bunnys.bunnynexus.freebies.FreebieSubscriptions.DEALS_ROLE_BUTTON_LABEL)));
        card.divider().text(footer);
        for (int i = 0; i < launchers.size(); i += 5) card.buttons(launchers.subList(i, Math.min(launchers.size(), i + 5)));
        return card.build();
    }

    /**
     * A post of the posts style: these deals as sections with their own store button, then the button that opens every
     * deal privately and the role button. {@code first}: the opening post ("Good deals right now"); later ones say how
     * many deals are new. {@code ping}: the role mentioned above the card, only when this post may ping.
     */
    public static MessageCreateData post(DealSnapshot snapshot, List<Deal> deals, boolean first, Optional<String> ping, Optional<String> role) {
        return postCard(snapshot, deals, first, ping, role).message();
    }

    /** Most deals one post lists; Discord allows 40 components and each deal is a section (3). */
    static final int PER_POST = 8;

    static Card postCard(DealSnapshot snapshot, List<Deal> deals, boolean first, Optional<String> ping, Optional<String> role) {
        var card = Card.builder();
        var listed = deals.subList(0, Math.min(PER_POST, deals.size()));
        ping.ifPresent(r -> card.above("<@&" + r + "> " + (listed.size() == 1 ? "A new deal" : listed.size() + " new deals")).mentionRoles(r));
        card.text("### " + (first ? "Good deals right now" : listed.size() == 1 ? "New deal" : listed.size() + " new deals") + "\n" + SUBTITLE).divider();
        for (Deal deal : listed) section(card, deal, snapshot.lowest(deal));
        int all = snapshot.deals().size();
        card.divider().text(REGIONAL + "\n-# Prices checked <t:" + snapshot.fetchedAt().getEpochSecond() + ":R> · " + CREDIT
                + (first ? " · New deals get a post of their own here" : ""));
        List<Button> buttons = new ArrayList<>();
        if (all > 0) buttons.add(Button.primary(PREFIX + ":store:" + ALL, "See All " + all + " Deals"));
        role.ifPresent(r -> buttons.add(Button.secondary(org.bunnys.bunnynexus.freebies.FreebieSubscriptions.ROLE_BUTTON + ":" + r,
                org.bunnys.bunnynexus.freebies.FreebieSubscriptions.DEALS_ROLE_BUTTON_LABEL)));
        if (!buttons.isEmpty()) card.buttons(buttons);
        return card.build();
    }

    /** "[Title](link) · **$1.99** ~~$39.99~~ · 95% off · 96% positive (120K reviews) · ends <t:…:R>". */
    static String compactLine(Deal deal) { return compactLine(deal, false); }

    /** {@code low}: at the lowest price tracked for it ({@link DealPrices}). */
    static String compactLine(Deal deal, boolean low) {
        return "[" + title(deal, 60) + "](" + deal.link() + ") · **" + money(deal.saleCents()) + "** ~~" + money(deal.normalCents())
                + "~~ · " + deal.percentOff() + "% off" + (low ? " · " + LOWEST : "") + " · " + rating(deal)
                + deal.endsAt().map(end -> " · ends <t:" + end.getEpochSecond() + ":R>").orElse("");
    }

    /** The badge of a deal at the lowest price tracked for it. */
    static final String LOWEST = "**Lowest price we've seen**";

    /**
     * Star emojis to the nearest quarter, then "96% positive (120K reviews)" / "rated 4.6/5 (1.2K reviews)": the
     * rating and how many people it comes from. Without the emojis (just after a start) the text alone.
     */
    static String rating(Deal deal) {
        String stars = org.bunnys.bunnynexus.ratings.Stars.of(stars(deal));
        return (stars.isEmpty() ? "" : stars + " ") + deal.ratingText() + " (" + count(deal.reviews()) + " reviews)";
    }

    /** Steam's percent positive or GOG's stars, out of 5. */
    static double stars(Deal deal) {
        return deal.store().stars ? deal.rating() / 10.0 : org.bunnys.bunnynexus.ratings.Stars.fromPercent(deal.rating());
    }

    /** 950, 1.2K, 12K, 120K, 1.3M: short enough for a deal line. */
    static String count(long n) { return org.bunnys.bunnynexus.ratings.Stars.count(n); }

    /** "A, B, C and 2 more". */
    static String newNames(List<Deal> deals) {
        var names = deals.stream().limit(3).map(d -> "**" + text(d.title(), 60) + "**").toList();
        return String.join(", ", names) + (deals.size() > 3 ? " and " + (deals.size() - 3) + " more" : "");
    }

    public static Card unavailable() {
        return Card.builder().text("### Deals\nDeals aren't available right now. Try again in a little while.").build();
    }

    private static void section(Card.Builder card, Deal deal, boolean low) {
        String link = deal.link().toString();
        card.section("**[" + title(deal, 100) + "](" + link + ")**\n" + line(deal, low), Button.link(link, "Open on " + deal.store().label));
    }

    static String line(Deal deal) { return line(deal, false); }

    /** "<emoji> Steam · **$1.99** ~~$39.99~~ · 95% off · ★★★★¾ 96% positive (12K reviews) · ends <t:…:R>". */
    static String line(Deal deal, boolean low) {
        List<String> parts = new ArrayList<>(List.of(deal.store().badge(), "**" + money(deal.saleCents()) + "** ~~" + money(deal.normalCents()) + "~~",
                deal.percentOff() + "% off"));
        if (low) parts.add(LOWEST);
        parts.add(rating(deal));
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
