package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.utils.Embeds;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.label;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.text;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.title;

/**
 * Announcement messages. Pure builders. A page with one game is the familiar full card; with several games, Epic games
 * share embeds of up to three and every other game gets one compact card. Every embed carries the exact footer
 * reference ({@code Powered by … • ref …}) of its first game, so history recovery matches the message. Claim buttons are only shown for live games; ended or withdrawn games stay as grey cards.
 * Only the first page's initial post may ping, and only the saved role.
 */
final class FreebieAnnouncementCards {
    private FreebieAnnouncementCards() {}

    enum Status { LIVE, ENDED, WITHDRAWN }
    record Member(FreebieOffer offer, FreebieFacts facts, String deliveryId, Status status) {
        String reference() { return FreebieMessages.reference(deliveryId); }
        String footer() { return "Powered by " + offer.sourceLabel() + " • ref " + reference(); }
    }

    static final int EMBED_TOTAL = 6000;

    static MessageCreateData create(List<Member> members, Optional<String> pingRole, boolean checkout, Instant now) {
        var builder = new MessageCreateBuilder().setEmbeds(embeds(members, now)).setComponents(components(members, checkout, now))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        pingRole.ifPresent(role -> builder.setContent("<@&" + role + ">").mentionRoles(role));
        return builder.build();
    }

    /**
     * A drop's message: with roles, the content is the role mentions alone and exactly those roles are
     * whitelisted (never everyone, here or users); without, no content at all.
     */
    static MessageCreateData create(List<Member> members, List<String> pingRoles, boolean checkout, Instant now) {
        var builder = new MessageCreateBuilder().setEmbeds(embeds(members, now)).setComponents(components(members, checkout, now))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        if (!pingRoles.isEmpty()) {
            var mentions = new StringJoiner(" ");
            for (String role : pingRoles) mentions.add("<@&" + role + ">");
            builder.setContent(mentions.toString()).mentionRoles(pingRoles);
        }
        return builder.build();
    }

    /** "1 new free game" / "N new free games", counting the games still claimable on this message. */
    static String headline(List<Member> members) {
        long live = members.stream().filter(m -> m.status() == Status.LIVE).count();
        return live == 1 ? "1 new free game" : live + " new free games";
    }

    /** The whole message rebuilt from current state. Content (the original ping text) is kept while any game is live. */
    static MessageEditData edit(List<Member> members, boolean checkout, Instant now) {
        return edit(members, checkout, now, Optional.empty());
    }

    private static final java.util.regex.Pattern HEADLINE = java.util.regex.Pattern.compile("\\d+ new free games?$");

    /**
     * As {@link #edit(List, boolean, Instant)}, given the posted content: a drop's {@code N new free games} is recounted
     * from the games still claimable (after silent joins and ended games); the mentions before it stay as posted. Edits
     * never notify, and allowed mentions stay empty.
     */
    static MessageEditData edit(List<Member> members, boolean checkout, Instant now, Optional<String> posted) {
        var builder = new MessageEditBuilder().setEmbeds(embeds(members, now)).setComponents(components(members, checkout, now))
                .setAllowedMentions(List.of());
        if (members.stream().noneMatch(m -> m.status() == Status.LIVE)) builder.setContent("");
        else posted.filter(c -> HEADLINE.matcher(c).find()).ifPresent(c -> builder.setContent(HEADLINE.matcher(c).replaceFirst(headline(members))));
        return builder.build();
    }

    /** Room kept under Discord's total for rounding in its own count. */
    private static final int EMBED_MARGIN = 100, MIN_BUDGET = 160;

    /** Most Epic games one embed of a several-game page lists. */
    static final int EPIC_GROUP = 3;

    /**
     * One full card, or several compact embeds: Epic games share embeds of up to {@link #EPIC_GROUP} games, every other
     * game keeps its own compact card. Each game starts with an equal share of Discord's 6000-character total, and
     * descriptions and region text shrink until the whole message fits.
     */
    static List<MessageEmbed> embeds(List<Member> members, Instant now) {
        if (members.size() == 1) return List.of(full(members.getFirst(), now));
        var units = units(members);
        int limit = EMBED_TOTAL - EMBED_MARGIN, budget = limit / members.size();
        while (true) {
            List<MessageEmbed> embeds = new ArrayList<>();
            for (var unit : units) embeds.add(render(unit, budget, now, false));
            int total = embeds.stream().mapToInt(MessageEmbed::getLength).sum();
            if (total <= limit) return embeds;
            if (budget <= MIN_BUDGET) {
                // Still over at the smallest share: every game falls back to its title and end date, which ten games
                // always fit (titles are cut to 60 characters at this share).
                List<MessageEmbed> bare = new ArrayList<>();
                for (var unit : units) bare.add(render(unit, MIN_BUDGET, now, true));
                return bare;
            }
            budget = Math.max(MIN_BUDGET, budget - Math.max(20, (total - limit) / members.size()));
        }
    }

    private static MessageEmbed render(List<Member> unit, int budget, Instant now, boolean bare) {
        return unit.size() == 1 ? compact(unit.getFirst(), budget, now, bare) : group(unit, budget, now, bare);
    }

    /**
     * The page's games in embed order. Epic games are split as evenly as possible into groups of at most
     * {@link #EPIC_GROUP} (four make 2 + 2, five 3 + 2), each placed where its first game stands; other games stay alone.
     */
    static List<List<Member>> units(List<Member> members) {
        var epic = members.stream().filter(m -> m.offer().store() == FreebieStore.EPIC).toList();
        int groups = (epic.size() + EPIC_GROUP - 1) / EPIC_GROUP;
        Map<Member, List<Member>> leads = new IdentityHashMap<>();
        for (int g = 0, from = 0; g < groups; g++) {
            int size = epic.size() / groups + (g < epic.size() % groups ? 1 : 0);
            leads.put(epic.get(from), epic.subList(from, from + size));
            from += size;
        }
        List<List<Member>> units = new ArrayList<>();
        for (Member m : members) {
            if (m.offer().store() != FreebieStore.EPIC) units.add(List.of(m));
            else if (leads.containsKey(m)) units.add(leads.get(m));
        }
        return units;
    }

    /**
     * Several Epic games in one embed: one field per game (claim and mobile links, price, end, region), the first live
     * game's thumbnail, and the first game's exact footer. History recovery looks for any footer of the page, so one
     * per embed is enough; page games never get per-card ended edits. Grey with "Ended:" once no game in it is live.
     */
    static MessageEmbed group(List<Member> unit, int budget, Instant now, boolean bare) {
        long live = unit.stream().filter(m -> m.status() == Status.LIVE).count();
        String launcher = FreebieStore.EPIC.label();
        String heading = live == unit.size() ? unit.size() + " free games on " + launcher : "Free games on " + launcher;
        var card = Embeds.of(FreebieCards.emoji(FreebieStore.EPIC), live == 0 ? FreebieLifecycleMessages.ENDED_PREFIX + heading : heading)
                .setAuthor("BunnyNexus • FREE GAMES • " + launcher, null, FreebieCards.icon(FreebieStore.EPIC))
                .setFooter(unit.getFirst().footer()).setTimestamp(null);
        if (live == 0) card.setColor(FreebieLifecycleMessages.ENDED_COLOUR);
        int titleRoom = Math.clamp(budget / 3, 60, 200);
        for (Member m : unit) {
            String name = text(title(m.offer().title()), titleRoom);
            if (m.status() != Status.LIVE) {
                boolean withdrawn = m.status() == Status.WITHDRAWN;
                card.addField(FreebieLifecycleMessages.ENDED_PREFIX + text(title(m.offer().title()), titleRoom - 20),
                        "**" + (withdrawn ? FreebieLifecycleMessages.WITHDRAWN_TEXT : FreebieLifecycleMessages.ENDED_TEXT) + "**", false);
                continue;
            }
            card.addField(name, groupValue(m, budget, now, bare), false);
        }
        unit.stream().filter(m -> m.status() == Status.LIVE).map(m -> m.offer().image()).flatMap(Optional::stream).findFirst()
                .ifPresent(i -> card.setThumbnail(i.toString()));
        return card.build();
    }

    /** Field values hold at most 1024 characters: region gives way first, then the description, then the links. */
    private static String groupValue(Member m, int budget, Instant now, boolean bare) {
        var offer = m.offer();
        String ends = offer.endsAt().map(e -> "Ends <t:" + e.getEpochSecond() + ":R> (<t:" + e.getEpochSecond() + ":f>)")
                .orElse("No end date listed");
        String deal = (offer.bonus() ? "**FREE BONUS BUNDLE** (the game is free to play) · "
                : offer.worth().map(w -> "~~" + text(w, 40) + "~~ → **FREE** · ").orElse("**FREE** · ")) + ends;
        if (bare) return ends;
        var claim = new StringBuilder("[Claim on PC](" + offer.claimUrl().toASCIIString() + ")");
        for (var v : m.facts().publicVariants(now)) claim.append(" · [").append(v.label()).append("](").append(v.page()).append(")");
        String region = m.facts().official() ? FreebieRegions.describe(m.facts().regions(), true, now) : offer.region();
        String tail = deal + "\n" + claim + (region == null || region.isBlank() ? "" : "\n*" + text(region, Math.clamp(budget / 4, 40, 300)) + "*");
        int room = Math.min(300, budget - tail.length() - 80);
        String value = room >= 40 ? text(offer.description(), room) + "\n" + tail : tail;
        if (value.length() <= 1024) return value;
        String withoutRegion = deal + "\n" + claim;
        return withoutRegion.length() <= 1024 ? withoutRegion : deal;
    }

    /** The single-game card: the shared card plus region and verified mobile details. */
    static MessageEmbed full(Member m, Instant now) {
        MessageEmbed built = decorate(FreebieCards.embed(m.offer(), m.footer()), m.facts(), m.facts().publicVariants(now), now);
        return m.status() == Status.LIVE ? built : FreebieLifecycleMessages.endedEmbed(built, m.status() == Status.WITHDRAWN);
    }

    /** The owner's review preview: the public card as it would be posted, with every live variant awaiting approval. */
    static MessageEmbed preview(FreebieOffer offer, FreebieFacts facts, Instant now) {
        return decorate(FreebieCards.embed(offer, "Powered by " + offer.sourceLabel() + " • ref preview", true), facts,
                facts.variants().stream().filter(v -> v.liveAt(now)).toList(), now);
    }

    private static MessageEmbed decorate(MessageEmbed base, FreebieFacts facts, List<FreebieFacts.Variant> mobile, Instant now) {
        var card = new EmbedBuilder(base);
        if (facts.official()) {
            card.getFields().removeIf(f -> FreebieCards.REGION.equals(f.getName()));
            card.addField(FreebieCards.REGION, text(FreebieRegions.describe(facts.regions(), true, now), 1000), false);
        }
        if (!mobile.isEmpty()) card.addField(FreebieCards.MOBILE, mobileText(mobile, now), false);
        return card.build();
    }

    static String mobileText(List<FreebieFacts.Variant> variants, Instant now) {
        var lines = new StringJoiner("\n");
        for (var v : variants) {
            String restrictions = FreebieRegions.blocked(v.regions(), now).isEmpty() ? ""
                    : " · not claimable in " + FreebieRegions.names(FreebieRegions.blocked(v.regions(), now));
            lines.add("[" + v.label() + "](" + v.page() + ") until <t:" + v.endsAt().getEpochSecond() + ":f>" + restrictions);
        }
        return lines + "\nSeparate claim per platform in the Epic Games mobile store; a PC claim does not include mobile.";
    }

    static MessageEmbed compact(Member m, int budget, Instant now) { return compact(m, budget, now, false); }

    private static MessageEmbed compact(Member m, int budget, Instant now, boolean bare) {
        var offer = m.offer();
        String mark = FreebieCards.emoji(offer.store());
        int titleRoom = Math.clamp(budget / 3, 60, 200); // Only a ten-game page with very long titles needs less than 200.
        var card = Embeds.of(mark, text(title(offer.title()), titleRoom));
        card.setFooter(m.footer()).setTimestamp(null);
        if (m.status() != Status.LIVE) {
            boolean withdrawn = m.status() == Status.WITHDRAWN;
            card.setTitle(FreebieLifecycleMessages.ENDED_PREFIX + text(title(offer.title()), titleRoom - 20), null)
                    .setColor(FreebieLifecycleMessages.ENDED_COLOUR)
                    .setDescription("**" + (withdrawn ? FreebieLifecycleMessages.WITHDRAWN_TEXT : FreebieLifecycleMessages.ENDED_TEXT) + "**");
            return card.build();
        }
        card.setUrl(offer.claimUrl().toString());
        var claim = new StringBuilder("[Claim on PC](" + offer.claimUrl().toASCIIString() + ")");
        for (var v : m.facts().publicVariants(now)) claim.append(" · [").append(v.label()).append("](").append(v.page()).append(")");
        String ends = offer.endsAt().map(e -> "Ends <t:" + e.getEpochSecond() + ":R> (<t:" + e.getEpochSecond() + ":f>)")
                .orElse("No end date listed");
        // Last resort when even the smallest share overflows (very long claim or mobile links): the end date only. The
        // title still links to the claim page and the game keeps its own claim button.
        if (bare) return card.setDescription(ends).build();
        String region = m.facts().official() ? FreebieRegions.describe(m.facts().regions(), true, now) : offer.region();
        // Region text gives way first, then the description; claim links and the end date always stay.
        String tail = "\n\n" + claim + "\n" + ends + "\n*" + text(region, Math.clamp(budget / 4, 40, 300)) + "*";
        int room = Math.min(400, budget - tail.length() - card.build().getLength() - 40);
        card.setDescription(room >= 40 ? text(offer.description(), room) + tail : tail.strip());
        offer.image().ifPresent(i -> card.setThumbnail(i.toString()));
        return card.build();
    }

    /**
     * The one button policy. A single live game: Claim, then Instant Checkout (only with an exact Epic catalog identity
     * and checkout enabled), then one link per reviewed mobile version. Several games: the combined checkout when
     * available, then one Claim per live game. Ended or withdrawn games have no buttons.
     */
    static List<ActionRow> components(List<Member> members, boolean checkout, Instant now) {
        List<ActionRowChildComponent> buttons = new ArrayList<>();
        var live = members.stream().filter(m -> m.status() == Status.LIVE).toList();
        if (checkout && members.size() > 1) combined(members, now).ifPresent(buttons::add);
        if (members.size() == 1) {
            Member m = members.getFirst();
            if (m.status() == Status.LIVE) {
                buttons.add(FreebieCards.claimButton(m.offer()));
                if (checkout) combined(members, now).ifPresent(buttons::add);
                for (var v : m.facts().publicVariants(now)) buttons.add(Button.link(v.page().toString(), "Claim " + v.label() + " Version")
                        .withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromFormatted(FreebieCards.emoji(
                                v.platform() == EgDataIntake.Platform.ANDROID ? FreebieStore.ANDROID : FreebieStore.IOS))));
            }
        } else for (Member m : live) buttons.add(Button.link(m.offer().claimUrl().toString(), label("Claim " + title(m.offer().title()))));
        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size() && rows.size() < 5; i += 5) rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
        return rows;
    }

    /**
     * Epic checkout for the live Epic games with exact catalog identities: "Instant Checkout" for one game, otherwise
     * "Claim all PC games" (or "eligible" when a game on the page was left out). On a page of several games a checkout
     * for just one of them names that game, so it is clear what it buys. Never built when the URL would not fit.
     */
    static Optional<Button> combined(List<Member> members, Instant now) {
        var eligible = members.stream().filter(m -> m.status() == Status.LIVE && m.offer().store() == FreebieStore.EPIC
                && m.facts().epicOffer().isPresent() && !m.offer().ended(now)).toList();
        if (eligible.isEmpty()) return Optional.empty();
        var url = EpicLinks.checkout(eligible.stream().map(m -> m.facts().epicOffer().get()).toList());
        if (url.isEmpty()) return Optional.empty();
        if (eligible.size() == 1) return Optional.of(Button.link(url.get().toString(), members.size() == 1 ? "Instant Checkout"
                : label("Instant Checkout: " + title(eligible.getFirst().offer().title()))));
        boolean all = eligible.size() == members.size();
        return Optional.of(Button.link(url.get().toString(), (all ? "Claim All PC Games (" : "Claim Eligible PC Games (") + eligible.size() + ")"));
    }
}
