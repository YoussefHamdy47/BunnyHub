package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.utils.Embeds;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.text;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.title;

/**
 * The ended card and the ending-soon reminder. Pure builders. The ended card is derived from the posted message
 * itself (not rebuilt from the offer), so whichever footer it carries stays byte-for-byte identical and history
 * recovery keeps matching it.
 */
final class FreebieLifecycleMessages {
    private FreebieLifecycleMessages() {}

    static final int ENDED_COLOUR = 0x95A5A6;
    static final String ENDED_PREFIX = "Ended: ", ENDED_TEXT = "This giveaway has ended.",
            WITHDRAWN_TEXT = "This giveaway was withdrawn.";
    /** The current claim heading, plus the older wording still on cards posted before it changed. */
    private static final List<String> CLAIM_BLOCKS = List.of("\n\n" + FreebieCards.CLAIM_HEADING + "\n", "\n\n**Claim your game**\n");
    private static final Set<String> DEADLINE_FIELDS = Set.of(FreebieCards.ENDS, "CLAIM WINDOW", "OFFER ENDS");

    /**
     * Edit for a posted alert: grey, "Ended:" title, the claim link line replaced by the ended sentence, deadline
     * shown as ended, content (and so any role mention) cleared, claim button removed. Allowed mentions are empty.
     */
    static MessageEditData ended(List<MessageEmbed> embeds, List<? extends MessageTopLevelComponent> components,
                                 String reference, boolean withdrawn) {
        List<MessageEmbed> edited = new ArrayList<>();
        for (MessageEmbed embed : embeds) edited.add(isCard(embed, reference) ? endedEmbed(embed, withdrawn) : embed);
        return new MessageEditBuilder().setContent("").setEmbeds(edited).setComponents(withoutClaim(components))
                .setAllowedMentions(List.of()).build();
    }

    static MessageEmbed endedEmbed(MessageEmbed card, boolean withdrawn) {
        var builder = new EmbedBuilder(card).setColor(ENDED_COLOUR);
        String title = card.getTitle();
        if (title != null && !title.startsWith(ENDED_PREFIX)) {
            String prefixed = ENDED_PREFIX + title;
            builder.setTitle(prefixed.length() <= MessageEmbed.TITLE_MAX_LENGTH ? prefixed
                    : prefixed.substring(0, MessageEmbed.TITLE_MAX_LENGTH - 1) + "…", null);
        } else if (title != null) builder.setTitle(title, null);
        String sentence = "**" + (withdrawn ? WITHDRAWN_TEXT : ENDED_TEXT) + "**";
        String description = card.getDescription() == null ? "" : card.getDescription();
        String kept = description;
        for (String block : CLAIM_BLOCKS) {
            int claim = kept.indexOf(block);
            if (claim >= 0) kept = kept.substring(0, claim);
        }
        if (!kept.endsWith(sentence)) kept = kept.isBlank() ? sentence : kept + "\n\n" + sentence;
        builder.setDescription(kept);
        builder.clearFields();
        for (var field : card.getFields())
            builder.addField(DEADLINE_FIELDS.contains(field.getName())
                    ? new MessageEmbed.Field(field.getName(), withdrawn ? "Withdrawn" : "Ended", field.isInline()) : field);
        return builder.build();
    }

    /** Already in the ended state: idempotent edits succeed without touching Discord again. */
    static boolean isEnded(Message message, String reference) {
        return message.getEmbeds().stream().filter(e -> isCard(e, reference)).findFirst()
                .map(e -> e.getTitle() != null && e.getTitle().startsWith(ENDED_PREFIX)
                        && rows(message.getComponents()).stream().flatMap(r -> r.getComponents().stream()).noneMatch(c -> isClaim(c)))
                .orElse(false);
    }

    private static boolean isCard(MessageEmbed embed, String reference) {
        return FreebieMessages.isAlertFooter(embed, reference);
    }

    /** The claim buttons are link buttons labelled "Claim …" (and Instant Checkout); anything else (such as Unsubscribe) stays. */
    static boolean isClaim(ActionRowChildComponent component) {
        return component instanceof Button button && button.getStyle() == ButtonStyle.LINK
                && (button.getLabel().startsWith("Claim") || button.getLabel().startsWith("Instant Checkout"));
    }

    static List<ActionRow> withoutClaim(List<? extends MessageTopLevelComponent> components) {
        List<ActionRow> kept = new ArrayList<>();
        for (var row : rows(components)) {
            List<ActionRowChildComponent> children = new ArrayList<>();
            // The spacers only exist to center the claim button, so they go with it.
            for (var child : row.getComponents()) if (!isClaim(child) && !FreebieCards.isSpacer(child)) children.add(child);
            if (!children.isEmpty()) kept.add(children.size() == row.getComponents().size() ? row : ActionRow.of(children));
        }
        return kept;
    }

    private static List<ActionRow> rows(List<? extends MessageTopLevelComponent> components) {
        List<ActionRow> rows = new ArrayList<>();
        for (var component : components) if (component instanceof ActionRow row) rows.add(row);
        return rows;
    }

    // ------------------------------------------------------------------ reminder

    /** One game of a reminder, with a link to its alert in the channel when known. */
    record Due(FreebieOffer offer, Optional<String> alertUrl) {}

    /** {@code key}: the reminder job ID for channel reminders, or {@code remind|<offerId>|<channelId>} for older ones. */
    static String reminderReference(String key) {
        return "Ending soon • ref " + FreebieMessages.nonce(key).substring(0, 12);
    }

    static String reminderNonce(String key) { return FreebieMessages.nonce(key); }

    static String reminderReference(String offerId, String channelId) {
        return reminderReference(FreebieLifecycleStore.remindId(offerId, channelId));
    }

    static String reminderNonce(String offerId, String channelId) {
        return reminderNonce(FreebieLifecycleStore.remindId(offerId, channelId));
    }

    static boolean carriesReminderReference(Message message, String key) {
        String reference = reminderReference(key);
        return message.getEmbeds().stream().anyMatch(e -> e.getFooter() != null && reference.equals(e.getFooter().getText()));
    }

    static String alertUrl(String guildId, String channelId, String messageId) {
        return "https://discord.com/channels/" + guildId + "/" + channelId + "/" + messageId;
    }

    /** A single-game reminder for an older per-offer job (tests and history references). */
    static MessageCreateData reminder(FreebieOffer offer, String channelId, Optional<String> roleId, Instant now) {
        return reminder(List.of(new Due(offer, Optional.empty())), FreebieLifecycleStore.remindId(offer.id(), channelId),
                roleId.map(Set::of).orElse(Set.of()), now);
    }

    /**
     * One message for every game in a channel ending at about the same time: a line per game (its claim link and a link
     * back to its alert), one claim button per game (at most five) and the time left until the earliest end. A normal
     * message, not a reply. Only {@code roleIds} may be pinged, never everyone/here or users; empty means no ping.
     */
    static MessageCreateData reminder(List<Due> due, String key, Set<String> roleIds, Instant now) {
        Instant end = due.stream().map(d -> d.offer().realEnd().orElseThrow()).min(Comparator.naturalOrder()).orElseThrow();
        long hours = FreebieLifecycle.hoursLeft(end, now);
        int room = Math.max(60, 3500 / due.size() - 120);
        var lines = new StringBuilder();
        for (var d : due) {
            lines.append("**[").append(text(title(d.offer().title()), Math.min(150, room))).append("](")
                    .append(d.offer().claimUrl().toASCIIString()).append(")**");
            d.alertUrl().ifPresent(url -> lines.append(" · [alert](").append(url).append(")"));
            lines.append("\n");
        }
        String games = due.size() == 1 ? "" : due.size() + " games · ";
        var embed = Embeds.of("⏰", "Ending soon")
                .setDescription(lines + games + "Ends in about " + hours + (hours == 1 ? " hour" : " hours")
                        + " (<t:" + end.getEpochSecond() + ":f>).")
                .setTimestamp(null).setFooter(reminderReference(key)).build();
        List<Button> buttons = new ArrayList<>();
        if (due.size() == 1) buttons.add(FreebieCards.claimButton(due.getFirst().offer()));
        else for (var d : due.subList(0, Math.min(5, due.size())))
            buttons.add(Button.link(d.offer().claimUrl().toString(), FreebieMessages.label("Claim " + title(d.offer().title()))));
        var builder = new MessageCreateBuilder().setEmbeds(embed).setComponents(ActionRow.of(buttons))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        if (!roleIds.isEmpty()) {
            var sorted = new TreeSet<>(roleIds);
            builder.setContent(String.join(" ", sorted.stream().map(r -> "<@&" + r + ">").toList())).mentionRoles(sorted);
        }
        return builder.build();
    }
}
