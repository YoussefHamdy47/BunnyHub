package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Where notifications go. Implemented with the bot's own connection ({@link JdaMailPoster}); tests fake it. */
public interface MailPoster {
    /** Why the bot can't post in the channel right now, or empty when it can. */
    Optional<String> problem(String guildId, String channelId);

    /**
     * Posts the message. Completes with Discord's message ID once Discord accepted it. A failure carrying a
     * Discord error response means it was definitely not posted; any other failure leaves that unknown.
     */
    CompletableFuture<String> post(String channelId, MessageCreateData message, String nonce);

    /** Whether one of the bot's recent messages in the channel carries this ref; empty if history can't be read. */
    Optional<Boolean> alreadyPosted(String channelId, String ref);
}
