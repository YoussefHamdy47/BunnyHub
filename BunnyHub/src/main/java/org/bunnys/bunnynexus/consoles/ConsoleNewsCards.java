package org.bunnys.bunnynexus.consoles;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.handler.cards.Card;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * The PS Plus and Game Pass posts. Plain cards: they say which subscription the games need, and every link goes to
 * PlayStation's or Microsoft's own pages. Pure.
 */
final class ConsoleNewsCards {
    static final int PER_SHELF = 10, IMAGES = 4;
    static final String GAME_PASS_PAGE = "https://www.xbox.com/en-US/xbox-game-pass/games";

    private ConsoleNewsCards() {}

    static MessageCreateData psPlus(PsPlusPost post, Optional<String> role) { return psPlusCard(post, role).message(); }

    /** Games shown with their own picture, platforms, rating and blurb; any more are listed by name. */
    static final int PS_DETAILED = 8;

    static Card psPlusCard(PsPlusPost post, Optional<String> role) {
        var card = Card.builder();
        role.ifPresent(r -> card.above("<@&" + r + ">").mentionRoles(r));
        String when = post.from().map(from -> post.until().map(until -> "Claim from " + day(from) + " until " + day(until))
                .orElse("Available from " + day(from))).orElse("Out now");
        String keep = post.kind() == PsPlusPost.Kind.MONTHLY
                ? "Games you add to your library stay playable while you're a PS Plus member."
                : "Catalog games are playable while they're in the catalog and you're a member.";
        card.text("### " + FreebieStore.PS_PLUS.badge() + " " + text(post.heading(), 120) + "\n**" + when + "** · needs " + post.tiers())
                .divider();
        Map<String, PsPlusPost.Game> details = new HashMap<>();
        for (var game : post.details()) details.putIfAbsent(game.name(), game);
        var plain = new StringBuilder();
        int detailed = 0;
        boolean pictures = false;
        for (String name : post.games()) {
            var game = details.get(name);
            if (game == null || detailed == PS_DETAILED) { plain.append("\n- ").append(text(name, 100)); continue; }
            detailed++;
            var lines = new StringBuilder("**[" + text(name.replace('[', '(').replace(']', ')'), 100) + "](" + game.storeSearch() + ")**");
            if (!game.platforms().isEmpty()) lines.append(" · ").append(String.join(", ", game.platforms()));
            game.rating().ifPresent(r -> lines.append("\n").append(r.line()));
            game.blurb().ifPresent(b -> lines.append("\n-# ").append(text(b, PsPlusPost.MAX_BLURB + 5)));
            if (game.image().isPresent()) { card.section(lines.toString(), game.image().get()); pictures = true; }
            else card.text(lines.toString());
        }
        if (post.more()) plain.append("\n- and more");
        if (!plain.isEmpty()) card.text((detailed > 0 ? "**Also**" : "**Games**") + plain);
        // The post's banner only when the games have no pictures of their own.
        if (!pictures) post.image().ifPresent(card::image);
        List<Button> buttons = new ArrayList<>(List.of(Button.link(post.link().toString(), "Read on PlayStation Blog")));
        role.ifPresent(r -> buttons.add(roleButton(r)));
        boolean rated = post.details().stream().anyMatch(g -> g.rating().isPresent());
        card.divider().text("-# " + keep + (rated ? " Ratings are from Steam players." : "") + " From the PlayStation Blog.").buttons(buttons);
        return card.build();
    }

    static MessageCreateData gamePass(Map<GamePass.Shelf, List<GamePass.Game>> shelves, Optional<String> role) {
        return gamePassCard(shelves, role).message();
    }

    static Card gamePassCard(Map<GamePass.Shelf, List<GamePass.Game>> shelves, Optional<String> role) {
        var card = Card.builder();
        role.ifPresent(r -> card.above("<@&" + r + ">").mentionRoles(r));
        int total = shelves.values().stream().mapToInt(List::size).sum();
        card.text("### " + FreebieStore.GAME_PASS.badge() + " Xbox Game Pass update\n-# " + total + (total == 1 ? " game" : " games")
                + " · best rated first on each list");
        String footer = "-# Which games you get depends on your Game Pass plan. Ratings are from Microsoft Store players. "
                + "From Microsoft's Game Pass catalog.";
        List<String> images = new ArrayList<>();
        boolean rated = false;
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
            var games = shelves.getOrDefault(shelf, List.of()).stream()
                    .sorted(Comparator.comparingDouble((GamePass.Game g) -> g.ratings() > 0 ? g.stars() : -1).reversed()).toList();
            if (games.isEmpty()) continue;
            var block = new StringBuilder("**" + shelf.heading + "** (" + games.size() + ")");
            int listed = 0;
            for (GamePass.Game game : games) {
                if (listed == PER_SHELF) break;
                String line = "\n" + gamePassLine(game, shelf);
                // Room for the other lists and the footer: the post must fit Discord's 4000 characters.
                if (card.remainingText() - block.length() - line.length() - footer.length() < 400) break;
                block.append(line);
                listed++;
                rated |= game.ratings() > 0;
                if (shelf != GamePass.Shelf.LEAVING && images.size() < IMAGES) game.image().ifPresent(images::add);
            }
            if (games.size() > listed) block.append("\n-# + ").append(games.size() - listed).append(" more");
            card.text(block.toString());
        }
        if (!images.isEmpty()) card.gallery(images);
        List<Button> buttons = new ArrayList<>(List.of(Button.link(GAME_PASS_PAGE, "Browse Game Pass")));
        role.ifPresent(r -> buttons.add(roleButton(r)));
        card.divider().text(rated ? footer : footer.replace(" Ratings are from Microsoft Store players.", "")).buttons(buttons);
        return card.build();
    }

    /** "- [Title](link) · Shooter · ★★★★½ 4.6 (4.7K) · PC too · Series X|S optimized", plus the date for upcoming games. */
    static String gamePassLine(GamePass.Game game, GamePass.Shelf shelf) {
        List<String> parts = new ArrayList<>(List.of("- [" + text(game.title().replace('[', '(').replace(']', ')'), 70) + "](" + game.link() + ")"));
        game.genre().ifPresent(g -> parts.add(text(g, 30)));
        if (game.ratings() > 0) {
            String stars = org.bunnys.bunnynexus.ratings.Stars.of(game.stars());
            parts.add((stars.isEmpty() ? "" : stars + " ") + String.format(Locale.ROOT, "%.1f", game.stars())
                    + " (" + org.bunnys.bunnynexus.ratings.Stars.count(game.ratings()) + ")");
        }
        parts.addAll(game.tags().subList(0, Math.min(2, game.tags().size()))); // Two at most: lines stay short.
        if (shelf == GamePass.Shelf.COMING) game.release().filter(d -> d.isAfter(LocalDate.now(ZoneOffset.UTC))).ifPresent(d -> parts.add(day(d)));
        return String.join(" · ", parts);
    }

    static MessageCreateData freeWeekend(FreeWeekend weekend, Optional<String> role) { return freeWeekendCard(weekend, role).message(); }

    static Card freeWeekendCard(FreeWeekend weekend, Optional<String> role) {
        var card = Card.builder();
        role.ifPresent(r -> card.above("<@&" + r + ">").mentionRoles(r));
        card.text("### " + FreebieStore.GAME_PASS.badge() + " Xbox Free Play Days: " + text(weekend.dates(), 40)
                + "\nA few Xbox games you can play for free this weekend. It's a trial: you don't keep them, and most need a"
                + " Game Pass plan.\n-# The lineup is confirmed by Xbox during the week; the list linked below is kept up to date.");
        weekend.image().ifPresent(card::image);
        List<Button> buttons = new ArrayList<>(List.of(Button.link(weekend.link().toString(), "See This Weekend's Games")));
        role.ifPresent(r -> buttons.add(roleButton(r)));
        card.divider().text("-# From Pure Xbox").buttons(buttons);
        return card.build();
    }

    static MessageCreateData items(List<ConsoleItem> items, Optional<String> role) { return itemsCard(items, role).message(); }

    static Card itemsCard(List<ConsoleItem> items, Optional<String> role) {
        var card = Card.builder();
        role.ifPresent(r -> card.above("<@&" + r + ">").mentionRoles(r));
        var block = new StringBuilder("### Free in-game items for console games");
        for (ConsoleItem item : items.subList(0, Math.min(PER_SHELF, items.size()))) {
            block.append("\n- [").append(text(item.title().replace('[', '(').replace(']', ')'), 80)).append("](").append(item.link())
                    .append(") · ").append(String.join(", ", item.platforms()));
            item.ends().ifPresent(end -> block.append(" · ends <t:").append(end.getEpochSecond()).append(":R>"));
        }
        if (items.size() > PER_SHELF) block.append("\n-# + ").append(items.size() - PER_SHELF).append(" more");
        card.text(block + "\n-# Most are keys from partner sites: you may need an account there, and keys can run out.");
        List<String> images = items.stream().flatMap(i -> i.image().stream()).limit(IMAGES).toList();
        if (!images.isEmpty()) card.gallery(images);
        List<Button> buttons = new ArrayList<>(List.of(Button.link("https://" + ConsoleItem.HOST + "/", "More on GamerPower")));
        role.ifPresent(r -> buttons.add(roleButton(r)));
        card.divider().text("-# From GamerPower").buttons(buttons);
        return card.build();
    }

    private static Button roleButton(String role) {
        return Button.secondary(FreebieSubscriptions.ROLE_BUTTON + ":" + role, FreebieSubscriptions.ROLE_BUTTON_LABEL);
    }

    /** A date in each reader's own format; noon UTC is the same calendar day almost everywhere. */
    static String day(LocalDate date) {
        return "<t:" + date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC) + ":D>";
    }

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
