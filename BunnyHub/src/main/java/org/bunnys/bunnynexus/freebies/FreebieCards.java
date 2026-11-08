package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.text;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.title;

/**
 * The one public game card and its claim button, built only from the reviewed offer's saved metadata. Every store and
 * every surface (channel post, DM, review preview) uses the same fields in the same order; the store only changes the
 * emoji, the author icon and the launcher name.
 */
final class FreebieCards {
    private FreebieCards() {}

    static final String PRICE = "Price", ENDS = "Ends", LAUNCHER = "Launcher", REGION = "Region", MOBILE = "Mobile",
            HOW_TO_CLAIM = "How to claim";
    static final String CLAIM_LABEL = "Claim Free Game";

    static String emoji(FreebieStore store) {
        return switch (store) {
            case EPIC -> AppDesign.Emojis.EPIC;
            case STEAM -> AppDesign.Emojis.STEAM;
            default -> "🎁";
        };
    }

    private static String icon(FreebieStore store) {
        return switch (store) {
            case EPIC -> "https://cdn.discordapp.com/emojis/824679104220626954.png";
            case STEAM -> "https://cdn.discordapp.com/emojis/705062577817780315.png";
            default -> null;
        };
    }

    /**
     * Custom ID prefix of the disabled spacer buttons older cards were posted with. No longer created; kept so ended
     * edits of those cards remove them together with the claim button.
     */
    static final String SPACER_PREFIX = "freebie_pad";
    /** Heading above the claim link on older cards. Lifecycle edits look for it to strip the link once an offer ends. */
    static final String CLAIM_HEADING = "**Claim Game**";

    static boolean isSpacer(ActionRowChildComponent component) {
        return component instanceof Button button && button.getCustomId() != null && button.getCustomId().startsWith(SPACER_PREFIX + ":");
    }

    /** The claim button: same label everywhere, the launcher's emoji. */
    static Button claimButton(FreebieOffer offer) {
        return Button.link(offer.claimUrl().toString(), CLAIM_LABEL).withEmoji(Emoji.fromFormatted(emoji(offer.store())));
    }

    /** The button row of a single-game card when no Epic catalog facts are known: the claim button only. */
    static ActionRow claimRow(FreebieOffer offer) {
        return ActionRow.of(claimButton(offer));
    }

    /** The channel card: no claim steps, since the store's own page shows them and the button does the claiming. */
    static MessageEmbed embed(FreebieOffer offer, String footer) {
        return embed(offer, footer, false);
    }

    /** {@code steps}: include the source's claim steps (DMs and the owner's review preview). */
    static MessageEmbed embed(FreebieOffer offer, String footer, boolean steps) {
        String mark = emoji(offer.store());
        var card = Embeds.of(mark, text(title(offer.title()), 250 - mark.length()))
                .setAuthor("BunnyNexus • FREE GAME • " + offer.store().label(), null, icon(offer.store()))
                .setUrl(offer.claimUrl().toString())
                .setDescription(text(offer.description(), 300))
                .addField(PRICE, offer.worth().map(w -> "~~" + text(w, 40) + "~~ → **FREE**").orElse("**FREE**"), true)
                .addField(ENDS, deadline(offer), true)
                .addField(LAUNCHER, offer.store().label(), true);
        if (offer.id().startsWith("epic:")) card.addField(REGION, text(offer.region(), 1000), false);
        if (steps && offer.instructions() != null && !offer.instructions().isBlank())
            card.addField(HOW_TO_CLAIM, text(offer.instructions(), 650), false);
        offer.image().ifPresent(image -> card.setImage(image.toString()));
        return card.setFooter(footer).setTimestamp(null).build();
    }

    /** Relative and absolute end; "estimated" only when the saved date really is a guess. */
    static String deadline(FreebieOffer offer) {
        return offer.endsAt().map(end -> "<t:" + end.getEpochSecond() + ":R>\n<t:" + end.getEpochSecond() + ":f>"
                + (offer.realEnd().isEmpty() ? " (estimated)" : ""))
                .orElse("No end date listed");
    }
}
