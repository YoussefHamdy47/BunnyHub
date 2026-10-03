package org.bunnys.utils;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import java.util.List;

/**
 * Private answers for component and modal interactions. A follow-up to a deferred edit is a new message, so it can
 * always be ephemeral. Commands deferred with a public reply use {@code CommandContext.replyTransient} instead.
 */
public final class Replies {
    private Replies() {}

    public static void error(IReplyCallback event, String title, String text) {
        privately(event, Embeds.error(title, text));
    }

    public static void privately(IReplyCallback event, MessageEmbed embed) {
        if (event.isAcknowledged())
            event.getHook().sendMessageEmbeds(embed).setEphemeral(true).setAllowedMentions(List.of()).queue(null, ignored -> {});
        else
            event.replyEmbeds(embed).setEphemeral(true).setAllowedMentions(List.of()).queue(null, ignored -> {});
    }
}
