package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieUnitTest {
    static FreebieOffer offer(String title, FreebieStore store) {
        return new FreebieOffer("gamerpower:1", "1", store, "PC, Epic Games Store", title, "A **great** game @everyone",
                "1. Click", Optional.of("$19.99"), Optional.empty(), URI.create("https://www.gamerpower.com/open/x"),
                URI.create("https://www.gamerpower.com/x"), Optional.of(Instant.parse("2026-10-01T00:00:00Z")),
                Instant.parse("2026-09-25T00:00:00Z"), FreebieOffer.State.APPROVED, Optional.empty(), Optional.empty(), true, false, 1);
    }

    @Test void mapsGamerPowerPlatformsToLaunchers() {
        assertEquals(FreebieStore.EPIC, FreebieStore.fromPlatforms("PC, Android, iOS, Epic Games Store"));
        assertEquals(FreebieStore.STEAM, FreebieStore.fromPlatforms("PC, Steam"));
        assertEquals(FreebieStore.ITCHIO, FreebieStore.fromPlatforms("PC, Itch.io, DRM-Free"));
        assertEquals(FreebieStore.UBISOFT, FreebieStore.fromPlatforms("PC, Ubisoft Connect"));
        assertEquals(FreebieStore.DRM_FREE, FreebieStore.fromPlatforms("PC, DRM-Free"));
        assertEquals(FreebieStore.OTHER, FreebieStore.fromPlatforms("VR"));
        assertEquals(FreebieStore.OTHER, FreebieStore.fromPlatforms(null));
        // Persisted IDs must stay unique and round-trip; slash choices are capped at 25.
        assertEquals(FreebieStore.values().length, Arrays.stream(FreebieStore.values()).map(FreebieStore::id).distinct().count());
        for (var store : FreebieStore.values()) assertEquals(store, FreebieStore.byId(store.id()).orElseThrow());
        assertTrue(FreebieStore.values().length <= 25);
    }

    @Test void classifiesDiscordFailuresForRetryOrOwnerNotice() {
        assertEquals(FreebieFailure.TRANSIENT, FreebieFailure.classify(new TimeoutException()));
        assertEquals(FreebieFailure.TRANSIENT, FreebieFailure.classify(new CompletionException(new SocketTimeoutException())));
        assertEquals(FreebieFailure.TRANSIENT, FreebieFailure.classify(new IOException("reset")));
        assertEquals(FreebieFailure.MISSING_PERMISSIONS, FreebieFailure.classify(mock(InsufficientPermissionException.class)));
        assertEquals(FreebieFailure.MISSING_PERMISSIONS, FreebieFailure.classify(discord(ErrorResponse.MISSING_PERMISSIONS, false)));
        assertEquals(FreebieFailure.MISSING_PERMISSIONS, FreebieFailure.classify(discord(ErrorResponse.MISSING_ACCESS, false)));
        assertEquals(FreebieFailure.CHANNEL_MISSING, FreebieFailure.classify(discord(ErrorResponse.UNKNOWN_CHANNEL, false)));
        assertEquals(FreebieFailure.BOT_REMOVED, FreebieFailure.classify(discord(ErrorResponse.UNKNOWN_GUILD, false)));
        assertEquals(FreebieFailure.TRANSIENT, FreebieFailure.classify(discord(ErrorResponse.SERVER_ERROR, true)));
        assertTrue(FreebieFailure.TRANSIENT.retryable());
        assertFalse(FreebieFailure.MISSING_PERMISSIONS.retryable());
        assertTrue(FreebieFailure.MISSING_PERMISSIONS.notifyServer());
        assertFalse(FreebieFailure.BOT_REMOVED.notifyServer());
    }
    private static ErrorResponseException discord(ErrorResponse code, boolean server) {
        var error = mock(ErrorResponseException.class);
        when(error.getErrorResponse()).thenReturn(code);
        when(error.getErrorCode()).thenReturn(code.getCode());
        when(error.isServerError()).thenReturn(server);
        return error;
    }

    @Test void retriesFiveTimesWithGrowingBackoff() {
        assertEquals(Duration.ofSeconds(30), FreebieFailure.backoff(1));
        assertEquals(Duration.ofMinutes(2), FreebieFailure.backoff(2));
        assertEquals(Duration.ofMinutes(10), FreebieFailure.backoff(3));
        assertEquals(Duration.ofMinutes(30), FreebieFailure.backoff(4));
        assertNull(FreebieFailure.backoff(FreebieFailure.MAX_ATTEMPTS));
        // The first retry must land inside Discord's nonce de-duplication window.
        assertTrue(FreebieFailure.backoff(1).compareTo(Duration.ofMinutes(2)) < 0);
    }

    @Test void configComesOnlyFromEnvironmentAndFailsClosed() {
        List<String> problems = new ArrayList<>();
        assertTrue(FreebieConfig.load(Map.<String, String>of()::get, problems).isEmpty());
        assertEquals(2, problems.size());

        problems.clear();
        var env = Map.of("FREEBIE_REVIEW_CHANNEL_ID", "1187559385359200316", "FREEBIE_OWNER_IDS", "333644367539470337, 123456789012345678");
        var config = FreebieConfig.load(env::get, problems).orElseThrow();
        assertTrue(config.isOwner("333644367539470337"));
        assertTrue(config.isOwner("123456789012345678"));
        assertFalse(config.isOwner("999999999999999999"));
        assertEquals(10, config.pollMinutes());

        problems.clear();
        assertTrue(FreebieConfig.load(Map.of("FREEBIE_REVIEW_CHANNEL_ID", "general", "FREEBIE_OWNER_IDS", "333644367539470337")::get, problems).isEmpty());
        problems.clear();
        var disabled = new HashMap<>(env); disabled.put("FREEBIE_ENABLED", "false");
        assertTrue(FreebieConfig.load(disabled::get, problems).isEmpty());
        problems.clear();
        var tooFast = new HashMap<>(env); tooFast.put("FREEBIE_POLL_MINUTES", "1");
        assertTrue(FreebieConfig.load(tooFast::get, problems).isEmpty());
    }

    @Test void nonceIsStablePerChannelAndWithinDiscordLimit() {
        String a = FreebieMessages.nonce("gamerpower:1|111"), b = FreebieMessages.nonce("gamerpower:1|222");
        assertEquals(a, FreebieMessages.nonce("gamerpower:1|111"));
        assertNotEquals(a, b);
        assertEquals(25, a.length());
        assertTrue(a.startsWith(FreebieMessages.reference("gamerpower:1|111")));
    }

    @Test void alertPingsOnlyTheConfiguredRole() {
        try (var withRole = FreebieMessages.alert(offer("Mechabellum (Epic Games) Giveaway", FreebieStore.EPIC), Optional.of("555555555555555555"), "abc");
             var noRole = FreebieMessages.alert(offer("X", FreebieStore.EPIC), Optional.empty(), "abc")) {
            assertEquals("<@&555555555555555555>", withRole.getContent());
            assertEquals(Set.of("555555555555555555"), withRole.getMentionedRoles());
            assertTrue(withRole.getMentionedUsers().isEmpty());
            assertFalse(withRole.getAllowedMentions().contains(Message.MentionType.EVERYONE));
            assertFalse(withRole.getAllowedMentions().contains(Message.MentionType.HERE));
            assertTrue(noRole.getContent().isEmpty());
            assertTrue(noRole.getMentionedRoles().isEmpty());
            var embed = withRole.getEmbeds().getFirst();
            assertEquals("Mechabellum (Epic Games)", embed.getTitle());
            assertTrue(embed.getDescription().contains("\\*\\*great\\*\\*"), embed.getDescription());
            assertEquals("Source: GamerPower • ref abc", embed.getFooter().getText());
        }
    }

    @Test void reviewAndCatchUpControlsFitDiscordLimits() {
        var rows = FreebieMessages.reviewControls(offer("X", FreebieStore.STEAM));
        assertEquals(2, rows.size());
        var menu = rows.get(1).getComponents().getFirst();
        assertInstanceOf(net.dv8tion.jda.api.components.selections.StringSelectMenu.class, menu);
        var select = (net.dv8tion.jda.api.components.selections.StringSelectMenu) menu;
        assertEquals(FreebieStore.values().length, select.getOptions().size());
        assertEquals(List.of("steam"), select.getOptions().stream().filter(o -> o.isDefault()).map(o -> o.getValue()).toList());
        var catchUp = FreebieMessages.catchUpControls("12345678901234567890", "12345678901234567890", FreebieStore.PLAYSTATION, 3);
        var button = (net.dv8tion.jda.api.components.buttons.Button) catchUp.getFirst().getComponents().getFirst();
        assertTrue(button.getCustomId().length() <= 100);
        assertEquals(4, button.getCustomId().split(":").length);
    }

    @Test void textIsBoundedWithoutBrokenEscapes() {
        String long_ = "*".repeat(5000);
        String cut = FreebieMessages.text(long_, 100);
        assertTrue(cut.length() <= 100);
        assertTrue(cut.endsWith("…"));
        assertFalse(cut.substring(0, cut.length() - 1).endsWith("\\"));
        assertEquals("—", FreebieMessages.text("  ", 10));
    }

    @Test void gamerPowerEndDateNeverCutsOffEarly() throws Exception {
        byte[] body = Files.readAllBytes(Path.of("src/test/resources/gamerpower/live-games-2026-09-21.json"));
        var batch = new GamerPowerIntake().decode(200, body, Instant.parse("2026-09-21T00:00:00Z"));
        var first = batch.candidates().stream().filter(c -> c.sourceItemId().equals("3322")).findFirst().orElseThrow();
        assertEquals(Optional.of("$29.99"), first.worth());
        assertTrue(first.image().isPresent());
        assertEquals(FreebieStore.DRM_FREE, FreebieStore.fromPlatforms(first.platforms()));
        var dated = batch.candidates().stream().filter(c -> c.endsLocal().isPresent()).findFirst().orElseThrow();
        Instant end = FreebieOffer.endInstant(dated).orElseThrow();
        assertTrue(end.isAfter(dated.endsLocal().get().toInstant(ZoneOffset.UTC)));
    }
}
