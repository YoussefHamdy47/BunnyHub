package org.bunnys.handler.cards;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.section.SectionAccessoryComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import java.awt.Color;
import java.util.*;

/**
 * A Discord Components V2 message: one card (a container) with optional text above it, built like an embed but
 * without an embed's limits on layout. Immutable; build one with {@link #builder()}.
 *
 * <pre>{@code
 * Card card = Card.builder()
 *         .above("<@&123> 2 new free games").mentionRoles("123")   // outside the card; the only text that pings
 *         .header("## BunnyNexus Help\nEverything the bot can do.", avatarUrl) // heading with a thumbnail beside it
 *         .divider()
 *         .field("Study", "/timer start · /timer stop")             // consecutive fields share one text block
 *         .field("Info", "/ping · /uptime")
 *         .section("### Knightica\n~~$14.99~~ → **Free**", Button.link(url, "Claim"))
 *         .buttons(Button.secondary("help:back", "Back"))
 *         .footer("9 commands")                                     // small grey line at the bottom
 *         .build();                                                 // checks Discord's limits
 * ctx.replyCard(card);          // a command
 * Cards.edit(event, card);      // a button or menu on that message
 * }</pre>
 *
 * <p>Plain cards have no accent colour; {@link Builder#accent} adds the coloured stripe (errors, warnings). Every card
 * is sent with no allowed mentions except the roles and users named with {@link Builder#mentionRoles} and
 * {@link Builder#mentionUsers}, and edits never ping. {@link Builder#build()} throws when Discord would refuse the
 * message: more than {@value #MAX_COMPONENTS} components or {@value #MAX_TEXT} characters of text.
 */
public final class Card {
    /** Discord's limits for one Components V2 message. */
    public static final int MAX_COMPONENTS = 40, MAX_TEXT = 4000, MAX_ROWS_IN_CARD = 5;

    private final List<MessageTopLevelComponent> components;
    private final Set<String> roles, users;

    private Card(List<MessageTopLevelComponent> components, Set<String> roles, Set<String> users) {
        this.components = List.copyOf(components);
        this.roles = Set.copyOf(roles);
        this.users = Set.copyOf(users);
    }

    public static Builder builder() { return new Builder(); }

    /** The top-level components: the text above the card, if any, then the card. */
    public List<MessageTopLevelComponent> components() { return components; }

    /** A new message. Mentions only the roles and users the builder named. */
    public MessageCreateData message() {
        var builder = new MessageCreateBuilder().useComponentsV2(true).setComponents(components)
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        if (!roles.isEmpty()) builder.mentionRoles(roles);
        if (!users.isEmpty()) builder.mentionUsers(users);
        return builder.build();
    }

    /**
     * The whole message replaced by this card. Edits never ping. A message first sent as an embed may not become a
     * card; {@link Cards#edit} falls back to a new private reply in that case.
     */
    public MessageEditData edit() {
        return new MessageEditBuilder().useComponentsV2(true).setComponents(components).setAllowedMentions(List.of()).build();
    }

    /** Every text block in reading order, one per line: for tests, logs and search. */
    public String plainText() {
        List<String> texts = new ArrayList<>();
        for (var component : components) {
            if (component instanceof TextDisplay text) texts.add(text.getContent());
            else if (component instanceof Container container)
                for (var child : container.getComponents()) {
                    if (child instanceof TextDisplay text) texts.add(text.getContent());
                    else if (child instanceof Section section)
                        for (var content : section.getContentComponents())
                            if (content instanceof TextDisplay text) texts.add(text.getContent());
                }
        }
        return String.join("\n", texts);
    }

    public int textLength() { return textLength(components); }
    public int componentCount() { return componentCount(components); }

    /** Characters Discord counts toward {@link #MAX_TEXT}: every text block, inside sections too. */
    public static int textLength(Collection<? extends MessageTopLevelComponent> top) {
        int total = 0;
        for (var component : top) {
            if (component instanceof TextDisplay text) total += text.getContent().length();
            else if (component instanceof Container container)
                for (var child : container.getComponents()) {
                    if (child instanceof TextDisplay text) total += text.getContent().length();
                    else if (child instanceof Section section)
                        for (var content : section.getContentComponents())
                            if (content instanceof TextDisplay text) total += text.getContent().length();
                }
        }
        return total;
    }

    /** Every component Discord counts toward {@link #MAX_COMPONENTS}: nested ones, section accessories and buttons. */
    public static int componentCount(Collection<? extends MessageTopLevelComponent> top) {
        int total = 0;
        for (var component : top) {
            total++;
            if (component instanceof Container container)
                for (var child : container.getComponents()) {
                    total++;
                    if (child instanceof Section section) total += section.getContentComponents().size() + 1;
                    else if (child instanceof ActionRow row) total += row.getComponents().size();
                }
            else if (component instanceof ActionRow row) total += row.getComponents().size();
        }
        return total;
    }

    /** Builds one card in reading order. Not thread-safe; build once, then share the {@link Card}. */
    public static final class Builder {
        private final List<String> above = new ArrayList<>();
        private final List<ContainerChildComponent> parts = new ArrayList<>();
        private final StringBuilder fields = new StringBuilder();
        private final Set<String> roles = new LinkedHashSet<>(), users = new LinkedHashSet<>();
        private Integer accent;
        private String footer;
        private int rows;

        private Builder() {}

        /** Text above the card, outside it: where a ping line goes. Mentions here notify only if allowed. */
        public Builder above(String markdown) { if (present(markdown)) above.add(markdown); return this; }

        /** The coloured stripe on the card's left edge; plain cards leave it out. */
        public Builder accent(Integer rgb) { this.accent = rgb; return this; }
        public Builder accent(Color color) { return accent(color == null ? null : color.getRGB() & 0xFFFFFF); }

        /** A {@code ##} heading. */
        public Builder title(String title) { return present(title) ? text("## " + title) : this; }

        /** Any markdown with a small image on its right (the bot's avatar, a game's art); no image when the URL is blank. */
        public Builder header(String markdown, String thumbnailUrl) {
            if (!present(markdown)) return this;
            flush();
            parts.add(present(thumbnailUrl) ? Section.of(Thumbnail.fromUrl(thumbnailUrl), TextDisplay.of(markdown)) : TextDisplay.of(markdown));
            return this;
        }

        /** A block of markdown. Blank text is skipped. */
        public Builder text(String markdown) {
            if (!present(markdown)) return this;
            flush();
            parts.add(TextDisplay.of(markdown));
            return this;
        }

        /** A {@code ###} heading and its value, like an embed field. Consecutive fields share one text block. */
        public Builder field(String name, String value) {
            if (!present(name) && !present(value)) return this;
            if (!fields.isEmpty()) fields.append("\n\n");
            if (present(name)) fields.append("### ").append(name).append('\n');
            fields.append(present(value) ? value.strip() : "");
            return this;
        }

        /** Markdown with a button on its right (a Claim link, an action). */
        public Builder section(String markdown, Button button) { return section(markdown, (SectionAccessoryComponent) button); }

        /** Markdown with an image on its right. */
        public Builder section(String markdown, String thumbnailUrl) { return header(markdown, thumbnailUrl); }

        public Builder section(String markdown, SectionAccessoryComponent accessory) {
            if (!present(markdown)) return this;
            flush();
            parts.add(Section.of(accessory, TextDisplay.of(markdown)));
            return this;
        }

        /** A thin line between parts. */
        public Builder divider() { flush(); parts.add(Separator.createDivider(Separator.Spacing.SMALL)); return this; }

        /** Blank space between parts. */
        public Builder space() { flush(); parts.add(Separator.createInvisible(Separator.Spacing.SMALL)); return this; }

        /** One large image. */
        public Builder image(String url) { return gallery(present(url) ? List.of(url) : List.of()); }

        /** Up to ten images side by side; blank URLs are skipped and nothing is added when none is left. */
        public Builder gallery(Collection<String> urls) {
            var items = urls.stream().filter(Builder::present).limit(MediaGallery.MAX_ITEMS).map(MediaGalleryItem::fromUrl).toList();
            if (items.isEmpty()) return this;
            flush();
            parts.add(MediaGallery.of(items));
            return this;
        }

        /** One row of up to five buttons inside the card. */
        public Builder buttons(Button... buttons) { return buttons(Arrays.asList(buttons)); }

        public Builder buttons(Collection<? extends ActionRowChildComponent> buttons) {
            return buttons.isEmpty() ? this : row(ActionRow.of(buttons));
        }

        /** A row (buttons or one menu) inside the card. */
        public Builder row(ActionRow row) {
            flush();
            parts.add(row);
            rows++;
            return this;
        }

        public Builder rows(Collection<ActionRow> rows) { rows.forEach(this::row); return this; }

        /** A small grey line at the bottom of the card ({@code -#} subtext). */
        public Builder footer(String text) { this.footer = present(text) ? text : null; return this; }

        /** Roles the {@link #above} text may ping; nothing else ever pings. */
        public Builder mentionRoles(String... ids) { roles.addAll(Arrays.asList(ids)); return this; }
        public Builder mentionRoles(Collection<String> ids) { roles.addAll(ids); return this; }
        public Builder mentionUsers(String... ids) { users.addAll(Arrays.asList(ids)); return this; }

        /** Characters left before {@link #MAX_TEXT}, for callers that shrink text to fit. */
        public int remainingText() { return MAX_TEXT - Card.textLength(assemble()); }

        /** Whether {@link #build()} would succeed. */
        public boolean fits() {
            var top = assemble();
            return Card.textLength(top) <= MAX_TEXT && Card.componentCount(top) <= MAX_COMPONENTS && rows <= MAX_ROWS_IN_CARD;
        }

        /** The card, checked against Discord's limits. */
        public Card build() {
            var top = assemble();
            int text = Card.textLength(top), count = Card.componentCount(top);
            if (text > MAX_TEXT) throw new IllegalStateException("Card text is " + text + " characters; Discord allows " + MAX_TEXT + ".");
            if (count > MAX_COMPONENTS) throw new IllegalStateException("Card has " + count + " components; Discord allows " + MAX_COMPONENTS + ".");
            if (rows > MAX_ROWS_IN_CARD) throw new IllegalStateException("Card has " + rows + " rows of buttons; keep it to " + MAX_ROWS_IN_CARD + ".");
            return new Card(top, roles, users);
        }

        private List<MessageTopLevelComponent> assemble() {
            List<ContainerChildComponent> all = new ArrayList<>(parts);
            if (!fields.isEmpty()) all.add(TextDisplay.of(fields.toString()));
            if (footer != null) all.add(TextDisplay.of("-# " + footer));
            List<MessageTopLevelComponent> top = new ArrayList<>();
            for (String text : above) top.add(TextDisplay.of(text));
            if (!all.isEmpty()) {
                Container container = Container.of(all);
                top.add(accent == null ? container : container.withAccentColor(accent));
            }
            return top;
        }

        /** Pending fields become one text block before anything else is added, so order is kept. */
        private void flush() {
            if (fields.isEmpty()) return;
            parts.add(TextDisplay.of(fields.toString()));
            fields.setLength(0);
        }

        private static boolean present(String value) { return value != null && !value.isBlank(); }
    }
}
