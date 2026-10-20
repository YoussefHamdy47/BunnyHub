package org.bunnys.bunnynexus.mailbridge;

import org.bunnys.bunnynexus.mailbridge.MailClassifier.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.bunnys.bunnynexus.mailbridge.MailFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MailClassifierTest {
    private final Instant at = Instant.parse("2026-10-20T08:00:00Z");
    private final MailClassifier classifier = new MailClassifier(Set.of("openai.com", "chatgpt.com"), defaultRules());

    private MailClassifier.Classification classify(String from, String subject, String body) {
        return classifier.classify(MailParser.parse(message("c1", from, subject, at, body, null)));
    }

    @Test void eachTaskIsRecognisedByItsName() {
        for (MailCategory category : List.of(MailCategory.EEE_TRAINING, MailCategory.POWER_ELECTRONICS, MailCategory.RTL_VERIFICATION)) {
            var result = classifier.classify(MailParser.parse(task("x", category.defaultPattern(), at)));
            assertEquals(Outcome.MATCHED, result.outcome(), category.name());
            assertEquals(category, result.category());
            assertTrue(result.forwardable());
        }
    }

    @Test void matchingIsCaseInsensitiveAndFallsBackToTheBody() {
        var result = classify(SENDER, "Task finished", "Results for rtl / digital verification career tracker are in.");
        assertEquals(MailCategory.RTL_VERIFICATION, result.category());
        assertEquals("matched in body", result.reason());
    }

    @Test void unrelatedEmailsAreIgnored() {
        assertEquals(Outcome.UNRELATED, classify("Bank <alerts@bank.com>", "EEE Industrial Training Hunter", "task").outcome(),
                "right words, wrong sender");
        assertEquals(Outcome.UNRELATED, classify(SENDER, "Your receipt", "Thanks for your payment.").outcome(),
                "right sender, not a task notice");
        assertEquals(Outcome.UNRELATED, classify("noreply@evilopenai.com", "Power Electronics Career Intelligence", "task").outcome(),
                "look-alike domains don't pass");
        assertEquals(Outcome.UNRELATED, classify("", "Power Electronics Career Intelligence", "").outcome());
    }

    @Test void taskEmailsMatchingNothingAreUnmatchedNotGuessed() {
        var result = classify(SENDER, "Your task has finished", "Something else entirely.");
        assertEquals(Outcome.UNMATCHED, result.outcome());
        assertEquals(MailCategory.UNKNOWN, result.category());
        assertFalse(result.forwardable());
    }

    @Test void ambiguousEmailsAreNeverForwarded() {
        var subject = classify(SENDER, "EEE Industrial Training Hunter and Power Electronics Career Intelligence", "");
        assertEquals(Outcome.AMBIGUOUS, subject.outcome());
        assertFalse(subject.forwardable());
        var body = classify(SENDER, "Task done", "EEE Industrial Training Hunter, RTL / Digital Verification Career Tracker");
        assertEquals(Outcome.AMBIGUOUS, body.outcome());
    }

    @Test void subjectWinsOverABodyMentioningAnotherTask() {
        var result = classify(SENDER, "EEE Industrial Training Hunter finished", "See also Power Electronics Career Intelligence next week.");
        assertEquals(MailCategory.EEE_TRAINING, result.category());
    }

    @Test void customRegexRulesAndExactAddresses() {
        var rules = new EnumMap<MailCategory, List<java.util.regex.Pattern>>(MailCategory.class);
        rules.put(MailCategory.EEE_TRAINING, MailBridgeConfig.patterns(MailCategory.EEE_TRAINING, "/\\bEEE\\b.*training/;industrial training"));
        rules.put(MailCategory.POWER_ELECTRONICS, MailBridgeConfig.patterns(MailCategory.POWER_ELECTRONICS, "power electronics"));
        rules.put(MailCategory.RTL_VERIFICATION, MailBridgeConfig.patterns(MailCategory.RTL_VERIFICATION, "/\\b(rtl|fpga)\\b/"));
        var custom = new MailClassifier(Set.of("noreply@tm.openai.com"), rules);
        assertEquals(MailCategory.RTL_VERIFICATION, custom.classify(MailParser.parse(message("r", SENDER, "FPGA roles this week", at, "", null))).category());
        assertEquals(MailCategory.EEE_TRAINING, custom.classify(MailParser.parse(message("e", SENDER, "EEE summer training", at, "", null))).category());
        assertFalse(custom.fromAllowedSender("other@tm.openai.com"));
        assertTrue(custom.fromAllowedSender("noreply@tm.openai.com"));
    }
}
