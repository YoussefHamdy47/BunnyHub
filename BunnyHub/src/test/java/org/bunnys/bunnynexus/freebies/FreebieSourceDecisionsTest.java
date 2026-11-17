package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Held-report questions: the answered message text, its buttons, and Clear finished. */
class FreebieSourceDecisionsTest {
    static final String TOKEN = "0123456789ab", OWNER = "223456789012345678";

    static FreebieDeliveryAdmin.View question() {
        return FreebieSourceDecisions.view(new Document("_id", "gamerpower:1").append("state", "APPROVED")
                .append("pageUrl", "https://example.com/a").append("sourceCandidates", new Document(TOKEN,
                        new Document("source", "epic").append("region", "US").append("title", "Knightica").append("pageUrl", "https://example.com/b"))), TOKEN);
    }

    @Test void clearFinishedSkipsQuestionsAnsweredElsewhere() {
        var rows = new ArrayList<MessageTopLevelComponent>(question().rows());
        assertTrue(FreebieMessages.needsOwner(rows), "an open question needs the owner");
        assertFalse(FreebieMessages.needsOwner(rows, id -> false), "answered elsewhere: finished");
        var ids = rows.stream().flatMap(r -> ((ActionRow) r).getComponents().stream()).map(c -> ((Button) c).getCustomId()).toList();
        for (String id : ids) assertArrayEquals(new String[] { "gamerpower:1", TOKEN }, FreebieSourceDecisions.question(id).orElseThrow(), id);
        var approve = List.<MessageTopLevelComponent>of(ActionRow.of(Button.primary("freebie_review:approve:gamerpower:1", "Approve")));
        assertTrue(FreebieSourceDecisions.question("freebie_review:approve:gamerpower:1").isEmpty());
        assertTrue(FreebieMessages.needsOwner(approve, id -> FreebieSourceDecisions.question(id).isEmpty()), "other review buttons are unaffected");
    }

    @Test void theAnsweredMessageShowsTheDecisionAndKeepsTheDetails() {
        String original = "**Possible duplicate held for your decision**\n" + question().text();
        var now = Instant.parse("2026-11-17T12:00:00Z");
        String saved = FreebieSourceDecisions.decided(original, new FreebieSourceDecisions.Outcome(FreebieSourceDecisions.Status.SAVED, "same", "x"), OWNER, now);
        assertTrue(saved.startsWith("✅ **Decided: Same giveaway** · by <@" + OWNER + "> <t:" + now.getEpochSecond() + ":R>"), saved);
        assertTrue(saved.contains("**Knightica**") && saved.contains("Existing offer: `gamerpower:1`"), saved);
        assertFalse(saved.contains("This report was **held**") || saved.contains("Compare the exact"), "the instructions for deciding go away");
        assertFalse(saved.contains("held for your decision"));
        String ignored = FreebieSourceDecisions.decided(original, new FreebieSourceDecisions.Outcome(FreebieSourceDecisions.Status.SAVED, "ignore", "x"), OWNER, now);
        assertTrue(ignored.startsWith("✅ **Decided: Ignored this source offer**"));
        String already = FreebieSourceDecisions.decided(original, new FreebieSourceDecisions.Outcome(FreebieSourceDecisions.Status.ALREADY, "same", "x"), OWNER, now);
        assertTrue(already.startsWith("☑️ **Already answered**"));
        assertTrue(FreebieSourceDecisions.decided("x\n" + "y".repeat(3000), new FreebieSourceDecisions.Outcome(FreebieSourceDecisions.Status.SAVED, "same", "x"),
                OWNER, now).length() <= 2000);
    }
}
