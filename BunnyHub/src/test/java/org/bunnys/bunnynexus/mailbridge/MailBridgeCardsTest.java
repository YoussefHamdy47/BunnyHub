package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.bunnys.bunnynexus.mailbridge.MailFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MailBridgeCardsTest {
    private final Instant at = Instant.parse("2026-10-20T08:00:00Z");
    private static final String ME = "333644367539470337";

    private static String all(MessageEmbed embed) {
        StringBuilder text = new StringBuilder(String.valueOf(embed.getTitle())).append(embed.getDescription());
        embed.getFields().forEach(f -> text.append(f.getName()).append(f.getValue()));
        return text.toString();
    }

    @Test void notificationHasEverythingAskedFor() {
        MailMessage mail = MailParser.parse(task("18f2a", "Power Electronics Career Intelligence", at));
        var data = MailBridgeCards.notification(mail, MailCategory.POWER_ELECTRONICS, ME);
        assertTrue(data.getContent().startsWith("<@" + ME + "> New results from **Power Electronics Career Intelligence**"));
        MessageEmbed embed = data.getEmbeds().getFirst();
        assertEquals("Power Electronics Career Intelligence", embed.getTitle());
        assertEquals(MailCategory.POWER_ELECTRONICS.colour(), embed.getColorRaw());
        assertTrue(all(embed).contains("<t:" + at.getEpochSecond() + ":F>"), "completion time");
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("Email subject")));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("From the email") && f.getValue().contains("three new openings")));
        assertTrue(all(embed).contains("[Open the report in ChatGPT](https://chatgpt.com/c/abc123)"));
        assertEquals("Gmail ref 18f2a", embed.getFooter().getText());
        assertEquals(at, embed.getTimestamp().toInstant());
        assertTrue(embed.isSendable());
    }

    @Test void saysWhenTheReportMustBeOpenedManually() {
        MailMessage mail = MailParser.parse(message("m1", SENDER, "EEE Industrial Training Hunter", at, "Done.", null));
        MessageEmbed embed = MailBridgeCards.notification(mail, MailCategory.EEE_TRAINING, ME).getEmbeds().getFirst();
        assertTrue(all(embed).contains("no direct link"), all(embed));
        assertTrue(embed.getFields().stream().noneMatch(f -> f.getName().equals("From the email")), "nothing worth summarising");
    }

    @Test void onlyTheConfiguredUserCanBePinged() {
        MailMessage hostile = MailParser.parse(message("h1", SENDER, "@everyone <@111111111111111111> EEE Industrial Training Hunter", at,
                "@here please <@&222222222222222222> <#333333333333333333> </ban:1> [click](https://evil.example) www.evil.example", null));
        var data = MailBridgeCards.notification(hostile, MailCategory.EEE_TRAINING, ME);
        assertEquals(EnumSet.of(MentionType.USER), data.getAllowedMentions());
        assertEquals(Set.of(ME), data.getMentionedUsers());
        assertTrue(data.getMentionedRoles().isEmpty());
        String text = all(data.getEmbeds().getFirst());
        for (String bad : new String[]{"@everyone", "@here", "<@111", "<@&", "<#", "</ban", "https://evil", "www.evil", "[click]("})
            assertFalse(text.contains(bad), bad + " survived: " + text);
    }

    @Test void longEmailsStayWithinDiscordLimits() {
        String huge = "word ".repeat(20_000);
        MailMessage mail = MailParser.parse(message("l1", SENDER, "RTL / Digital Verification Career Tracker " + huge, at, huge, null));
        var embed = MailBridgeCards.notification(mail, MailCategory.RTL_VERIFICATION, ME).getEmbeds().getFirst();
        assertTrue(embed.isSendable());
        embed.getFields().forEach(f -> assertTrue(f.getValue().length() <= MessageEmbed.VALUE_MAX_LENGTH));
        assertTrue(embed.getFields().stream().filter(f -> f.getName().equals("From the email"))
                .allMatch(f -> f.getValue().length() <= MailBridgeCards.SUMMARY_LIMIT));
    }

    @Test void unclassifiedNoticesCarryNoEmailContentAndPingNobody() {
        MailMessage mail = MailParser.parse(message("u1", SENDER, "Private subject words", at, "Private body words", null));
        var data = MailBridgeCards.unclassified(mail, "no task pattern matched");
        String text = all(data.getEmbeds().getFirst());
        assertFalse(text.contains("Private"), text);
        assertTrue(text.contains("tm.openai.com"));
        assertTrue(data.getAllowedMentions().isEmpty());
        assertTrue(data.getContent().isEmpty());
    }

    @Test void authAndTestNoticesPingOnlyTheUser() {
        for (var data : new net.dv8tion.jda.api.utils.messages.MessageCreateData[]{
                MailBridgeCards.authProblem(ME, "Google rejected the stored credentials (invalid_grant)", at), MailBridgeCards.test(ME, at)}) {
            assertEquals(EnumSet.of(MentionType.USER), data.getAllowedMentions());
            assertEquals(Set.of(ME), data.getMentionedUsers());
            assertTrue(data.getEmbeds().getFirst().isSendable());
        }
    }
}
