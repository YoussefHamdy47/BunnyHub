package org.bunnys.bunnynexus.updates;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import org.bunnys.handler.commands.CommandLinks;

import java.util.ArrayList;
import java.util.List;

/** An ad as the small card under a free-game alert: image, title, text, optional link button, a "From BunnyNexus" line. */
public final class AdCards {
    static final String FROM = "-# From BunnyNexus";

    private AdCards() {}

    public static Container card(Ad ad) {
        List<ContainerChildComponent> parts = new ArrayList<>();
        ad.image().ifPresent(image -> parts.add(MediaGallery.of(MediaGalleryItem.fromUrl(image.toString()))));
        // {/free-games setup} and `/free-games setup` become clickable commands.
        String text = CommandLinks.render(ad.text());
        parts.add(TextDisplay.of(ad.title().map(t -> "### " + t + "\n").orElse("") + text + "\n" + FROM));
        ad.link().ifPresent(link -> parts.add(ActionRow.of(Button.link(link.url().toString(), link.label()))));
        return Container.of(parts);
    }

    /** The ad on its own, for the owner's preview. */
    public static MessageCreateData preview(Ad ad) {
        return new MessageCreateBuilder().useComponentsV2(true).setComponents(card(ad)).setAllowedMentions(List.of()).build();
    }

    /** The ad under a note for the owner. */
    public static MessageCreateData preview(Ad ad, String note) {
        return new MessageCreateBuilder().useComponentsV2(true).setComponents(TextDisplay.of(note), card(ad)).setAllowedMentions(List.of()).build();
    }
}
