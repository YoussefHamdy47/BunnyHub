package org.bunnys.bunnynexus.consoles;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.handler.cards.Card;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing the real feeds (saved copies), what gets posted when, and that each channel gets a post once. */
class ConsoleNewsTest {
    static byte[] fixture(String name) throws IOException {
        try (InputStream in = ConsoleNewsTest.class.getResourceAsStream("/consoles/" + name)) { return in.readAllBytes(); }
    }

    // ---- Parsing ----------------------------------------------------------------------------------------------------

    @Test void readsTheMonthlyGamesPostFromThePlayStationBlog() throws IOException {
        var posts = PsPlusPost.parse(fixture("playstation-blog.xml"));
        assertEquals(1, posts.size(), "other blog posts are skipped");
        var post = posts.getFirst();
        assertEquals(PsPlusPost.Kind.MONTHLY, post.kind());
        assertEquals("December", post.month());
        assertEquals(List.of("Tony Hawk’s Pro Skater 3+4", "Dragon Ball Xenoverse 2", "Amnesia: The Bunker"), post.games());
        assertFalse(post.more());
        assertEquals(Optional.of(LocalDate.of(2026, 12, 1)), post.from());
        assertEquals(Optional.of(LocalDate.of(2027, 1, 4)), post.until(), "a January end date in a November post is next year");
        assertTrue(post.image().orElseThrow().startsWith("https://blog.playstation.com/"));
        assertEquals("blog.playstation.com", post.link().getHost());
        assertEquals("PlayStation Plus Monthly Games for December", post.heading());
        assertEquals(post, PsPlusPost.read(post.toDocument()).orElseThrow(), "saved and read back");
    }

    @Test void eachPsPlusGameGetsItsPlatformsPictureAndBlurb() throws IOException {
        var post = PsPlusPost.parse(fixture("playstation-blog.xml")).getFirst();
        assertEquals(3, post.details().size());
        var tony = post.details().getFirst();
        assertEquals("Tony Hawk’s Pro Skater 3+4", tony.name(), "the title's name, matched to the heading \"… – Cross-Gen Edition\"");
        assertEquals(List.of("PS5", "PS4"), tony.platforms());
        assertTrue(tony.image().orElseThrow().startsWith("https://blog.playstation.com/"));
        assertTrue(tony.blurb().orElseThrow().startsWith("Alert the neighborhood watch"), tony.blurb().toString());
        assertTrue(tony.blurb().get().length() <= PsPlusPost.MAX_BLURB + 1);
        assertEquals(List.of("PS4"), post.details().get(2).platforms(), "Amnesia: The Bunker is PS4 only");
        assertNotEquals(post.details().get(0).image(), post.details().get(1).image(), "each game has its own picture");
        assertEquals("https://store.playstation.com/en-us/search/Dragon%20Ball%20Xenoverse%202", post.details().get(1).storeSearch());

        var rated = post.withDetails(post.details().stream().map(g -> g.withRating(Optional.of(new org.bunnys.bunnynexus.ratings.GameRating(1, 92, 12000, "Very Positive")))).toList());
        assertEquals(rated, PsPlusPost.read(rated.toDocument()).orElseThrow(), "ratings are saved with the post");
        var card = ConsoleNewsCards.psPlusCard(rated, Optional.empty());
        String text = card.plainText();
        assertTrue(text.contains("PS5, PS4"));
        assertTrue(text.contains("92%"), text);
        assertTrue(text.contains("Ratings are from Steam players"));
        assertTrue(card.componentCount() <= 40 && card.textLength() <= 4000);
    }

    @Test void gamePassGamesGetGenreStoreRatingAndTags() throws IOException {
        var games = GamePass.games(fixture("gamepass-2-games.json"));
        var outlast = games.stream().filter(g -> g.title().equals("The Outlast Trials")).findFirst().orElseThrow();
        assertEquals(Optional.of("Action & adventure"), outlast.genre());
        assertEquals(4.6, outlast.stars());
        assertEquals(4698, outlast.ratings());
        assertEquals(List.of("PC too", "Series X|S optimized", "Online co-op"), outlast.tags());
        assertEquals(Optional.of(outlast), GamePass.Game.read(outlast.toDocument()));
        var sniper = games.stream().filter(g -> g.id().equals("9NV8BWGH15GN")).findFirst().orElseThrow();
        assertEquals(Optional.of("Shooter"), sniper.genre());

        var card = ConsoleNewsCards.gamePassCard(Map.of(GamePass.Shelf.ADDED, List.of(sniper, outlast)), Optional.empty()).plainText();
        assertTrue(card.indexOf("The Outlast Trials") < card.indexOf("Sniper Ghost Warrior"), "best rated first: 4.6 before 3.6");
        assertTrue(card.contains("4.6 (4.6K)"), card);
        assertTrue(card.contains("New on Game Pass** (2)"));
    }

    @Test void gameListsWithAndAndMore() {
        List<String> games = new ArrayList<>();
        assertTrue(PsPlusPost.games("Hogwarts Legacy, Persona 3 Reload, Stray and more", games));
        assertEquals(List.of("Hogwarts Legacy", "Persona 3 Reload", "Stray"), games);
        games.clear();
        assertFalse(PsPlusPost.games("Ratchet and Clank", games));
        assertEquals(List.of("Ratchet and Clank"), games, "one title with \"and\" stays one game");
        games.clear();
        PsPlusPost.games("A, B, and C", games);
        assertEquals(List.of("A", "B", "C"), games);
    }

    @Test void hostileOrBrokenFeedsReadAsNothing() {
        assertEquals(List.of(), PsPlusPost.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><rss>&e;</rss>".getBytes()));
        assertEquals(List.of(), PsPlusPost.parse("not xml".getBytes()));
        assertEquals(List.of(), GamePass.ids("{oops".getBytes()));
        assertEquals(List.of(), GamePass.games("[]".getBytes()));
    }

    @Test void readsGamePassListsAndStoreDetails() throws IOException {
        var ids = GamePass.ids(fixture("sigl-added.json"));
        assertFalse(ids.isEmpty());
        assertTrue(ids.stream().allMatch(id -> GamePass.PRODUCT.matcher(id).matches()), "the list's own heading entry is skipped");

        var games = GamePass.games(fixture("catalog-2-products.json"));
        assertEquals(1, games.size());
        var sniper = games.stream().filter(g -> g.id().equals("9NV8BWGH15GN")).findFirst().orElseThrow();
        assertEquals("Sniper Ghost Warrior Contracts 2", sniper.title());
        assertTrue(sniper.console());
        assertEquals(Optional.of(LocalDate.of(2021, 6, 4)), sniper.release());
        assertTrue(sniper.image().orElseThrow().startsWith("https://store-images.s-microsoft.com/"));
        assertEquals("https://www.xbox.com/en-US/games/store/x/9NV8BWGH15GN", sniper.link());
        assertEquals(Optional.of(sniper), GamePass.Game.read(sniper.toDocument()));
    }

    // ---- Planning and delivery --------------------------------------------------------------------------------------

    Instant now = Instant.parse("2026-11-26T18:00:00Z");
    final Map<GamePass.Shelf, List<String>> shelves = new EnumMap<>(GamePass.Shelf.class);
    final Map<String, GamePass.Game> catalog = new HashMap<>();
    List<PsPlusPost> feed = List.of();
    List<FreeWeekend> weekends = List.of();
    List<ConsoleItem> items = List.of();
    final Memory store = new Memory();
    final List<String> sent = new ArrayList<>();
    final Set<String> broken = new HashSet<>();
    final List<Subscription> subs = new ArrayList<>();
    boolean paused;

    @BeforeEach void reset() {
        for (GamePass.Shelf s : GamePass.Shelf.values()) shelves.put(s, List.of());
    }

    ConsoleNews news() {
        return new ConsoleNews(new ConsoleNews.Source() {
            @Override public Optional<List<PsPlusPost>> psPlus() { return Optional.of(feed); }
            @Override public List<String> shelf(GamePass.Shelf shelf) { return shelves.get(shelf); }
            @Override public List<GamePass.Game> games(List<String> ids) { return ids.stream().map(catalog::get).filter(Objects::nonNull).toList(); }
            @Override public Optional<List<FreeWeekend>> freeWeekends() { return Optional.of(weekends); }
            @Override public Optional<List<ConsoleItem>> items() { return Optional.of(items); }
        }, store, new ConsoleNews.Discord() {
            @Override public boolean ready() { return true; }
            @Override public Optional<String> send(String guildId, String channelId, MessageCreateData message) {
                if (broken.contains(channelId)) return Optional.of("nope");
                sent.add(channelId + " " + Card.textLength(message.getComponents().stream()
                        .map(c -> (net.dv8tion.jda.api.components.MessageTopLevelComponent) c).toList()));
                return Optional.empty();
            }
        }, store_ -> subs.stream().filter(s -> s.store() == store_).toList(), () -> paused);
    }

    PsPlusPost ps(String id, Instant published) {
        return new PsPlusPost(id, PsPlusPost.Kind.MONTHLY, "December", List.of("A", "B"), false, URI.create("https://blog.playstation.com/x"),
                published, Optional.of(LocalDate.of(2026, 12, 1)), Optional.of(LocalDate.of(2027, 1, 4)), Optional.empty());
    }

    GamePass.Game game(String id, boolean console) {
        var g = new GamePass.Game(id, "Game " + id, Optional.empty(), console, Optional.empty());
        catalog.put(id, g);
        return g;
    }

    static String id(int n) { return String.format("9N%010d", n); }

    @Test void psPlusPostsGoOutOnceAndOldOnesNever() {
        subs.add(new Subscription("g", "c1", FreebieStore.PS_PLUS, Optional.of("role")));
        subs.add(new Subscription("g", "c2", FreebieStore.PLAYSTATION, Optional.empty()));
        feed = List.of(ps("new", now.minus(Duration.ofDays(1))), ps("old", now.minus(Duration.ofDays(30))));
        news().run(now);
        assertEquals(1, sent.size(), "only the channel with PS Plus ticked, only the recent post");
        assertTrue(sent.getFirst().startsWith("c1"));
        news().run(now.plus(Duration.ofHours(3)));
        assertEquals(1, sent.size(), "never twice");

        subs.add(new Subscription("g2", "c3", FreebieStore.PS_PLUS, Optional.empty()));
        news().run(now.plus(Duration.ofHours(6)));
        assertEquals(2, sent.size(), "a channel that turns it on within three days still gets it");
        subs.add(new Subscription("g3", "c4", FreebieStore.PS_PLUS, Optional.empty()));
        news().run(now.plus(Duration.ofDays(4)));
        assertEquals(2, sent.size(), "but not after");
    }

    @Test void gamePassStartsSilentlyThenPostsChangesAtMostDaily() {
        subs.add(new Subscription("g", "c1", FreebieStore.GAME_PASS, Optional.empty()));
        game(id(1), true); game(id(2), true); game(id(3), false); game(id(4), true); game(id(5), true);
        shelves.put(GamePass.Shelf.ADDED, List.of(id(1)));
        news().run(now);
        assertEquals(List.of(), sent, "the first check only records the lists");

        shelves.put(GamePass.Shelf.ADDED, List.of(id(2), id(3), id(1)));
        news().run(now.plus(Duration.ofHours(3)));
        assertEquals(1, sent.size());
        var post = store.posts.values().iterator().next();
        assertEquals(List.of(id(2)), post.gamePass().get(GamePass.Shelf.ADDED).stream().map(GamePass.Game::id).toList(), "PC-only games are left out");

        shelves.put(GamePass.Shelf.COMING, List.of(id(4)));
        news().run(now.plus(Duration.ofHours(6)));
        assertEquals(1, sent.size(), "waits: one post a day at most");
        shelves.put(GamePass.Shelf.COMING, List.of());
        shelves.put(GamePass.Shelf.ADDED, List.of(id(4), id(2), id(3), id(1)));
        news().run(now.plus(Duration.ofHours(24)));
        assertEquals(2, sent.size());
        var latest = store.posts.values().stream().max(Comparator.comparing(ConsoleNews.Post::createdAt)).orElseThrow();
        assertEquals(Set.of(GamePass.Shelf.ADDED), latest.gamePass().keySet(), "a game that arrived before the post is posted once, as added");
    }

    @Test void aFailingChannelIsTriedThreeTimesAndAPauseHoldsEverything() {
        subs.add(new Subscription("g", "bad", FreebieStore.PS_PLUS, Optional.empty()));
        subs.add(new Subscription("g", "good", FreebieStore.PS_PLUS, Optional.empty()));
        broken.add("bad");
        feed = List.of(ps("p", now));
        paused = true;
        news().run(now);
        assertEquals(List.of(), sent);
        paused = false;
        for (int i = 1; i <= 5; i++) news().run(now.plus(Duration.ofHours(i)));
        assertEquals(1, sent.size());
        assertEquals(ConsoleNews.TRIES, store.posts.get("p").failures().get("bad"));
    }

    @Test void cardsFitDiscordAndSayWhatTheyNeed() {
        var card = ConsoleNewsCards.psPlusCard(ps("p", now), Optional.of("123"));
        String text = card.plainText();
        assertTrue(text.contains("PS Plus Essential, Extra or Premium"));
        assertTrue(text.contains("<@&123>"));
        assertTrue(text.contains("<t:" + LocalDate.of(2026, 12, 1).atTime(12, 0).toEpochSecond(ZoneOffset.UTC) + ":D>"));

        Map<GamePass.Shelf, List<GamePass.Game>> many = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
            List<GamePass.Game> games = new ArrayList<>();
            for (int i = 0; i < 30; i++) games.add(new GamePass.Game(id(i), "A very long game title that keeps going and going " + i,
                    Optional.of("https://store-images.s-microsoft.com/i" + i), true, Optional.empty()));
            many.put(shelf, games);
        }
        var gp = ConsoleNewsCards.gamePassCard(many, Optional.of("123"));
        assertTrue(gp.textLength() <= 4000, "text " + gp.textLength());
        assertTrue(gp.componentCount() <= 40, "components " + gp.componentCount());
        assertTrue(gp.plainText().contains("+ 20 more"));
        assertTrue(gp.plainText().contains("depends on your Game Pass plan"));
    }

    @Test void readsFreePlayDaysFromPureXbox() throws IOException {
        var weekends = FreeWeekend.parse(fixture("purexbox.xml"));
        assertEquals(1, weekends.size(), "other news is skipped");
        var w = weekends.getFirst();
        assertEquals("November 26-29", w.dates());
        assertEquals("www.purexbox.com", w.link().getHost());
        assertEquals(Optional.of("https://images.purexbox.com/ef37e04b22d94/large.jpg"), w.image());
        assertEquals(Instant.parse("2026-11-26T13:00:00Z"), w.published());
        assertEquals(Optional.of(w), FreeWeekend.read(w.toDocument()));
    }

    @Test void readsConsoleItemsFromGamerPower() throws IOException {
        var items = ConsoleItem.parse(fixture("gamerpower.json"));
        assertEquals(4, items.size(), "full games and PC-only items are skipped");
        var witcher = items.getFirst();
        assertEquals("gamerpower:3793", witcher.id());
        assertEquals("The Witcher 3: Wild Hunt Remastered - Scarlet Crest Armor", witcher.title(), "\"Giveaway\" dropped");
        assertEquals(List.of("PlayStation", "Xbox", "Switch", "PC"), witcher.platforms());
        assertEquals(Optional.empty(), witcher.ends());
        var bond = items.stream().filter(i -> i.id().equals("gamerpower:3656")).findFirst().orElseThrow();
        assertEquals(Optional.of(Instant.parse("2027-05-28T01:59:00Z")), bond.ends());
        assertEquals(Optional.of(bond), ConsoleItem.fromSaved(bond.toDocument()));
    }

    @Test void freePlayDaysGoToGamePassChannelsOnce() {
        subs.add(new Subscription("g", "xb", FreebieStore.GAME_PASS, Optional.empty()));
        subs.add(new Subscription("g", "ps", FreebieStore.PS_PLUS, Optional.empty()));
        weekends = List.of(new FreeWeekend("freeplay:/new", "November 26-29", URI.create("https://www.purexbox.com/a"), now.minus(Duration.ofHours(5)), Optional.empty()),
                new FreeWeekend("freeplay:/old", "November 19-22", URI.create("https://www.purexbox.com/b"), now.minus(Duration.ofDays(7)), Optional.empty()));
        news().run(now);
        news().run(now.plus(Duration.ofHours(3)));
        assertEquals(1, sent.size(), "the current weekend, once; last week's never");
        assertTrue(sent.getFirst().startsWith("xb"));
    }

    @Test void inGameItemsStartSilentlySkipEndedOnesAndPostDaily() {
        subs.add(new Subscription("g", "items", FreebieStore.CONSOLE_ITEMS, Optional.empty()));
        var old = item(1, Optional.empty());
        items = List.of(old);
        news().run(now);
        assertEquals(List.of(), sent, "what is already listed is only recorded");
        var fresh = item(2, Optional.of(now.plus(Duration.ofDays(5))));
        var ended = item(3, Optional.of(now.minus(Duration.ofHours(1))));
        items = List.of(fresh, ended, old);
        news().run(now.plus(Duration.ofHours(3)));
        assertEquals(1, sent.size());
        var post = store.posts.values().iterator().next();
        assertEquals(List.of(fresh), post.items());
        items = List.of(item(4, Optional.empty()), fresh, old);
        news().run(now.plus(Duration.ofHours(6)));
        assertEquals(1, sent.size(), "one post a day at most");
        news().run(now.plus(Duration.ofHours(24)));
        assertEquals(2, sent.size());
    }

    ConsoleItem item(int n, Optional<Instant> ends) {
        return new ConsoleItem("gamerpower:" + n, "Item " + n, URI.create("https://www.gamerpower.com/i" + n), List.of("Xbox"), ends, Optional.empty());
    }

    @Test void weekendAndItemCardsSayWhatTheyAre() {
        var weekend = ConsoleNewsCards.freeWeekendCard(new FreeWeekend("freeplay:/a", "November 26-29", URI.create("https://www.purexbox.com/a"),
                now, Optional.empty()), Optional.of("9")).plainText();
        assertTrue(weekend.contains("Free Play Days: November 26-29"));
        assertTrue(weekend.contains("you don't keep them"));
        List<ConsoleItem> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add(item(i, Optional.of(now.plus(Duration.ofDays(1)))));
        var card = ConsoleNewsCards.itemsCard(many, Optional.of("9"));
        assertTrue(card.componentCount() <= 40 && card.textLength() <= 4000);
        assertTrue(card.plainText().contains("keys can run out"));
        assertTrue(card.plainText().contains("+ 15 more"));
    }

    @Test void anUnchangedFeedChangesNothingAndReadsCountOnlyOnceStored() {
        subs.add(new Subscription("g", "c1", FreebieStore.PS_PLUS, Optional.empty()));
        List<String> calls = new ArrayList<>();
        var unchanged = new ConsoleNews(new ConsoleNews.Source() {
            @Override public Optional<List<PsPlusPost>> psPlus() { return Optional.empty(); }
            @Override public List<String> shelf(GamePass.Shelf shelf) { return List.of(); }
            @Override public List<GamePass.Game> games(List<String> ids) { return List.of(); }
            @Override public Optional<List<FreeWeekend>> freeWeekends() { return Optional.empty(); }
            @Override public Optional<List<ConsoleItem>> items() { return Optional.empty(); }
            @Override public void committed() { calls.add("commit"); }
            @Override public void rolledBack() { calls.add("rollback"); }
        }, store, new ConsoleNews.Discord() {
            @Override public boolean ready() { return true; }
            @Override public Optional<String> send(String g, String c, MessageCreateData m) { sent.add(c); return Optional.empty(); }
        }, s -> subs.stream().filter(x -> x.store() == s).toList(), () -> false);
        unchanged.run(now);
        assertEquals(List.of(), sent);
        assertEquals(List.of(), store.state.psSeen());
        assertEquals(List.of("commit"), calls);

        var broken = new Memory() { @Override public void save(ConsoleNews.State s) { throw new IllegalStateException("db down"); } };
        var failing = new ConsoleNews(new ConsoleNews.Source() {
            @Override public Optional<List<PsPlusPost>> psPlus() { return Optional.of(List.of(ps("p", now))); }
            @Override public List<String> shelf(GamePass.Shelf shelf) { return List.of(); }
            @Override public List<GamePass.Game> games(List<String> ids) { return List.of(); }
            @Override public Optional<List<FreeWeekend>> freeWeekends() { return Optional.empty(); }
            @Override public Optional<List<ConsoleItem>> items() { return Optional.empty(); }
            @Override public void committed() { calls.add("commit"); }
            @Override public void rolledBack() { calls.add("rollback"); }
        }, broken, (ConsoleNews.Discord) null, s -> List.of(), () -> false);
        assertThrows(IllegalStateException.class, () -> failing.run(now));
        assertEquals(List.of("commit", "rollback"), calls, "not stored: the feed is read again next time");
    }

    /** In memory, same rules as the MongoDB store. */
    static class Memory implements ConsoleNews.Store {
        ConsoleNews.State state = ConsoleNews.State.EMPTY;
        final Map<String, ConsoleNews.Post> posts = new LinkedHashMap<>();
        @Override public ConsoleNews.State state() { return state; }
        @Override public void save(ConsoleNews.State s) { state = s; }
        @Override public void create(ConsoleNews.Post p) { posts.putIfAbsent(p.id(), p); }
        @Override public List<ConsoleNews.Post> open() { return new ArrayList<>(posts.values()); }
        @Override public boolean claim(String id, String channel) {
            var p = posts.get(id);
            if (p.delivered().contains(channel)) return false;
            var d = new HashSet<>(p.delivered()); d.add(channel);
            posts.put(id, p.with(d, p.failures()));
            return true;
        }
        @Override public void failed(String id, String channel) {
            var p = posts.get(id);
            var d = new HashSet<>(p.delivered()); d.remove(channel);
            var f = new HashMap<>(p.failures()); f.merge(channel, 1, Integer::sum);
            posts.put(id, p.with(d, f));
        }
        @Override public void close(String id) { posts.remove(id); }
    }
}
