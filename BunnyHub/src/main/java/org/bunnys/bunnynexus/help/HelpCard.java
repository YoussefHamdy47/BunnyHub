package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.handler.cards.Card;
import java.util.ArrayList;
import java.util.List;

/**
 * One help page as a Discord Components V2 card: a heading with the bot's avatar beside it, the intro, the page's
 * sections as {@code ###} headings, the menus and buttons, and a subtext footer. Built like an embed (title,
 * description, fields, footer) so the help pages stay simple; no accent colour.
 */
final class HelpCard {
    record Field(String name, String value) {}

    /** Discord counts every text block toward 4000 characters per message; this leaves room for the heading. */
    static final int MAX_TEXT = 3600;

    private String title = "", description = "", footer = "", thumbnail;
    private final List<Field> fields = new ArrayList<>();

    HelpCard title(String title) { this.title = title == null ? "" : title; return this; }
    HelpCard description(String description) { this.description = description == null ? "" : description; return this; }
    HelpCard thumbnail(String url) { this.thumbnail = url; return this; }
    HelpCard footer(String footer) { this.footer = footer == null ? "" : footer; return this; }
    void add(String name, String value) { fields.add(new Field(name, value)); }

    String getTitle() { return title; }
    String getDescription() { return description; }
    String getFooter() { return footer; }
    List<Field> getFields() { return List.copyOf(fields); }

    /** Characters this page puts into text blocks (the heading, intro, sections and footer). */
    int length() {
        int total = title.length() + description.length() + footer.length() + 16;
        for (Field f : fields) total += f.name().length() + f.value().length() + 7;
        return total;
    }

    /** The page as a {@link Card}: heading beside the avatar, sections, controls, footer. */
    Card card(List<ActionRow> rows) {
        var card = Card.builder().header("## " + title + (description.isBlank() ? "" : "\n" + description), thumbnail);
        if (!fields.isEmpty()) card.divider();
        for (Field f : fields) card.field(f.name(), f.value());
        if (!rows.isEmpty()) card.divider().rows(rows);
        return card.footer(footer).build();
    }

    List<MessageTopLevelComponent> components(List<ActionRow> rows) { return card(rows).components(); }
    MessageCreateData message(List<ActionRow> rows) { return card(rows).message(); }
    MessageEditData edit(List<ActionRow> rows) { return card(rows).edit(); }
}
