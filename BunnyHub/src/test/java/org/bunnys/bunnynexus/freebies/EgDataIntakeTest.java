package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** EGData's captured free-games list (2026-10-24, unmodified) plus explicitly synthetic edge cases. */
class EgDataIntakeTest {
    static final Instant NOW = Instant.parse("2026-10-24T16:03:44Z");

    static byte[] captured() throws Exception {
        try (var in = EgDataIntakeTest.class.getResourceAsStream("/egdata/free-games-US-2026-10-24.json")) { return in.readAllBytes(); }
    }
    static EgDataIntake.Result decode(byte[] bytes) { return new EgDataIntake().decode(bytes, NOW); }

    @Test void homeHasSeparatePcAndroidAndIosOffersWithTheirOwnIdentities() throws Exception {
        var result = decode(captured());
        var home = result.offers().stream().filter(o -> o.identity().namespace().equals("c7e46cce751b40b6937e878abd0fc531")).toList();
        assertEquals(3, home.size());
        var byPlatform = new EnumMap<EgDataIntake.Platform, EgDataIntake.Offer>(EgDataIntake.Platform.class);
        home.forEach(o -> byPlatform.put(o.platform(), o));
        assertEquals("06190191cede42afb633fe89ef08c27e", byPlatform.get(EgDataIntake.Platform.PC).identity().offerId());
        assertEquals(Optional.of("home-19212a"), byPlatform.get(EgDataIntake.Platform.PC).slug());
        assertEquals(Optional.of("home-android-9ba07f"), byPlatform.get(EgDataIntake.Platform.ANDROID).slug());
        assertEquals(Optional.of("home-ios-69cfcf"), byPlatform.get(EgDataIntake.Platform.IOS).slug());
        assertEquals(Instant.parse("2026-10-29T15:00:00Z"), byPlatform.get(EgDataIntake.Platform.PC).endsAt());
        assertEquals(Instant.parse("2026-10-22T15:55:00Z"), byPlatform.get(EgDataIntake.Platform.ANDROID).startsAt(),
                "mobile windows are independent; never copied from PC");
        for (var o : home) assertEquals(Optional.of(List.of("SA", "TW")), o.regions().blacklist());
        assertTrue(home.stream().allMatch(o -> o.liveAt(NOW)));
    }

    @Test void conflictingDuplicateWindowsAreDroppedAndUpcomingIsNotLive() throws Exception {
        var result = decode(captured());
        assertEquals(EgDataIntake.State.PARTIAL, result.state(), "the capture reports one upcoming offer twice with different ends");
        assertTrue(result.offers().stream().noneMatch(o -> o.title().equals("Sorry We're Closed")));
        var sker = result.offers().stream().filter(o -> o.title().equals("Sker Ritual")).findFirst().orElseThrow();
        assertEquals(Optional.of(List.of("KR")), sker.regions().blacklist());
    }

    @Test void malformedEnvelopesFailClosed() {
        for (String body : List.of("{}", "[", "[] []", "{\"errors\":[]}", "null"))
            assertEquals(EgDataIntake.State.FAILED, decode(body.getBytes(StandardCharsets.UTF_8)).state(), body);
        assertEquals(EgDataIntake.State.FAILED, decode(new byte[EgDataIntake.MAX_BYTES + 1]).state());
    }

    // Synthetic mutations of one captured row.
    @Test void invalidRowsAreIgnoredNotGuessed() throws Exception {
        var rows = Document.parse("{\"r\":" + new String(captured(), StandardCharsets.UTF_8) + "}").getList("r", Document.class);
        var row = rows.getFirst();
        row.get("giveaway", Document.class).put("platform", "switch");
        var second = new Document(rows.get(1));
        second.put("countriesBlacklist", List.of("IOS"));
        var body = ("[" + row.toJson() + "," + second.toJson() + "]").getBytes(StandardCharsets.UTF_8);
        var result = decode(body);
        assertEquals(EgDataIntake.State.PARTIAL, result.state());
        assertEquals(1, result.offers().size());
        assertTrue(result.offers().getFirst().regions().malformed(), "a platform token is never a country");
    }
}
