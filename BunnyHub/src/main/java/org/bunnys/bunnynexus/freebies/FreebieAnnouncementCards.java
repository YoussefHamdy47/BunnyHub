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
 * Announcement messages. Pure builders. A page with one game is the familiar full card; several games use one compact
 * card each. Every card keeps its member's exact footer reference ({@code Powered by … • ref …}), so history recovery
 * and audits match it. Claim buttons are only shown for live games; ended or withdrawn games stay as grey cards.
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

    /** The whole message rebuilt from current state. Content (the original ping text) is kept while any game is live. */
    static MessageEditData edit(List<Member> members, boolean checkout, Instant now) {
        var builder = new MessageEditBuilder().setEmbeds(embeds(members, now)).setComponents(components(members, checkout, now))
                .setAllowedMentions(List.of());
        if (members.stream().noneMatch(m -> m.status() == Status.LIVE)) builder.setContent("");
        return builder.build();
    }

    static List<MessageEmbed> embeds(List<Member> members, Instant now) {
        if (members.size() == 1) return List.of(full(members.getFirst(), now));
        List<MessageEmbed> embeds = new ArrayList<>();
        int budget = EMBED_TOTAL / members.size();
        for (Member m : members) embeds.add(compact(m, budget, now));
        return embeds;
    }

    /** The single-game card: the existing launcher card plus region and verified mobile details. */
    static MessageEmbed full(Member m, Instant now) {
        MessageEmbed built = decorate(FreebieCards.embed(m.offer(), m.footer()), m.facts(), m.facts().publicVariants(now), now);
        return m.status() == Status.LIVE ? built : FreebieLifecycleMessages.endedEmbed(built, m.status() == Status.WITHDRAWN);
    }

    /** The owner's review preview: the public card as it would be posted, with every live variant awaiting approval. */
    static MessageEmbed preview(FreebieOffer offer, FreebieFacts facts, Instant now) {
        return decorate(FreebieCards.embed(offer, "Powered by " + offer.sourceLabel() + " • ref preview"), facts,
                facts.variants().stream().filter(v -> v.liveAt(now)).toList(), now);
    }

    private static MessageEmbed decorate(MessageEmbed base, FreebieFacts facts, List<FreebieFacts.Variant> mobile, Instant now) {
        var card = new EmbedBuilder(base);
        if (facts.official()) {
            card.getFields().removeIf(f -> "REGION".equals(f.getName()));
            card.addField("REGION", text(FreebieRegions.describe(facts.regions(), true, now), 1000), false);
        }
        if (!mobile.isEmpty()) card.addField("ALSO FREE ON MOBILE", mobileText(mobile, now), false);
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

    static MessageEmbed compact(Member m, int budget, Instant now) {
        var offer = m.offer();
        String mark = FreebieCards.emoji(offer.store());
        var card = Embeds.of(mark, text(title(offer.title()), 200));
        card.setFooter(m.footer()).setTimestamp(null);
        if (m.status() != Status.LIVE) {
            boolean withdrawn = m.status() == Status.WITHDRAWN;
            card.setTitle(FreebieLifecycleMessages.ENDED_PREFIX + text(title(offer.title()), 180), null)
                    .setColor(FreebieLifecycleMessages.ENDED_COLOUR)
                    .setDescription("**" + (withdrawn ? FreebieLifecycleMessages.WITHDRAWN_TEXT : FreebieLifecycleMessages.ENDED_TEXT) + "**");
            return card.build();
        }
        card.setUrl(offer.claimUrl().toString());
        var claim = new StringBuilder("[Claim on PC](" + offer.claimUrl().toASCIIString() + ")");
        for (var v : m.facts().publicVariants(now)) claim.append(" · [").append(v.label()).append("](").append(v.page()).append(")");
        String ends = offer.endsAt().map(e -> "Ends <t:" + e.getEpochSecond() + ":R> (<t:" + e.getEpochSecond() + ":f>)")
                .orElse("No end date listed");
        String region = m.facts().official() ? FreebieRegions.describe(m.facts().regions(), true, now) : offer.region();
        String tail = "\n\n" + claim + "\n" + ends + "\n*" + text(region, 300) + "*";
        int room = Math.max(80, budget - tail.length() - card.build().getLength() - 40);
        card.setDescription(text(offer.description(), Math.min(400, room)) + tail);
        offer.image().ifPresent(i -> card.setThumbnail(i.toString()));
        return card.build();
    }

    /** One claim button per live game (plus details/mobile for a single game) and, when enabled, combined checkout. */
    static List<ActionRow> components(List<Member> members, boolean checkout, Instant now) {
        List<ActionRowChildComponent> buttons = new ArrayList<>();
        var live = members.stream().filter(m -> m.status() == Status.LIVE).toList();
        if (checkout && members.size() > 1) combined(members, now).ifPresent(buttons::add);
        if (members.size() == 1) {
            Member m = members.getFirst();
            if (m.status() == Status.LIVE) {
                buttons.add(FreebieCards.claimButton(m.offer()));
                if (checkout) combined(members, now).ifPresent(buttons::add);
                for (var v : m.facts().publicVariants(now)) buttons.add(Button.link(v.page().toString(), "Claim " + v.label() + " version"));
            }
            buttons.add(Button.link(m.offer().pageUrl().toString(), "Offer Details"));
        } else for (Member m : live) buttons.add(Button.link(m.offer().claimUrl().toString(), label("Claim " + title(m.offer().title()))));
        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size() && rows.size() < 5; i += 5) rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
        return rows;
    }

    /**
     * Epic checkout for the live Epic games with exact catalog identities: "Instant Checkout" for one game, otherwise
     * "Claim all PC games" (or "eligible" when a game on the page was left out). Never built when the URL would not fit.
     */
    static Optional<Button> combined(List<Member> members, Instant now) {
        var eligible = members.stream().filter(m -> m.status() == Status.LIVE && m.offer().store() == FreebieStore.EPIC
                && m.facts().epicOffer().isPresent() && !m.offer().ended(now)).toList();
        if (eligible.isEmpty()) return Optional.empty();
        var url = EpicLinks.checkout(eligible.stream().map(m -> m.facts().epicOffer().get()).toList());
        if (url.isEmpty()) return Optional.empty();
        if (eligible.size() == 1) return Optional.of(Button.link(url.get().toString(), "Instant Checkout"));
        boolean all = eligible.size() == members.size();
        return Optional.of(Button.link(url.get().toString(), (all ? "Claim all PC games (" : "Claim eligible PC games (") + eligible.size() + ")"));
    }
}
