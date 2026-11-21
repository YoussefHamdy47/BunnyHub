package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.handler.cards.Card;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One game the Epic Games Store lists as free in its next promotion ("Free next week"). Read from the Epic feed the
 * bot already polls, so it costs no extra request.
 *
 * <p>Each week the owners get a review card with the list and a <b>Prepare announcement</b> button; that opens the
 * usual announcement preview (send with or without ping, or discard), so nothing reaches servers without a click.
 * {@code /free-games list} also shows the list.
 */
public record FreebieUpcoming(String title, URI page, Optional<URI> image, Instant start, Instant end) {
    static final String ACTION = "upcoming", TITLE = "Free next week on Epic";
    static final int MAX_SHOWN = 6;

    /** The week's identity: when its first game starts. A game added to the same week later does not repost. */
    static String key(List<FreebieUpcoming> games) { return games.getFirst().start().toString(); }

    private String line() {
        return "**[" + FreebieMessages.text(title, 120) + "](" + page + ")**\nFree <t:" + start.getEpochSecond() + ":R>, until <t:"
                + end.getEpochSecond() + ":f>";
    }

    /** The announcement text servers receive (sent as a card titled {@link #TITLE}). */
    static String body(List<FreebieUpcoming> games) {
        var text = new StringBuilder("Coming free to keep on the Epic Games Store:\n\n");
        for (var game : games.subList(0, Math.min(MAX_SHOWN, games.size()))) text.append(game.line()).append("\n\n");
        return text.append("-# Claim them on the store once they go live.").toString();
    }

    /** The weekly card for the owners: the games, their art, and the button that prepares the announcement. */
    static MessageCreateData reviewCard(List<FreebieUpcoming> games) {
        var card = Card.builder().gallery(games.stream().map(FreebieUpcoming::image).flatMap(Optional::stream).limit(4).map(URI::toString).toList())
                .heading(TITLE);
        for (var game : games.subList(0, Math.min(MAX_SHOWN, games.size()))) card.text(game.line());
        return card.text("-# Send it to every server with Epic alerts? You'll see the exact message first.")
                .buttons(Button.primary(FreebieMessages.BUTTON_PREFIX + ":" + ACTION + ":next", "Prepare announcement"),
                        FreebieMessages.clearButton())
                .build().message();
    }

    /** For {@code /free-games list}: one short line per game. */
    static String listSection(List<FreebieUpcoming> games) {
        var text = new StringBuilder("### Free next week\n");
        for (var game : games.subList(0, Math.min(4, games.size())))
            text.append("[").append(FreebieMessages.text(game.title(), 100)).append("](").append(game.page()).append(") · <t:")
                    .append(game.start().getEpochSecond()).append(":R>\n");
        return text.toString().strip();
    }
}
