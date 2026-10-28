package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

/** Private owner resolution. Similar text is a question, never proof of catalog identity. */
final class FreebieSourceDecisions {
    private FreebieSourceDecisions() {}
    static String resolve(FreebieSourceStore store, String actor, String offerId, String action, java.time.Instant now) {
        String[] parts = action.split("-", 3);
        if (parts.length != 3 || !parts[2].matches("[0-9a-f]{12}")) return "Invalid source decision.";
        return store.resolve(offerId, parts[2], parts[1], actor, now)
                ? "Source decision saved. It does not approve a game or change an existing approval. Separate games appear on their next source check."
                : "This source decision was already handled. Nothing changed.";
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
