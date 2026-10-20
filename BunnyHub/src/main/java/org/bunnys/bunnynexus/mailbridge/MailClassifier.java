package org.bunnys.bunnynexus.mailbridge;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Decides which task an email belongs to, from configured patterns only.
 *
 * <ol>
 *   <li>The sender must match {@code MAIL_BRIDGE_SENDERS}; anything else is {@link Outcome#UNRELATED}.</li>
 *   <li>Patterns are tried on the subject first. Exactly one category matching there wins.</li>
 *   <li>Otherwise the body is tried the same way.</li>
 *   <li>No match, or more than one category matching at the same level, is never guessed: it comes back as
 *       {@link Outcome#UNMATCHED} or {@link Outcome#AMBIGUOUS} with category {@link MailCategory#UNKNOWN}.</li>
 * </ol>
 * Emails from the right sender that don't look like a task notice at all (receipts, security alerts) are
 * {@link Outcome#UNRELATED} too, so they never produce diagnostics.
 */
public final class MailClassifier {
    /** Words that mark an email from the sender as being about a scheduled task. */
    static final Pattern TASK_NOTICE = Pattern.compile("\\btasks?\\b", Pattern.CASE_INSENSITIVE);

    public enum Outcome { MATCHED, UNRELATED, UNMATCHED, AMBIGUOUS }

    public record Classification(MailCategory category, Outcome outcome, String reason) {
        public boolean forwardable() { return outcome == Outcome.MATCHED; }
    }

    private final Set<String> senders;
    private final Map<MailCategory, List<Pattern>> rules;

    public MailClassifier(Set<String> senders, Map<MailCategory, List<Pattern>> rules) {
        this.senders = Set.copyOf(senders);
        this.rules = Map.copyOf(rules);
    }

    public static MailClassifier from(MailBridgeConfig config) {
        return new MailClassifier(config.senders(), config.rules());
    }

    public Classification classify(MailMessage message) {
        if (!fromAllowedSender(message.fromAddress()))
            return new Classification(MailCategory.UNKNOWN, Outcome.UNRELATED, "sender not allowed");
        var bySubject = matching(message.subject());
        if (bySubject.size() == 1) return matched(bySubject.getFirst(), "subject");
        if (bySubject.size() > 1) return new Classification(MailCategory.UNKNOWN, Outcome.AMBIGUOUS, "subject matches " + bySubject);
        var byBody = matching(message.text());
        if (byBody.size() == 1) return matched(byBody.getFirst(), "body");
        if (byBody.size() > 1) return new Classification(MailCategory.UNKNOWN, Outcome.AMBIGUOUS, "body matches " + byBody);
        boolean looksLikeTask = TASK_NOTICE.matcher(message.subject()).find() || TASK_NOTICE.matcher(message.text()).find();
        return looksLikeTask ? new Classification(MailCategory.UNKNOWN, Outcome.UNMATCHED, "no task pattern matched")
                : new Classification(MailCategory.UNKNOWN, Outcome.UNRELATED, "not a task notice");
    }

    /** The address equals an entry, or its domain is the entry or a subdomain of it. */
    boolean fromAllowedSender(String address) {
        if (address.isEmpty()) return false;
        int at = address.lastIndexOf('@');
        String domain = at < 0 ? "" : address.substring(at + 1);
        for (String sender : senders) {
            if (sender.contains("@") ? address.equals(sender) : domain.equals(sender) || domain.endsWith("." + sender))
                return true;
        }
        return false;
    }

    private List<MailCategory> matching(String text) {
        List<MailCategory> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        for (MailCategory category : MailCategory.values()) {
            for (Pattern pattern : rules.getOrDefault(category, List.of()))
                if (pattern.matcher(text).find()) { found.add(category); break; }
        }
        return found;
    }

    private static Classification matched(MailCategory category, String where) {
        return new Classification(category, Outcome.MATCHED, "matched in " + where);
    }
}
