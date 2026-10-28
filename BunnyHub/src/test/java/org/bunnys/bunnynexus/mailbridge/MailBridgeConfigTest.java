package org.bunnys.bunnynexus.mailbridge;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MailBridgeConfigTest {
    private static Optional<MailBridgeConfig> load(Map<String, String> env, List<String> problems) {
        return MailBridgeConfig.load(env::get, problems);
    }

    private static Map<String, String> credentials() {
        return new HashMap<>(Map.of("GMAIL_CLIENT_ID", "id.apps.googleusercontent.com", "GMAIL_CLIENT_SECRET", "s3cret",
                "GMAIL_REFRESH_TOKEN", "1//refresh-token"));
    }

    @Test void staysOffWithoutCredentialsAndSaysWhich() {
        List<String> problems = new ArrayList<>();
        assertTrue(load(Map.of("GMAIL_CLIENT_ID", "x"), problems).isEmpty());
        assertEquals(List.of("GMAIL_CLIENT_SECRET is missing", "GMAIL_REFRESH_TOKEN is missing"), problems);
    }

    @Test void explicitlyDisabled() {
        var env = credentials();
        env.put("MAIL_BRIDGE_ENABLED", "false");
        List<String> problems = new ArrayList<>();
        assertTrue(load(env, problems).isEmpty());
        assertEquals(List.of("MAIL_BRIDGE_ENABLED=false"), problems);
    }

    @Test void defaultsPostToTheRequestedChannel() {
        var config = load(credentials(), new ArrayList<>()).orElseThrow();
        assertEquals("1043515676960247828", config.guildId());
        assertEquals("1562067773033807912", config.channelId());
        assertEquals("333644367539470337", config.mentionUserId());
        assertTrue(config.diagnosticChannelId().isEmpty());
        assertEquals(5, config.pollInterval().toMinutes());
        assertEquals(Set.of("openai.com", "chatgpt.com"), config.senders());
        assertEquals(3, config.rules().size());
        assertFalse(config.rules().containsKey(MailCategory.UNKNOWN));
    }

    @Test void invalidValuesAreReportedWithoutStarting() {
        for (var bad : List.of(Map.entry("MAIL_BRIDGE_CHANNEL_ID", "general"), Map.entry("MAIL_BRIDGE_POLL_MINUTES", "0"),
                Map.entry("MAIL_BRIDGE_MAX_ATTEMPTS", "lots"), Map.entry("MAIL_BRIDGE_PATTERNS_EEE_TRAINING", "/([/"),
                Map.entry("MAIL_BRIDGE_PATTERNS_RTL_VERIFICATION", " ; ; "), Map.entry("MAIL_BRIDGE_SENDERS", " , "))) {
            var env = credentials();
            env.put(bad.getKey(), bad.getValue());
            List<String> problems = new ArrayList<>();
            assertTrue(load(env, problems).isEmpty(), bad.getKey());
            assertEquals(1, problems.size(), bad.getKey());
            assertFalse(problems.getFirst().contains(bad.getValue()) && bad.getValue().length() > 3, "values aren't echoed");
        }
    }

    @Test void customPatternsAndOverrides() {
        var env = credentials();
        env.put("MAIL_BRIDGE_PATTERNS_POWER_ELECTRONICS", "Power Electronics; /\\bPE\\b/");
        env.put("MAIL_BRIDGE_DIAGNOSTIC_CHANNEL_ID", "1562067773033807999");
        env.put("MAIL_BRIDGE_SENDERS", "NoReply@TM.OpenAI.com");
        var config = load(env, new ArrayList<>()).orElseThrow();
        assertEquals(2, config.rules().get(MailCategory.POWER_ELECTRONICS).size());
        assertEquals(Optional.of("1562067773033807999"), config.diagnosticChannelId());
        assertEquals(Set.of("noreply@tm.openai.com"), config.senders());
    }

    @Test void secretsNeverAppearInToString() {
        String text = load(credentials(), new ArrayList<>()).orElseThrow().toString();
        assertFalse(text.contains("s3cret") || text.contains("refresh-token") || text.contains("googleusercontent"), text);
    }
}
