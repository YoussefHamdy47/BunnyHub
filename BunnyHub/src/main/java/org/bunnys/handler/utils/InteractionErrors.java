package org.bunnys.handler.utils;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.LoggerFactory;

public final class InteractionErrors {
    private InteractionErrors() {}

    /** Only deliberately authored validation failures may expose their message to Discord. */
    public static final class InputFailure extends IllegalArgumentException {
        public InputFailure(String message) { super(message); }
    }

    public static final class StateFailure extends IllegalStateException {
        public StateFailure(String message) { super(message); }
    }

    public static String userMessage(Throwable error) {
        if (!(error instanceof InputFailure || error instanceof StateFailure) || error.getMessage() == null)
            return "Something went wrong. Please try again.";
        String message = error.getMessage().replace("@", "@\u200B");
        return message.substring(0, Math.min(message.length(), 1500));
    }

    public static void report(IReplyCallback event, Throwable error) {
        LoggerFactory.getLogger(InteractionErrors.class).error("Interaction {} failed | {}",
                event.getId(), org.bunnys.utils.FailureDiagnostics.describe(error));
        String message = userMessage(error);
        if (!event.isAcknowledged()) event.reply(message).setEphemeral(true).queue();
        else event.getHook().sendMessage(message).setEphemeral(true).queue();
    }
}
