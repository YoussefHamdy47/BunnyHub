package org.bunnys.utils;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** Logs failures locally; user replies contain no exception details or connection strings. */
public final class ErrorReporter {
    private ErrorReporter() {}
    public static String report(String action, String guildId, Throwable error) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        LoggerFactory.getLogger(ErrorReporter.class).error("Failure {} in {} (guild {}) | {}",
                reference, action, guildId, FailureDiagnostics.describe(error));
        return reference;
    }
    public static void reportAndReply(String action, Throwable error, IReplyCallback event) {
        var embed = SystemEmbeds.crashed(report(action, null, error), false);
        if (event.isAcknowledged()) event.getHook().sendMessageEmbeds(embed).setEphemeral(true).queue();
        else event.replyEmbeds(embed).setEphemeral(true).queue();
    }
}
