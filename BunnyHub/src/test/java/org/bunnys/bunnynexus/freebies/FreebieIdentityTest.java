package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure matching rules and field authority. */
class FreebieIdentityTest {
    static final Instant START = Instant.parse("2026-10-22T15:00:00Z"), END = Instant.parse("2026-10-29T15:00:00Z");
    static final URI PAGE = URI.create("https://store.epicgames.com/en-US/p/home-19212a");

    static FreebieFeed.Candidate epic(Instant start, Instant end) {
        return new FreebieFeed.Candidate(FreebieFeed.Provider.EPIC, "c7e46cce751b40b6937e878abd0fc531:06190191cede42afb633fe89ef08c27e:" + start + ":" + end,
                FreebieStore.EPIC, "PC, Epic Games Store", "Home - A Unique Horror Adventure", "Official synopsis.", "", Optional.empty(),
                Optional.of(URI.create("https://cdn1.epicgames.com/offer/home.jpg")), PAGE, PAGE, Optional.of(start), Optional.of(end),
                "US (en-US); availability elsewhere may differ");
    }

    static FreebieFeed.Candidate gamerpower(String id, String title, String platforms, Optional<URI> store) {
        return new FreebieFeed.Candidate(FreebieFeed.Provider.GAMERPOWER, id, FreebieStore.fromPlatforms(platforms), platforms, title,
                "Don't miss this indie game!", "1. Click the button", Optional.of("$4.99"), Optional.empty(),
                URI.create("https://www.gamerpower.com/open/home"), URI.create("https://www.gamerpower.com/home"), Optional.empty(),
                Optional.of(Instant.parse("2026-10-29T23:59:59Z").plus(Duration.ofHours(2))), "Not specified by source", store);
    }

    @Test void subtitlesRelateButOtherGamesAndEditionsDoNot() {
        assertTrue(FreebieIdentity.titlesRelated("Home (Epic Games) Giveaway", "Home - A Unique Horror Adventure"));
        assertTrue(FreebieIdentity.titlesRelated("Agent A: A puzzle in disguise", "Agent A"));
        assertFalse(FreebieIdentity.titlesRelated("Home", "Homeworld Remastered Collection"));
        assertFalse(FreebieIdentity.titlesRelated("Home", "Home Sweet Home"));
        assertTrue(FreebieIdentity.editionConflict("Home Deluxe Edition", "Home - A Unique Horror Adventure"));
        assertFalse(FreebieIdentity.editionConflict("Home (Epic Games) Giveaway", "Home - A Unique Horror Adventure"));
    }

    @Test void approximateEndsMatchWithinToleranceButRepeatGiveawaysDoNot() {
        var saved = SourceDoc.of(epic(START, END));
        Instant now = START.plus(Duration.ofMinutes(40));
        assertTrue(FreebieIdentity.sameOccurrence(saved, gamerpower("3823", "Home", "PC, Epic Games Store", Optional.empty()), now),
                "GamerPower's end-of-day date 9 h after Epic's exact end is the same occurrence");
        var repeat = epic(END.plus(Duration.ofDays(90)), END.plus(Duration.ofDays(97)));
        assertFalse(FreebieIdentity.sameOccurrence(saved, repeat, now), "same catalog offer, later window: another occurrence");
    }

    @Test void productIdentityComesFromOfficialLinksOnly() {
        assertEquals(Optional.of("home-19212a"), epic(START, END).productSlug());
        assertTrue(gamerpower("3823", "Home", "PC, Epic Games Store", Optional.empty()).productSlug().isEmpty(),
                "a GamerPower redirect link is not a storefront identity until resolved");
        assertEquals(Optional.of("home-19212a"), gamerpower("3823", "Home", "PC, Epic Games Store",
                Optional.of(URI.create("https://store.epicgames.com/p/home-19212a?lang=en-US"))).productSlug());
        assertEquals(new EpicLinks.Offer("c7e46cce751b40b6937e878abd0fc531", "06190191cede42afb633fe89ef08c27e"), epic(START, END).epicOffer().orElseThrow());
    }

    @Test void officialFieldsReplacePendingTextButOnlyShortenApprovedOffers() {
        var gp = SourceDoc.of(gamerpower("3823", "Home (Epic Games) Giveaway", "PC, Android, iOS, Epic Games Store", Optional.empty()));
        String pending = FreebieFields.changes(gp, epic(START, END), END).toString();
        assertTrue(pending.contains("Home - A Unique Horror Adventure") && pending.contains("reviewRefresh") && pending.contains("groupKey"));
        gp.put("state", "APPROVED");
        String approved = FreebieFields.changes(gp, epic(START, END), END).toString();
        assertTrue(approved.contains("cardRevision") && approved.contains("endsAt"), "official end is earlier than GamerPower's estimate");
        assertTrue(approved.contains("officialTextDiffers"));
        assertFalse(approved.contains("fieldName='title'") || approved.contains("fieldName='description'"), "approved text is kept as reviewed");
        gp.put("endsAt", Date.from(END.minus(Duration.ofDays(1))));
        String later = FreebieFields.changes(gp, epic(START, END), END).toString();
        assertTrue(later.contains("officialLaterEnd"));
        assertFalse(later.contains("fieldName='endsAt'"), "a longer window is never applied silently");
        gp.put("state", "STOPPED");
        assertFalse(FreebieFields.changes(gp, epic(START, END), END).toString().contains("fieldName='title'"), "finished offers get provenance only");
    }

    @Test void secondaryReportsOnlyFillEmptyFieldsBeforeReview() {
        var official = SourceDoc.of(epic(START, END));
        var gp = gamerpower("3823", "Home (Epic Games) Giveaway", "PC, Android, iOS, Epic Games Store", Optional.empty());
        String changes = FreebieFields.changes(official, gp, START).toString();
        assertTrue(changes.contains("$4.99") && changes.contains("reported.gamerpower"));
        assertFalse(changes.contains("Home (Epic Games) Giveaway"), "GamerPower never overwrites the official title");
        official.put("state", "APPROVED");
        assertFalse(FreebieFields.changes(official, gp, START).toString().contains("$4.99"));
    }

    @Test void reObservingTheSameOfficialOfferChangesNothing() {
        var c = epic(START, END);
        assertTrue(FreebieFields.changes(SourceDoc.of(c), c, END).isEmpty());
    }

    /** A saved offer document exactly as discovery writes it. */
    static final class SourceDoc {
        static Document of(FreebieFeed.Candidate c) { return FreebieSourceStore.fresh(c, START); }
    }
}
