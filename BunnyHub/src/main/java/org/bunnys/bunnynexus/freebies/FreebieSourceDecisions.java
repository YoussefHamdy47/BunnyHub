package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

/** Private owner resolution. Similar text is a question, never proof of catalog identity. */
public final class FreebieSourceDecisions {
    private FreebieSourceDecisions() {}
    /** SAVED: this click decided it. ALREADY: it was decided before (here or elsewhere). INVALID: not a decision. */
    public enum Status { SAVED, ALREADY, INVALID }
    public record Outcome(Status status, String decision, String message) {}

    static Outcome resolve(FreebieSourceStore store, String actor, String offerId, String action, java.time.Instant now) {
        String[] parts = action.split("-", 3);
        if (parts.length != 3 || !parts[2].matches("[0-9a-f]{12}") || !List.of("same", "separate", "ignore").contains(parts[1]))
            return new Outcome(Status.INVALID, "", "Invalid source decision.");
        return store.resolve(offerId, parts[2], parts[1], actor, now)
                ? new Outcome(Status.SAVED, parts[1], "Source decision saved. It does not approve a game or change an existing approval.")
                : new Outcome(Status.ALREADY, parts[1], "This question was already answered. Nothing changed.");
    }

    /**
     * The held report's message once answered: the decision on top, the original details below, and no decision
     * buttons, so the question no longer looks open and Clear finished can remove it. Never longer than Discord's
     * 2000 characters; mentions are text only (sent with no allowed mentions).
     */
    public static String decided(String original, Outcome outcome, String actor, java.time.Instant now) {
        String head = outcome.status() == Status.SAVED
                ? "✅ **Decided: " + label(outcome.decision()) + "** · by <@" + actor + "> <t:" + now.getEpochSecond() + ":R>\n-# " + note(outcome.decision())
                : "☑️ **Already answered** · this question was decided elsewhere; nothing changed.";
        String details = original == null ? "" : original.lines().skip(1)
                .filter(l -> !l.startsWith("This report was") && !l.startsWith("Compare the exact") && !l.startsWith("Same giveaway adds"))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String body = head + (details.isBlank() ? "" : "\n\n" + details);
        return body.length() <= 2000 ? body : body.substring(0, 1999) + "…";
    }

    static String label(String decision) {
        return switch (decision) {
            case "same" -> "Same giveaway";
            case "separate" -> "Separate giveaway";
            case "ignore" -> "Ignored this source offer";
            default -> decision;
        };
    }

    private static String note(String decision) {
        return switch (decision) {
            case "same" -> "Nothing was sent. The new source counts as evidence for the existing giveaway after its next live check.";
            case "separate" -> "Nothing was sent yet. It gets its own review on the next source check.";
            case "ignore" -> "Nothing was sent. This source offer is suppressed permanently.";
            default -> "";
        };
    }

    private static final java.util.regex.Pattern QUESTION =
            java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(FreebieMessages.BUTTON_PREFIX) + ":source-\\w+-([0-9a-f]{12}):(.+)");

    /** The (offer ID, token) of a decision button's custom ID, or empty for any other button. */
    static Optional<String[]> question(String customId) {
        if (customId == null) return Optional.empty();
        var m = QUESTION.matcher(customId);
        return m.matches() ? Optional.of(new String[] { m.group(2), m.group(1) }) : Optional.empty();
    }
    static FreebieDeliveryAdmin.View view(FreebieSourceStore store) {
        Document parent = store.conflict().orElse(null);
        if (parent == null) return new FreebieDeliveryAdmin.View("No unresolved source matches.", List.of());
        Document candidates = parent.get("sourceCandidates", Document.class);
        String token = candidates.keySet().stream().sorted().findFirst().orElseThrow();
        long open = store.openQuestions();
        var view = view(parent, token);
        return open > 1 ? new FreebieDeliveryAdmin.View(view.text() + "\n" + (open - 1) + " more waiting; run this again after deciding.", view.rows()) : view;
    }

    /** One held report with its three decisions; also posted once to the review channel when the report is held. */
    static FreebieDeliveryAdmin.View view(Document parent, String token) {
        Document candidate = parent.get("sourceCandidates", Document.class).get(token, Document.class);
        String prefix = FreebieMessages.BUTTON_PREFIX + ":source-";
        String suffix = "-" + token + ":" + parent.getString("_id");
        String text = "**Possible duplicate — owner decision required**\nExisting offer: `" + parent.getString("_id")
                + "` (" + parent.getString("state") + ").\nNew source: " + candidate.getString("source")
                + " · " + FreebieMessages.text(candidate.getString("region"), 100)
                + "\n**" + FreebieMessages.text(candidate.getString("title"), 256) + "**\n[New source page]("
                + candidate.getString("pageUrl") + ")"
                + (parent.getString("pageUrl") == null ? "\nHistorical metadata was purged; identity cannot be checked from the old card."
                    : "\n[Existing source page](" + parent.getString("pageUrl") + ")")
                + "\nThis report was **held**, not merged: it opened no review and sends nothing until you decide."
                + "\nCompare the exact product, edition, platform and promotion window. A matching title is insufficient."
                + "\nSame giveaway adds source evidence only after its next live check. Separate queues another mandatory review."
                + " Ignore permanently suppresses this source identity. None of these actions approves a game or enables checkout.";
        return new FreebieDeliveryAdmin.View(text, List.of(ActionRow.of(
                Button.primary(prefix + "same" + suffix, "Same giveaway"),
                Button.secondary(prefix + "separate" + suffix, "Separate giveaway"),
                Button.danger(prefix + "ignore" + suffix, "Ignore this source offer"))));
    }
}
