package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** "Free next week": read from the Epic response the bot already polls, then a weekly card and a line in the list. */
class FreebieUpcomingTest {
    static byte[] captured() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/epic/captured-2026-10-13-US.json"));
    }

    @Test void nextWeeksFreeGamesComeFromTheFeedSoonestFirst() throws Exception {
        var games = EpicIntake.upcoming(captured(), Instant.parse("2026-10-13T12:00:00Z"));
        assertEquals(List.of("Agent A: A puzzle in disguise", "Bad Cheese").stream().sorted().toList(),
                games.stream().map(FreebieUpcoming::title).sorted().toList());
        assertTrue(games.stream().allMatch(g -> g.start().equals(Instant.parse("2026-10-15T15:00:00Z")) && g.end().isAfter(g.start())));
        assertTrue(games.stream().allMatch(g -> g.page().toString().startsWith("https://store.epicgames.com/en-US/p/")));
        assertEquals("2026-10-15T15:00:00Z", FreebieUpcoming.key(games));
    }

    @Test void onceThePromotionStartsItIsNoLongerUpcoming() throws Exception {
        assertTrue(EpicIntake.upcoming(captured(), Instant.parse("2026-10-16T00:00:00Z")).isEmpty());
        assertTrue(EpicIntake.upcoming("{\"nope\":1}".getBytes(), Instant.EPOCH).isEmpty(), "an unusable body gives no games");
        assertTrue(EpicIntake.upcoming(null, Instant.EPOCH).isEmpty(), "no poll yet");
    }

    static List<FreebieUpcoming> games() {
        return List.of(new FreebieUpcoming("Bad Cheese", URI.create("https://store.epicgames.com/en-US/p/bad-cheese-4256d9"),
                Optional.of(URI.create("https://cdn1.epicgames.com/x.jpg")), Instant.parse("2026-10-15T15:00:00Z"),
                Instant.parse("2026-10-22T15:00:00Z")));
    }

    @Test void theOwnersCardPreparesTheAnnouncementAndNeverHoldsClearFinished() {
        var card = FreebieUpcoming.reviewCard(games());
        String json = card.toData().toString();
        assertTrue(json.contains("Free next week on Epic") && json.contains("Bad Cheese") && json.contains("<t:1792076400:R>"));
        var ids = MessageComponentTree.of(card.getComponents()).findAll(Button.class).stream().map(Button::getCustomId).toList();
        assertEquals(List.of("freebie_review:upcoming:next", FreebieMessages.clearButton().getCustomId()), ids);
        // FreebieSystem.sourceQuestionOpen answers false for this button, so Clear finished may remove the card.
        assertFalse(FreebieMessages.needsOwner(card.getComponents(), id -> !id.startsWith("freebie_review:upcoming:")),
                "the weekly card never holds Clear finished back");
        assertTrue(FreebieUpcoming.body(games()).length() <= FreebieBroadcasts.MAX_BODY);
    }

    @Test void theListShowsNextWeekForEveryLauncherOrEpicOnly() {
        var all = FreebieMessages.liveList(List.of(), Optional.empty(), games()).toData().toString();
        assertTrue(all.contains("Free next week") && all.contains("Bad Cheese"));
        var steam = FreebieMessages.liveList(List.of(), Optional.of(FreebieStore.STEAM), games()).toData().toString();
        assertFalse(steam.contains("Free next week"), "other launchers do not show Epic's list");
        var full = FreebieMessages.liveList(List.of(FreebieUnitTest.offer("A", FreebieStore.EPIC)), Optional.of(FreebieStore.EPIC), games());
        assertTrue(full.toData().toString().contains("Free next week"));
    }
}
