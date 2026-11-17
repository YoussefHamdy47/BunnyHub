package org.bunnys.handler.cards;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

/**
 * Sending and editing {@link Card}s from interactions and channels, so callers never handle the Components V2 flag,
 * acknowledgement state or allowed mentions themselves. All calls queue and never block.
 */
public final class Cards {
    private Cards() {}

    /** A reply to a slash command, button, menu or modal; follows up when the interaction was already acknowledged. */
    public static void reply(IReplyCallback event, Card card, boolean ephemeral) {
        if (event.isAcknowledged()) event.getHook().sendMessage(card.message()).setEphemeral(ephemeral).queue(null, ignored -> {});
        else event.reply(card.message()).setEphemeral(ephemeral).queue(null, ignored -> {});
    }

    /**
     * Replaces the message a button or menu sits on with {@code card}. A message first sent as an embed may not
     * become a card; then the clicker gets the card as a new private reply instead of a dead control.
     */
    public static <E extends IMessageEditCallback & IReplyCallback> void edit(E event, Card card) {
        if (event.isAcknowledged()) {
            event.getHook().editOriginal(card.edit()).queue(null,
                    failed -> event.getHook().sendMessage(card.message()).setEphemeral(true).queue(null, ignored -> {}));
            return;
        }
        event.editMessage(card.edit()).queue(null,
                failed -> event.reply(card.message()).setEphemeral(true).queue(null, ignored -> {}));
    }

    /** A new message in a channel. */
    public static void send(MessageChannel channel, Card card) {
        channel.sendMessage(card.message()).queue(null, ignored -> {});
    }
}
