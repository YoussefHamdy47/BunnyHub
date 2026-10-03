package org.bunnys.handler.utils;

import java.io.Serial;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.LoggerFactory;
import org.bunnys.utils.FailureDiagnostics;
import org.bunnys.utils.Replies;

public final class InteractionErrors {
    private InteractionErrors() {}

    /** Only deliberately authored validation failures may expose their message to Discord. */
    public static final class InputFailure extends IllegalArgumentException {
        @Serial private static final long serialVersionUID = 1L;
        public InputFailure(String message) { super(message); }
    }

    public static final class StateFailure extends IllegalStateException {
        @Serial private static final long serialVersionUID = 1L;
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
                event.getId(), FailureDiagnostics.describe(error));
        Replies.error(event, "Action failed", userMessage(error));
    }
}
