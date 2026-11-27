package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.bunnys.bunnynexus.freebies.FreebieAnnouncementCards.Member;
import static org.bunnys.bunnynexus.freebies.FreebieAnnouncementCards.Status;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.text;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.title;

/**
 * Announcement pages as Discord Components V2 messages (FREEBIE_COMPONENTS_V2): one plain card (no accent colour).
 * One game: full-width art, a heading linking the claim page, price and end, launcher and region, then the button row
 * of {@link FreebieAnnouncementCards#components}. Several games: up to four pieces of art, a heading, one section per
 * game with its own Claim button, and the combined checkout below. The drop's mention line is a text block above the
 * card. The last line is the first game's exact {@code Powered by … • ref …} as subtext, which history recovery reads
 * ({@link FreebieMessages#carriesReference}). Pure builders.
 */
final class FreebieComponentCards {
    private FreebieComponentCards() {}

    /** Discord's limits for one Components V2 message. */
    static final int MAX_COMPONENTS = 40, MAX_TEXT = 4000;
    /** Room kept under {@link #MAX_TEXT} for Discord's own count. */
    private static final int TEXT_LIMIT = MAX_TEXT - 100;
    static final int MAX_ART = 4;
    static final String SUBTEXT = "-# ";

    static MessageCreateData create(List<Member> members, List<String> pingRoles, boolean checkout, Instant now) {
        return create(members, pingRoles, checkout, now, Optional.empty());
    }

    /** {@code ad}: the ad card shown under the game card while a game is claimable. */
    static MessageCreateData create(List<Member> members, List<String> pingRoles, boolean checkout, Instant now, Optional<Container> ad) {
        return create(members, pingRoles, checkout, now, ad, Optional.empty());
    }

    /**
     * {@code roleButton}: the channel's ping role, offered as a "Ping Me for Free Games" button inside the card. The mention line is
     * the mentions alone: the card already says what was posted.
     */
    static MessageCreateData create(List<Member> members, List<String> pingRoles, boolean checkout, Instant now, Optional<Container> ad,
                                    Optional<String> roleButton) {
        Optional<String> header = Optional.empty();
        if (!pingRoles.isEmpty()) {
            var mentions = new StringJoiner(" ");
            for (String role : pingRoles) mentions.add("<@&" + role + ">");
            header = Optional.of(mentions.toString());
        }
        var builder = new MessageCreateBuilder().useComponentsV2(true)
                .setComponents(components(members, header, checkout, now, ad, roleButton.map(FreebieComponentCards::roleRow)))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        if (!pingRoles.isEmpty()) builder.mentionRoles(pingRoles);
        return builder.build();
    }

    private static final Pattern HEADLINE = Pattern.compile("\\d+ new free games?$");

    /**
     * The whole message rebuilt from current state. The posted mention line keeps its mentions and recounts its
     * {@code N new free games}; it goes away once no game is claimable. Edits never notify.
     */
    static MessageEditData edit(List<Member> members, boolean checkout, Instant now, Optional<String> postedHeader) {
        return edit(members, checkout, now, postedHeader, Optional.empty());
    }

    /** As above, keeping the posted ad card ({@link #ad}) while a game is claimable. */
    static MessageEditData edit(List<Member> members, boolean checkout, Instant now, Optional<String> postedHeader, Optional<Container> postedAd) {
        return edit(members, checkout, now, postedHeader, postedAd, Optional.empty());
    }

    /** As above, also keeping the posted role button ({@link #roleRow(Message)}). Older posts recount their "N new free games". */
    static MessageEditData edit(List<Member> members, boolean checkout, Instant now, Optional<String> postedHeader, Optional<Container> postedAd,
                                Optional<ActionRow> postedRole) {
        boolean live = members.stream().anyMatch(m -> m.status() == Status.LIVE);
        Optional<String> header = live ? postedHeader.map(h -> HEADLINE.matcher(h).find()
                ? HEADLINE.matcher(h).replaceFirst(FreebieAnnouncementCards.headline(members)) : h) : Optional.empty();
        return new MessageEditBuilder().useComponentsV2(true).setComponents(components(members, header, checkout, now, postedAd, postedRole))
                .setAllowedMentions(List.of()).build();
    }

    /** The role button for the channel's ping role; a second press removes the role. */
    static ActionRow roleRow(String roleId) {
        return ActionRow.of(Button.secondary(FreebieSubscriptions.ROLE_BUTTON + ":" + roleId, FreebieSubscriptions.ROLE_BUTTON_LABEL));
    }

    /** The posted role button row of a V2 page, kept as posted on edits. */
    static Optional<ActionRow> roleRow(Message message) {
        if (!message.isUsingComponentsV2()) return Optional.empty();
        return roleRow(message.getComponents());
    }

    /**
     * The posted role button as a row of its own: inside the card on current posts, a row under the card on older ones
     * (which move it into the card on their next edit).
     */
    static Optional<ActionRow> roleRow(List<? extends MessageTopLevelComponent> components) {
        for (var c : components) {
            List<ActionRow> rows = new ArrayList<>();
            if (c instanceof ActionRow row) rows.add(row);
            else if (c instanceof Container container)
                for (var child : container.getComponents()) if (child instanceof ActionRow row) rows.add(row);
            for (var row : rows)
                for (var b : row.getComponents())
                    if (b instanceof Button button && isRoleButton(button)) return Optional.of(ActionRow.of(button));
        }
        return Optional.empty();
    }

    private static boolean isRoleButton(Button button) {
        return button.getCustomId() != null && button.getCustomId().startsWith(FreebieSubscriptions.ROLE_BUTTON + ":");
    }

    private static Optional<Button> roleButton(ActionRow row) {
        return row.getComponents().stream().filter(b -> b instanceof Button button && isRoleButton(button)).map(b -> (Button) b).findFirst()
                // Older posts carry the earlier label; every edit shows the current one.
                .map(b -> b.withLabel(FreebieSubscriptions.ROLE_BUTTON_LABEL));
    }

    /**
     * The card with the role button at the end of its last button row (when that row has room), else in a row of its
     * own above the footer.
     */
    static Container withRole(Container card, Button role) {
        List<ContainerChildComponent> parts = new ArrayList<>(card.getComponents());
        int footer = parts.size() - 1;
        if (footer > 0 && parts.get(footer - 1) instanceof ActionRow row && row.getComponents().size() < 5) {
            List<net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent> buttons = new ArrayList<>(row.getComponents());
            buttons.add(role);
            parts.set(footer - 1, ActionRow.of(buttons));
        } else {
            parts.add(footer, ActionRow.of(role));
        }
        return Container.of(parts);
    }

    /** The mention line of a posted V2 page: a top-level text block above the card, when there is one. */
    static Optional<String> header(Message message) { return message.isUsingComponentsV2() ? topText(message.getComponents(), true) : Optional.empty(); }

    /** The ad card of a posted V2 page (the second card, which only an ad adds), kept as posted on edits. */
    static Optional<Container> ad(Message message) { return message.isUsingComponentsV2() ? adCard(message.getComponents()) : Optional.empty(); }

    /** A top-level text block above the card (the mention line). */
    static Optional<String> topText(List<? extends MessageTopLevelComponent> components, boolean above) {
        for (var c : components) {
            if (c instanceof Container) return Optional.empty();
            if (c instanceof TextDisplay text) return Optional.of(text.getContent());
        }
        return Optional.empty();
    }

    /** The second card, which only an ad adds. */
    static Optional<Container> adCard(List<? extends MessageTopLevelComponent> components) {
        var cards = components.stream().filter(c -> c instanceof Container).map(c -> (Container) c).toList();
        return cards.size() < 2 ? Optional.empty() : Optional.of(cards.get(1));
    }

    static List<MessageTopLevelComponent> components(List<Member> members, Optional<String> header, boolean checkout, Instant now) {
        return components(members, header, checkout, now, Optional.empty());
    }

    static List<MessageTopLevelComponent> components(List<Member> members, Optional<String> header, boolean checkout, Instant now,
                                                     Optional<Container> ad) {
        return components(members, header, checkout, now, ad, Optional.empty());
    }

    static List<MessageTopLevelComponent> components(List<Member> members, Optional<String> header, boolean checkout, Instant now,
                                                     Optional<Container> ad, Optional<ActionRow> role) {
        // The ad goes once no game is claimable, like the mention line.
        var shownAd = ad.filter(a -> members.stream().anyMatch(m -> m.status() == Status.LIVE));
        // Shrinking order when the text limit is reached: descriptions, region text, the links in titles and mobile
        // lines (link URLs count as text; the Claim buttons keep theirs), then long titles.
        for (Detail detail : Detail.values()) {
            List<MessageTopLevelComponent> top = new ArrayList<>();
            header.ifPresent(h -> top.add(TextDisplay.of(h)));
            Container card = members.size() == 1 ? single(members.getFirst(), checkout, now, detail) : several(members, checkout, now, detail);
            top.add(card);
            shownAd.ifPresent(top::add);
            // Discord refuses more than 40 components: on a full ten-game page the role button gives way first, then the ad.
            var roleButton = role.flatMap(FreebieComponentCards::roleButton);
            if (roleButton.isPresent()) {
                int at = top.indexOf(card);
                top.set(at, withRole(card, roleButton.get()));
                if (componentCount(top) > MAX_COMPONENTS) top.set(at, card);
            }
            if (shownAd.isPresent() && componentCount(top) > MAX_COMPONENTS) top.remove(shownAd.get());
            if (textLength(top) <= TEXT_LIMIT || detail == Detail.BARE) return top;
        }
        throw new IllegalStateException("unreachable");
    }

    enum Detail { FULL, NO_DESCRIPTION, NO_REGION, NO_LINKS, BARE }

    private static Container single(Member m, boolean checkout, Instant now, Detail detail) {
        var offer = m.offer();
        List<ContainerChildComponent> parts = new ArrayList<>();
        offer.image().ifPresent(i -> parts.add(MediaGallery.of(MediaGalleryItem.fromUrl(i.toString()))));
        if (m.status() != Status.LIVE) {
            parts.add(TextDisplay.of("## " + FreebieLifecycleMessages.ENDED_PREFIX + text(title(offer.title()), 200) + "\n**"
                    + (m.status() == Status.WITHDRAWN ? FreebieLifecycleMessages.WITHDRAWN_TEXT : FreebieLifecycleMessages.ENDED_TEXT) + "**"));
            parts.add(TextDisplay.of(SUBTEXT + m.footer()));
            return Container.of(parts);
        }
        String heading = "## [" + linkText(title(offer.title()), detail == Detail.BARE ? 80 : 200) + "](" + offer.claimUrl() + ")";
        parts.add(TextDisplay.of(detail == Detail.FULL && !offer.description().isBlank()
                ? heading + "\n" + excerpt(offer.description(), 300) : heading));
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));
        // The labeled lines of the earlier featured card: the deal, the claim window, platforms and region.
        var facts = new StringJoiner("\n");
        facts.add("**The deal** " + price(offer));
        m.facts().rating().ifPresent(r -> facts.add("**Players** " + r.line()));
        facts.add("**Claim window** " + offer.endsAt().map(e -> "Ends <t:" + e.getEpochSecond() + ":R> · <t:" + e.getEpochSecond() + ":f>"
                + (offer.realEnd().isEmpty() ? " (estimated)" : "")).orElse("No end date listed"));
        facts.add("**Platforms** " + text(offer.platforms() == null || offer.platforms().isBlank() ? offer.store().label() : offer.platforms(), 120));
        if (detail.compareTo(Detail.NO_REGION) < 0) regionLine(m, now).ifPresent(r -> facts.add("**Region** " + r));
        var mobile = m.facts().publicVariants(now);
        if (!mobile.isEmpty() && detail != Detail.BARE) {
            facts.add("**Mobile** " + mobileLine(m, mobile, now));
            facts.add(SUBTEXT + "📱 A separate claim from PC");
        }
        parts.add(TextDisplay.of(facts.toString()));
        var rows = FreebieAnnouncementCards.components(List.of(m), checkout, now);
        if (!rows.isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.addAll(rows);
        }
        parts.add(TextDisplay.of(SUBTEXT + m.footer() + FreebieInvite.line()));
        return Container.of(parts);
    }

    private static Container several(List<Member> members, boolean checkout, Instant now, Detail detail) {
        List<ContainerChildComponent> parts = new ArrayList<>();
        var art = members.stream().filter(m -> m.status() == Status.LIVE).map(m -> m.offer().image()).flatMap(Optional::stream)
                .limit(MAX_ART).map(i -> MediaGalleryItem.fromUrl(i.toString())).toList();
        if (!art.isEmpty()) parts.add(MediaGallery.of(art));
        long live = members.stream().filter(m -> m.status() == Status.LIVE).count();
        var stores = members.stream().map(m -> m.offer().store()).distinct().toList();
        String games = (live == members.size() ? live + " free games" : "Free games")
                + (stores.size() == 1 ? " on " + stores.getFirst().label() : "");
        parts.add(TextDisplay.of("## " + (live == 0 ? FreebieLifecycleMessages.ENDED_PREFIX : "") + games));
        // Dividers between games only while they keep the message well inside Discord's 40 components.
        boolean dividers = members.size() <= 4;
        int titleRoom = detail == Detail.BARE ? 60 : 150;
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            var offer = m.offer();
            if (dividers || i == 0) parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            if (m.status() != Status.LIVE) {
                parts.add(TextDisplay.of("### ~~" + text(title(offer.title()), titleRoom) + "~~\n"
                        + (m.status() == Status.WITHDRAWN ? FreebieLifecycleMessages.WITHDRAWN_TEXT : FreebieLifecycleMessages.ENDED_TEXT)));
                continue;
            }
            boolean links = detail.compareTo(Detail.NO_LINKS) < 0;
            var body = new StringBuilder("### ").append(links
                    ? "[" + linkText(title(offer.title()), titleRoom) + "](" + offer.claimUrl() + ")"
                    : text(title(offer.title()), titleRoom)).append("\n").append(deal(offer, false));
            m.facts().rating().ifPresent(r -> body.append("\n").append(r.line()));
            // Each extra line is subtext of its own: the store (mixed pages), a short description, the region
            // restriction as flags, then mobile versions.
            var line = new StringJoiner(" · ");
            if (stores.size() > 1) line.add(offer.store().label());
            if (detail == Detail.FULL && !offer.description().isBlank()) line.add(excerpt(offer.description(), 110));
            if (line.length() > 0) body.append("\n").append(SUBTEXT).append(line);
            if (detail.compareTo(Detail.NO_REGION) < 0) regionLine(m, now).ifPresent(r -> body.append("\n").append(SUBTEXT).append(r));
            if (links) {
                var mobile = m.facts().publicVariants(now);
                if (!mobile.isEmpty()) body.append("\n").append(SUBTEXT).append("📱 ").append(mobileLine(m, mobile, now));
            }
            parts.add(Section.of(Button.link(offer.claimUrl().toString(), "Claim")
                    .withEmoji(Emoji.fromFormatted(FreebieCards.emoji(offer.store()))), TextDisplay.of(body.toString())));
        }
        if (checkout) FreebieAnnouncementCards.combined(members, now).ifPresent(button -> {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(ActionRow.of(button));
        });
        parts.add(TextDisplay.of(SUBTEXT + members.getFirst().footer() + (live > 0 ? FreebieInvite.line() : "")));
        return Container.of(parts);
    }

    static String price(FreebieOffer offer) {
        if (offer.bonus()) return "**Free bonus bundle** · the game is free to play";
        return offer.worth().map(w -> "~~" + text(w, 40) + "~~ → **Free**").orElse("**Free**");
    }

    /**
     * Only a known restriction is shown, never the generic "availability may differ" text: "🚫 Not claimable in" or
     * "✅ Only claimable in" followed by flags (Discord's 4000 characters go further than with names). Empty when
     * nothing is restricted.
     */
    static Optional<String> regionLine(Member m, Instant now) {
        return restriction(m.facts().regions(), now);
    }

    private static Optional<String> restriction(Optional<FreebieRegions.Evidence> evidence, Instant now) {
        var blocked = FreebieRegions.blocked(evidence, now);
        if (!blocked.isEmpty()) return Optional.of("🚫 Not claimable in " + FreebieRegions.flags(blocked));
        var only = FreebieRegions.only(evidence, now);
        if (!only.isEmpty()) return Optional.of("✅ Only claimable in " + FreebieRegions.flags(only));
        return Optional.empty();
    }

    /**
     * {@code [Android](…) · [iOS](…)}: a version's own end only when it differs from the game's, and its own
     * restriction only when it differs from the PC one already shown.
     */
    static String mobileLine(Member m, List<FreebieFacts.Variant> variants, Instant now) {
        var pcBlocked = FreebieRegions.blocked(m.facts().regions(), now);
        var pcOnly = FreebieRegions.only(m.facts().regions(), now);
        var line = new StringJoiner(" · ");
        for (var v : variants) {
            var part = new StringBuilder("[" + v.label() + "](" + v.page() + ")");
            if (m.offer().endsAt().map(e -> !e.equals(v.endsAt())).orElse(true))
                part.append(" until <t:").append(v.endsAt().getEpochSecond()).append(":f>");
            if (!FreebieRegions.blocked(v.regions(), now).equals(pcBlocked) || !FreebieRegions.only(v.regions(), now).equals(pcOnly))
                restriction(v.regions(), now).ifPresent(r -> part.append(" ").append(r));
            line.add(part);
        }
        return line.toString();
    }

    /**
     * A plain description excerpt: one line, cut at the last sentence end (or word) before {@code max} rather than
     * mid-word.
     */
    static String excerpt(String raw, int max) {
        String flat = raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
        if (flat.isEmpty()) return "—";
        if (flat.length() <= max) return text(flat, max);
        String head = flat.substring(0, max - 1);
        int sentence = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (sentence >= max / 2) return text(head.substring(0, sentence + 1), max);
        int space = head.lastIndexOf(' ');
        String cut = space >= max / 2 ? head.substring(0, space) : head;
        return text(cut.replaceAll("[,;:\\-–—]+$", ""), max) + "…";
    }

    /** {@code ~~$9.99~~ → **Free** · Ends in 3 days}; the full card adds the absolute time. */
    static String deal(FreebieOffer offer, boolean full) {
        String price = price(offer);
        String ends = offer.endsAt().map(e -> "Ends <t:" + e.getEpochSecond() + ":R>"
                        + (full ? " (<t:" + e.getEpochSecond() + ":f>)" : "") + (offer.realEnd().isEmpty() ? " (estimated)" : ""))
                .orElse("No end date listed");
        return price + " · " + ends;
    }

    /** Link text cannot contain brackets without breaking the markdown link. */
    private static String linkText(String value, int max) {
        return text(value.replace('[', '(').replace(']', ')'), max);
    }

    /** Characters Discord counts toward {@link #MAX_TEXT}: every text block's content. */
    static int textLength(List<? extends MessageTopLevelComponent> top) {
        int total = 0;
        for (var component : top) {
            if (component instanceof TextDisplay text) total += text.getContent().length();
            else if (component instanceof Container container)
                for (var child : container.getComponents()) {
                    if (child instanceof TextDisplay text) total += text.getContent().length();
                    else if (child instanceof Section section)
                        for (var content : section.getContentComponents()) if (content instanceof TextDisplay text) total += text.getContent().length();
                }
        }
        return total;
    }

    /** Every component Discord counts toward {@link #MAX_COMPONENTS}, nested ones and accessories included. */
    static int componentCount(List<? extends MessageTopLevelComponent> top) {
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
}
