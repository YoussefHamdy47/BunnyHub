package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.bunnys.bunnynexus.mailbridge.MailFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MailParserTest {
    private final Instant at = Instant.parse("2026-10-20T08:00:00Z");

    @Test void readsHeadersPlainTextAndLinks() {
        MailMessage mail = MailParser.parse(task("18f2a", "EEE Industrial Training Hunter", at));
        assertEquals("18f2a", mail.id());
        assertEquals(at, mail.received());
        assertEquals("noreply@tm.openai.com", mail.fromAddress());
        assertEquals("ChatGPT", mail.fromName());
        assertEquals("tm.openai.com", mail.senderDomain());
        assertTrue(mail.subject().contains("EEE Industrial Training Hunter"));
        assertTrue(mail.text().contains("three new openings"));
        assertEquals("https://chatgpt.com/c/abc123", mail.reportLink().orElseThrow().toString());
        assertTrue(mail.labelIds().contains("INBOX"));
    }

    @Test void fallsBackToHtmlWithMarkupScriptsAndEntitiesRemoved() {
        String html = "<html><head><style>p{color:red}</style></head><body><script>alert(1)</script>"
                + "<p>Results&nbsp;ready &amp; waiting</p><br><a href=\"https://chatgpt.com/s/xyz?x=1&amp;y=2\">View</a>"
                + "<a href='https://click.example.com/track?u=1'>Unsubscribe</a></body></html>";
        MailMessage mail = MailParser.parse(message("h1", SENDER, "Task done", at, null, html));
        assertTrue(mail.text().contains("Results ready & waiting"), mail.text());
        assertFalse(mail.text().contains("alert(1)") || mail.text().contains("color:red") || mail.text().contains("<"), mail.text());
        assertEquals("https://chatgpt.com/s/xyz?x=1&y=2", mail.reportLink().orElseThrow().toString());
        assertEquals(2, mail.links().size());
    }

    @Test void prefersPlainTextOverHtml() {
        MailMessage mail = MailParser.parse(message("p1", SENDER, "Task done", at, "plain body", "<p>html body</p>"));
        assertEquals("plain body", mail.text());
    }

    @Test void trackingLinksAreNotReportLinks() {
        MailMessage mail = MailParser.parse(message("t1", SENDER, "Task done", at, "Open: https://email.openai.com/c/redirect?id=9 or http://chatgpt.com/c/1", null));
        assertTrue(mail.reportLink().isEmpty(), "only https links on chatgpt.com count");
        assertEquals(2, mail.links().size());
    }

    @Test void missingBodiesAreEmptyNotErrors() {
        DataObject raw = DataObject.empty().put("id", "e1").put("internalDate", String.valueOf(at.toEpochMilli()))
                .put("payload", DataObject.empty().put("mimeType", "text/plain")
                        .put("headers", DataArray.empty().add(header("From", SENDER)).add(header("Subject", "Task done")))
                        .put("body", DataObject.empty().put("size", 0)));
        MailMessage mail = MailParser.parse(raw);
        assertEquals("", mail.text());
        assertTrue(mail.links().isEmpty());
        assertTrue(mail.reportLink().isEmpty());
        assertTrue(MailParser.parse(DataObject.empty().put("id", "e2")).text().isEmpty(), "no payload at all");
    }

    @Test void attachmentsAndBrokenBase64AreSkipped() {
        DataObject attachment = part("text/plain", "SECRET ATTACHMENT");
        attachment.put("headers", DataArray.empty().add(header("Content-Disposition", "attachment; filename=a.txt")));
        DataObject broken = DataObject.empty().put("mimeType", "text/plain").put("body", DataObject.empty().put("data", "@@@not base64@@@"));
        DataObject payload = DataObject.empty().put("mimeType", "multipart/mixed")
                .put("headers", DataArray.empty().add(header("From", SENDER)).add(header("Subject", "x")))
                .put("parts", DataArray.empty().add(part("text/plain", "visible")).add(attachment).add(broken));
        MailMessage mail = MailParser.parse(DataObject.empty().put("id", "a1").put("internalDate", "0").put("payload", payload));
        assertEquals("visible", mail.text());
    }

    @Test void nestedMultipartAndEncodedHeadersAreDecoded() {
        DataObject inner = DataObject.empty().put("mimeType", "multipart/alternative")
                .put("parts", DataArray.empty().add(part("text/plain", "deep text")));
        DataObject payload = DataObject.empty().put("mimeType", "multipart/mixed")
                .put("headers", DataArray.empty().add(header("From", "=?UTF-8?B?Q2hhdEdQVA==?= <noreply@openai.com>"))
                        .add(header("Subject", "=?UTF-8?Q?Task_=E2=9C=85?= =?UTF-8?B?IGRvbmU=?=")))
                .put("parts", DataArray.empty().add(inner));
        MailMessage mail = MailParser.parse(DataObject.empty().put("id", "n1").put("internalDate", "0").put("payload", payload));
        assertEquals("deep text", mail.text());
        assertEquals("Task ✅ done", mail.subject());
        assertEquals("ChatGPT", mail.fromName());
    }

    @Test void rejectsMessagesWithoutAUsableId() {
        assertThrows(IllegalArgumentException.class, () -> MailParser.parse(DataObject.empty().put("id", "../etc")));
    }

    @Test void bareAddressesAndControlCharacters() {
        assertEquals("a@b.com", MailParser.address("a@b.com"));
        assertEquals("", MailParser.address("no address"));
        assertEquals("a b\nc", MailParser.normalise("a\u0000  b \r\n c\u200B"));
    }
}
