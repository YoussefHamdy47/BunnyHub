package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.text;
import static org.bunnys.bunnynexus.freebies.FreebieMessages.title;

/** Launcher-specific public cards, built only from the reviewed offer's saved metadata. */
final class FreebieCards {
    private FreebieCards() {}

    static String emoji(FreebieStore store) {
        return switch (store) {
            case EPIC -> AppDesign.Emojis.EPIC;
            case STEAM -> AppDesign.Emojis.STEAM;
            default -> "🎁";
        };
    }

    static Button claimButton(FreebieOffer offer) {
        String label = switch (offer.store()) {
            case EPIC -> "Claim Epic offer";
            case STEAM -> "Claim Steam offer";
            default -> "Claim free game";
        };
        return Button.link(offer.claimUrl().toString(), label).withEmoji(Emoji.fromFormatted(emoji(offer.store())));
    }

    static MessageEmbed embed(FreebieOffer offer, String footer) {
        boolean epic = offer.store() == FreebieStore.EPIC;
        boolean steam = offer.store() == FreebieStore.STEAM;
        boolean featured = epic || steam;
        String mark = emoji(offer.store());
        String heading = epic ? "BunnyNexus • EPIC GAMES" : steam ? "BunnyNexus • STEAM"
                : "BunnyNexus • FREE GAME • " + offer.store().label();
        String icon = epic ? "https://cdn.discordapp.com/emojis/824679104220626954.png"
                : steam ? "https://cdn.discordapp.com/emojis/705062577817780315.png" : null;
        var card = Embeds.of(mark, text(title(offer.title()), 250 - mark.length()))
                .setAuthor(heading, null, icon)
                .setUrl(offer.claimUrl().toString())
                .setDescription(text(offer.description(), 500)
                        + "\n\n**Claim your game**\n[" + text(title(offer.title()), 200) + "](" + offer.claimUrl().toASCIIString() + ")")
                .addField(featured ? "THE DEAL" : "PRICE", offer.worth()
                        .map(w -> "~~" + text(w, 40) + "~~ → **FREE**\nListed value")
                        .orElse("**FREE**\nNo price listed"), true)
                .addField(featured ? "CLAIM WINDOW" : "OFFER ENDS", deadline(offer), true);
        if (featured) card.addField("PLATFORMS", text(offer.platforms(), 200), false);
        else card.addField("LAUNCHER", offer.store().label(), true);
        if (offer.instructions() != null && !offer.instructions().isBlank())
            card.addField(epic ? "GET STARTED ON EPIC" : steam ? "GET STARTED ON STEAM" : "HOW TO CLAIM",
                    text(offer.instructions(), 650), false);
        if (offer.id().startsWith("epic:")) card.addField("REGION", offer.region(), false);
        offer.image().ifPresent(image -> card.setImage(image.toString()));
        return card.setFooter(footer).setTimestamp(null).build();
    }

    private static String deadline(FreebieOffer offer) {
        return offer.endsAt().map(end -> "<t:" + end.getEpochSecond() + ":R>\n"
                + (offer.id().startsWith("epic:") ? "Ends: <t:" : "Estimated: <t:") + end.getEpochSecond() + ":f>")
                .orElse("No end date listed\nClaim while available");
    }
}

