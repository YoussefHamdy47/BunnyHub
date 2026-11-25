package org.bunnys.bunnynexus.updates;

import net.dv8tion.jda.api.components.buttons.Button;
import org.bunnys.handler.cards.Card;

/**
 * The product-update message (docs/product-updates/README.md, rule 3-4): plain card, no ping, addressed to admins,
 * one button to turn product updates off for the server. Button ID: {@code updates:off}.
 */
public final class UpdateCards {
    public static final String PREFIX = "updates", OFF = "off";

    private UpdateCards() {}

    public static Card update(ProductUpdate update) {
        return Card.builder()
                .title(update.title())
                .text(update.pitch())
                .text(org.bunnys.handler.commands.CommandLinks.render("**Turn it on:** " + update.howTo()))
                .buttons(Button.secondary(PREFIX + ":" + OFF, "Turn off product updates"))
                .footer("For server admins • At most one product update every 14 days")
                .build();
    }
}
