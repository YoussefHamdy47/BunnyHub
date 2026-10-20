package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Discord messages the bridge posts. Every message allows exactly one mention: the configured user. Anything
 * that came from the email is escaped, has its links removed and can't form a mention, so an email can't ping
 * anyone, hide a link behind text, or break the layout.
 */
public final class MailBridgeCards {
    static final String REF_PREFIX = "Gmail ref ";
    static final int SUMMARY_LIMIT = 700;
    private static final Pattern LINKS = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private MailBridgeCards() {}

    /** The notification for a recognised task email. */
    public static MessageCreateData notification(MailMessage mail, MailCategory category, String mentionUserId) {
        EmbedBuilder embed = new EmbedBuilder().setColor(category.colour()).setTitle(category.label())
                .addField("Completed", "<t:" + mail.received().getEpochSecond() + ":F> (<t:" + mail.received().getEpochSecond() + ":R>)", false)
                .addField("Email subject", untrusted(mail.subject().isBlank() ? "(no subject)" : mail.subject(), MessageEmbed.VALUE_MAX_LENGTH), false);
        summary(mail).ifPresent(text -> embed.addField("From the email", text, false));
        Optional<URI> report = mail.reportLink();
        embed.addField("Full report", report.map(link -> "[Open the report in ChatGPT](" + link.toASCIIString() + ")")
                .orElse("The email has no direct link. Open ChatGPT and check **Tasks** (or the task's chat) for the full report."), false);
        embed.setFooter(REF_PREFIX + mail.id()).setTimestamp(mail.received());
        return mention(mentionUserId, "New results from **" + category.label() + "**", embed.build());
    }

    /**
     * Posted to the diagnostic channel when an email looked like a task notice but couldn't be classified.
     * Carries no subject or body, only enough to find the email and fix the patterns.
     */
    public static MessageCreateData unclassified(MailMessage mail, String reason) {
        MessageEmbed embed = new EmbedBuilder().setColor(MailCategory.UNKNOWN.colour()).setTitle("Unclassified task email")
                .setDescription("A task email arrived but didn't match exactly one task, so it wasn't forwarded. "
                        + "Check the patterns with `/mail-bridge diagnose`.")
                .addField("Why", untrusted(reason, 200), true)
                .addField("Sender domain", untrusted(mail.senderDomain().isEmpty() ? "unknown" : mail.senderDomain(), 100), true)
                .addField("Received", "<t:" + mail.received().getEpochSecond() + ":F>", false)
                .setFooter(REF_PREFIX + mail.id()).build();
        return new MessageCreateBuilder().setEmbeds(embed).setAllowedMentions(EnumSet.noneOf(MentionType.class)).build();
    }

    /** Posted once when Google stops accepting the stored credentials. */
    public static MessageCreateData authProblem(String mentionUserId, String reason, Instant since) {
        MessageEmbed embed = new EmbedBuilder().setColor(0xD0021B).setTitle("Gmail access needs attention")
                .setDescription("Task emails can't be read until Gmail is authorised again. Create a new refresh token "
                        + "(see the README, \"Mail bridge\"), update `GMAIL_REFRESH_TOKEN` and restart.")
                .addField("Problem", untrusted(reason, 300), false)
                .addField("Since", "<t:" + since.getEpochSecond() + ":F>", false).build();
        return mention(mentionUserId, "The mail bridge has stopped reading Gmail.", embed);
    }

    /** {@code /mail-bridge test}: proves the bot can post and ping in the channel. */
    public static MessageCreateData test(String mentionUserId, Instant now) {
        MessageEmbed embed = new EmbedBuilder().setColor(0x50E3C2).setTitle("Mail bridge test")
                .setDescription("If you got pinged and can see this, task notifications will arrive here.")
                .setTimestamp(now).build();
        return mention(mentionUserId, "Test notification", embed);
    }

    private static MessageCreateData mention(String userId, String text, MessageEmbed embed) {
        return new MessageCreateBuilder().setContent("<@" + userId + "> " + text).setEmbeds(embed)
                .setAllowedMentions(EnumSet.of(MentionType.USER)).mentionUsers(userId).build();
    }

    /** A short excerpt of the body, if it says more than the subject does. */
    static Optional<String> summary(MailMessage mail) {
        String text = mail.text();
        if (text.isBlank()) return Optional.empty();
        String withoutSubject = text.replace(mail.subject(), "").strip();
        if (withoutSubject.length() < 20) return Optional.empty();
        String excerpt = untrusted(withoutSubject, SUMMARY_LIMIT);
        return excerpt.isBlank() ? Optional.empty() : Optional.of(excerpt);
    }

    /**
     * Makes email-controlled text inert: links removed, markdown and brackets escaped (so nothing can become a masked
     * link), and {@code @} and {@code <} broken with a
     * zero-width space so nothing can form a mention, a channel link or a slash-command link.
     */
    public static String untrusted(String text, int limit) {
        String cleaned = LINKS.matcher(text).replaceAll("[link]").replaceAll("\\n{3,}", "\n\n").strip();
        if (cleaned.length() > limit - 40) cleaned = cleaned.substring(0, limit - 41).strip() + "…";
        String escaped = MarkdownSanitizer.escape(cleaned, true).replace("[", "\\[").replace("]", "\\]")
                .replace("@", "@\u200B").replace("<", "<\u200B");
        return escaped.length() <= limit ? escaped : escaped.substring(0, limit - 1) + "…";
    }
}
