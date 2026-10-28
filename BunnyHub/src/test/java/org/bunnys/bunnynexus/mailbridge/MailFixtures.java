package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Gmail API message JSON as {@code users.messages.get?format=full} returns it, built for tests. */
final class MailFixtures {
    private MailFixtures() {}

    static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static DataObject header(String name, String value) {
        return DataObject.empty().put("name", name).put("value", value);
    }

    static DataObject part(String mime, String body) {
        return DataObject.empty().put("mimeType", mime)
                .put("headers", DataArray.empty().add(header("Content-Type", mime + "; charset=UTF-8")))
                .put("body", DataObject.empty().put("size", body.length()).put("data", b64(body)));
    }

    /** multipart/alternative with whichever of plain and html is non-null. */
    static DataObject message(String id, String from, String subject, Instant received, String plain, String html) {
        DataArray parts = DataArray.empty();
        if (plain != null) parts.add(part("text/plain", plain));
        if (html != null) parts.add(part("text/html", html));
        DataObject payload = DataObject.empty().put("mimeType", "multipart/alternative")
                .put("headers", DataArray.empty().add(header("From", from)).add(header("Subject", subject)))
                .put("parts", parts);
        return DataObject.empty().put("id", id).put("threadId", id).put("internalDate", String.valueOf(received.toEpochMilli()))
                .put("labelIds", DataArray.empty().add("INBOX")).put("payload", payload);
    }

    static final String SENDER = "ChatGPT <noreply@tm.openai.com>";

    static DataObject task(String id, String taskName, Instant received) {
        return message(id, SENDER, "Your task \"" + taskName + "\" has finished", received,
                "Your scheduled task " + taskName + " completed. Here are this week's highlights: three new openings.\n"
                        + "View it here: https://chatgpt.com/c/abc123", null);
    }

    static Map<MailCategory, List<Pattern>> defaultRules() {
        Map<MailCategory, List<Pattern>> rules = new EnumMap<>(MailCategory.class);
        for (MailCategory category : MailCategory.values())
            if (category != MailCategory.UNKNOWN) rules.put(category, List.of(MailBridgeConfig.literal(category.defaultPattern())));
        return rules;
    }

    static MailBridgeConfig config(int maxAttempts, Optional<String> diagnostic) {
        return new MailBridgeConfig("client", "secret", "refresh", MailBridgeConfig.DEFAULT_GUILD, MailBridgeConfig.DEFAULT_CHANNEL,
                MailBridgeConfig.DEFAULT_MENTION, diagnostic, MailBridgeConfig.DEFAULT_QUERY, Set.of("openai.com", "chatgpt.com"),
                defaultRules(), java.time.Duration.ofMinutes(5), maxAttempts);
    }
}
