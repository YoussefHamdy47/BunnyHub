package org.bunnys.bunnynexus.timers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.replacer.ComponentReplacer;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.handler.cards.Card;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sends the timer screens as Components V2 cards. The screens are still composed as embeds ({@link TimerEmbeds},
 * {@link SessionEmbeds}); this class renders them, and keeps messages posted before the switch working: a session
 * menu can outlive a deploy, and a posted embed cannot become a card, so it is edited as the embed it is.
 */
public final class TimerCards {
    private TimerCards() {}

    static final String OBJECTIVE = "> **Mission Objective:** ";

    public static Card card(MessageEmbed embed, ActionRow... rows) { return Card.fromEmbed(embed, List.of(rows)); }

    public static MessageCreateData message(MessageEmbed embed, ActionRow... rows) { return card(embed, rows).message(); }

    /** A new card that notifies {@code userId}, and nobody else. */
    public static MessageCreateData ping(String userId, MessageEmbed embed) { return card(embed).pinging(userId).message(); }

    /** Replaces a deferred reply or prompt whose format is unknown. */
    public static void show(InteractionHook hook, MessageEmbed embed, ActionRow... rows) {
        show(hook, embed, List.of(rows), null, null);
    }

    public static void show(InteractionHook hook, MessageEmbed embed, List<ActionRow> rows, Consumer<? super Message> done,
                            Consumer<? super Throwable> failed) {
        hook.editOriginal(Card.fromEmbed(embed, rows).edit()).queue(done,
                rejected -> hook.editOriginalEmbeds(embed).setComponents(rows).queue(done, failed));
    }

    /** As {@link #show}, with the owner's mention above the card. An edit never notifies. */
    public static void showWithMention(InteractionHook hook, String userId, MessageEmbed embed) {
        hook.editOriginal(card(embed).pinging(userId).edit()).queue(null,
                rejected -> hook.editOriginalEmbeds(embed).setContent("<@" + userId + ">").setComponents().queue());
    }

    /** Replaces {@code message}, already acknowledged through {@code hook}, in the format it was posted in. */
    public static void update(InteractionHook hook, Message message, MessageEmbed embed, List<ActionRow> rows,
                              Consumer<? super Message> done) {
        if (message.isUsingComponentsV2()) hook.editOriginal(Card.fromEmbed(embed, rows).edit()).queue(done);
        else hook.editOriginalEmbeds(embed).setComponents(rows).queue(done);
    }

    /** Answers a button click that was not acknowledged yet by replacing its message. */
    public static void replace(ButtonInteractionEvent event, MessageEmbed embed, List<ActionRow> rows) {
        if (event.getMessage().isUsingComponentsV2()) event.editMessage(Card.fromEmbed(embed, rows).edit()).queue();
        else event.editMessageEmbeds(embed).setComponents(rows).queue();
    }

    /** Swaps the button row and leaves the rest of the message as it is. */
    public static void setRow(InteractionHook hook, Message message, ActionRow row) {
        if (message.isUsingComponentsV2()) hook.editOriginal(withRow(message, row)).queue(null, ignored -> {});
        else hook.editOriginalComponents(row).queue(null, ignored -> {});
    }

    /** As {@link #setRow(InteractionHook, Message, ActionRow)}, for a message reached without an interaction. */
    public static void setRow(Message message, ActionRow row) {
        (message.isUsingComponentsV2() ? message.editMessage(withRow(message, row)) : message.editMessageComponents(row))
                .queue(null, ignored -> {});
    }

    /** Every control on {@code message} disabled, its text untouched. */
    public static RestAction<Message> disable(Message message) {
        return message.isUsingComponentsV2() ? message.editMessage(disabled(message))
                : message.editMessageComponents(message.getComponentTree().asDisabled().getComponents());
    }

    public static MessageEditData disabled(Message message) { return edit(message.getComponentTree().asDisabled()); }

    static MessageEditData withRow(Message message, ActionRow row) {
        return edit(message.getComponentTree().replace(ComponentReplacer.<ActionRow>of(ActionRow.class, any -> true, any -> row)));
    }

    private static MessageEditData edit(MessageComponentTree tree) {
        return new MessageEditBuilder().useComponentsV2(true).setComponents(tree.getComponents()).setAllowedMentions(List.of()).build();
    }

    /** What a session refresh needs from the posted menu: the embed itself, or a stand-in holding a card's objective. */
    public static MessageEmbed current(Message message) {
        if (!message.isUsingComponentsV2()) return message.getEmbeds().getFirst();
        var current = new EmbedBuilder().setTitle("Session");
        for (var block : message.getComponentTree().findAll(TextDisplay.class)) {
            int start = block.getContent().indexOf(OBJECTIVE);
            if (start >= 0) return current.setDescription(block.getContent().substring(start)).build();
        }
        return current.build();
    }

    /** Whether the message still shows the screen titled {@code title}, as an embed or a card. */
    public static boolean showing(Message message, String title) {
        if (!message.isUsingComponentsV2())
            return !message.getEmbeds().isEmpty() && title.equals(message.getEmbeds().getFirst().getTitle());
        return message.getComponentTree().findAll(TextDisplay.class).stream().anyMatch(t -> t.getContent().startsWith("### " + title));
    }
}
