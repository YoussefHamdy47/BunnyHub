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
    private static final String CLAIM_BLOCK = "\n\n**Claim your game**\n";
    private static final Set<String> DEADLINE_FIELDS = Set.of("CLAIM WINDOW", "OFFER ENDS");

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
        int claim = description.indexOf(CLAIM_BLOCK);
        String kept = claim >= 0 ? description.substring(0, claim) : description;
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

    /** The claim button is the link button labelled "Claim …"; anything else (such as Unsubscribe) stays. */
    static boolean isClaim(ActionRowChildComponent component) {
        return component instanceof Button button && button.getStyle() == ButtonStyle.LINK && button.getLabel().startsWith("Claim");
    }

    static List<ActionRow> withoutClaim(List<? extends MessageTopLevelComponent> components) {
        List<ActionRow> kept = new ArrayList<>();
        for (var row : rows(components)) {
            List<ActionRowChildComponent> children = new ArrayList<>();
            for (var child : row.getComponents()) if (!isClaim(child)) children.add(child);
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

    static String reminderReference(String offerId, String channelId) {
        return "Ending soon • ref " + FreebieMessages.nonce("remind|" + offerId + "|" + channelId).substring(0, 12);
    }

    static String reminderNonce(String offerId, String channelId) {
        return FreebieMessages.nonce("remind|" + offerId + "|" + channelId);
    }

    static boolean carriesReminderReference(Message message, String offerId, String channelId) {
        String reference = reminderReference(offerId, channelId);
        return message.getEmbeds().stream().anyMatch(e -> e.getFooter() != null && reference.equals(e.getFooter().getText()));
    }

    /**
     * Short reply to the original alert, with the claim button. Only {@code roleId} may be pinged, never
     * everyone/here or users, and the reply does not ping the replied-to author.
     */
    static MessageCreateData reminder(FreebieOffer offer, String channelId, Optional<String> roleId, Instant now) {
        Instant end = offer.realEnd().orElseThrow();
        long hours = FreebieLifecycle.hoursLeft(end, now);
        var embed = Embeds.of("⏰", "Ending soon")
                .setDescription("**[" + text(title(offer.title()), 200) + "](" + offer.claimUrl().toASCIIString() + ")**\n"
                        + "Ends in about " + hours + (hours == 1 ? " hour" : " hours") + " (<t:" + end.getEpochSecond() + ":f>).")
                .setTimestamp(null).setFooter(reminderReference(offer.id(), channelId)).build();
        var builder = new MessageCreateBuilder().setEmbeds(embed).setComponents(ActionRow.of(FreebieCards.claimButton(offer)))
                .setAllowedMentions(List.of()).mentionRepliedUser(false);
        roleId.ifPresent(role -> builder.setContent("<@&" + role + ">").mentionRoles(role));
        return builder.build();
    }
}
