package org.bunnys.bunnynexus.updates;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import org.bunnys.handler.commands.CommandLinks;

import java.util.ArrayList;
import java.util.List;

/**
 * An ad as the card under a free-game alert: image, an emoji heading, the text (with its links), its buttons inside
 * the card, and a "From BunnyNexus" line.
 */
public final class AdCards {
    static final String FROM = "-# From BunnyNexus";

    private AdCards() {}

    public static Container card(Ad ad) {
        List<ContainerChildComponent> parts = new ArrayList<>();
        ad.image().ifPresent(image -> parts.add(MediaGallery.of(MediaGalleryItem.fromUrl(image.toString()))));
        // {/free-games setup} and `/free-games setup` become clickable commands; [words](https://…) are links.
        String text = CommandLinks.render(ad.text());
        String heading = ad.title().map(t -> "### " + ad.emoji().map(e -> e + " ").orElse("") + t + "\n").orElse("");
        parts.add(TextDisplay.of(heading + text));
        if (!ad.links().isEmpty()) {
            List<Button> buttons = new ArrayList<>();
            for (Ad.Link link : ad.links()) {
                Button button = Button.link(link.url().toString(), link.label());
                buttons.add(link.emoji().map(e -> button.withEmoji(Emoji.fromFormatted(e))).orElse(button));
            }
            parts.add(ActionRow.of(buttons));
        }
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));
        parts.add(TextDisplay.of(FROM));
        return Container.of(parts);
    }

    /**
     * {@code message} with the ad card under it, when it is a Components V2 message and the card fits Discord's 40
     * components; otherwise the message as it was.
     */
    public static MessageCreateData attach(MessageCreateData message, java.util.Optional<Container> ad) {
        if (ad.isEmpty() || !message.isUsingComponentsV2()) return message;
        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> top = new ArrayList<>(message.getComponents());
        top.add(ad.get());
        if (count(top) > 40) return message;
        return MessageCreateBuilder.from(message).setComponents(top).build();
    }

    /** Every component Discord counts, nested ones and accessories included. */
    static int count(List<? extends net.dv8tion.jda.api.components.MessageTopLevelComponent> top) {
        int total = 0;
        for (var component : top) {
            total++;
            if (component instanceof Container container)
                for (var child : container.getComponents()) {
                    total++;
                    if (child instanceof net.dv8tion.jda.api.components.section.Section section) total += section.getContentComponents().size() + 1;
                    else if (child instanceof ActionRow row) total += row.getComponents().size();
                }
            else if (component instanceof ActionRow row) total += row.getComponents().size();
        }
        return total;
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
