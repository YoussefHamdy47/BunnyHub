package org.bunnys.bunnynexus.ratings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RatingsTest {
    static byte[] fixture(String name) throws Exception {
        try (var in = RatingsTest.class.getResourceAsStream("/ratings/" + name)) { return in.readAllBytes(); }
    }

    @AfterEach void noStars() { Stars.use(Map.of()); }

    @Test void starsRoundToTheNearestQuarter() {
        assertArrayEquals(new int[]{4, 4, 4, 4, 1}, Stars.quarters(4.25));
        assertArrayEquals(new int[]{4, 4, 4, 2, 0}, Stars.quarters(3.6), "3.6 is 3.5 stars");
        assertArrayEquals(new int[]{4, 4, 4, 4, 3}, Stars.quarters(Stars.fromPercent(96)), "96% is 4.8, so 4.75");
        assertArrayEquals(new int[]{4, 4, 4, 4, 4}, Stars.quarters(9));
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, Stars.quarters(-1));
        assertEquals(20, Arrays.stream(Stars.quarters(5)).sum());
    }

    @Test void starEmojisOnlyOnceAllFiveExist() {
        assertEquals("", Stars.of(4), "no emojis yet: callers show the numbers alone");
        Stars.use(Map.of("star_full", "<:star_full:1>"));
        assertEquals("", Stars.of(4), "an incomplete set is never used");
        Stars.use(Stars.fake());
        String four = Stars.of(4.25);
        assertEquals(4, four.split("star_full", -1).length - 1);
        assertTrue(four.endsWith("<:star_quarter:100000000000000001>"));
        assertTrue(four.indexOf("star_quarter") > four.lastIndexOf("star_full"), "filled stars first");
    }

    @Test void reviewCountsAreShort() {
        assertEquals(List.of("950", "1K", "1.2K", "12K", "120K", "1.3M"),
                List.of(950L, 1000L, 1250L, 12_900L, 120_400L, 1_340_000L).stream().map(Stars::count).toList());
    }

    @Test void titlesAndCompaniesCompareLoosely() {
        assertEquals(SteamRatings.normalize("Bread and Fred"), SteamRatings.normalize("Bread & Fred"));
        assertEquals("firestone idle clicker online rpg", SteamRatings.normalize("Firestone – Idle Clicker Online RPG"));
        assertEquals(SteamRatings.normalize("Thief™ Gold"), SteamRatings.normalize("Thief Gold"));
        assertTrue(SteamRatings.sameCompany("AM Holyday Studios Ltd", "Holyday Studios"));
        assertTrue(SteamRatings.sameCompany("Atari", "Atari, Inc."));
        assertFalse(SteamRatings.sameCompany("Atari", "Infogrames"));
        assertFalse(SteamRatings.sameCompany("EA", "EA"), "too short to trust");
    }

    @Test void aGameIsMatchedByExactTitleAndPublisher() throws Exception {
        var ids = SteamRatings.exactMatches(fixture("search-bread-and-fred.json"), "Bread and Fred");
        assertEquals(List.of(1607680L), ids, "the soundtrack and demo are different titles");
        var items = fixture("items-bread-and-fred.json");
        var found = assertInstanceOf(SteamRatings.Found.class, SteamRatings.pick(items, Optional.of("Atari")));
        assertEquals(1607680L, found.rating().appId());
        assertTrue(found.rating().reviews() >= GameRating.MIN_REVIEWS);
        assertFalse(found.rating().label().isBlank());
        assertInstanceOf(SteamRatings.Found.class, SteamRatings.pick(items, Optional.empty()), "one game with that title");
        assertInstanceOf(SteamRatings.NoMatch.class, SteamRatings.pick(items, Optional.of("Some Other Publisher")),
                "same title, different publisher: no rating rather than a wrong one");
        assertInstanceOf(SteamRatings.Found.class, SteamRatings.pickApp(items, 1607680L));
        assertTrue(SteamRatings.exactMatches(fixture("search-bread-and-fred.json"), "Bread").isEmpty());
    }

    @Test void aRatingLineHasStarsLabelAndCount() {
        var rating = new GameRating(1, 92, 12_400, "Very Positive");
        assertEquals("**Very Positive** · 92% of 12K Steam reviews", rating.line());
        Stars.use(Stars.fake());
        assertTrue(rating.line().startsWith("<:star_full:"));
        assertEquals(Optional.of(rating), GameRating.read(rating.toDocument(java.time.Instant.EPOCH)));
        assertEquals(Optional.empty(), GameRating.read(new GameRating(1, 100, 3, "Positive").toDocument(java.time.Instant.EPOCH)),
                "a handful of reviews says nothing");
    }
}
