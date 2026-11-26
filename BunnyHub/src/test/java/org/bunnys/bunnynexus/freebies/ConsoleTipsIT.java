package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.time.*;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: a headline becomes one tip, and adding it queues a normal pending offer only when the store agrees. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleTipsIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    final Instant now = Instant.parse("2026-12-01T12:00:00Z");
    final Map<String, byte[]> responses = new HashMap<>();
    final List<String> posted = new ArrayList<>();
    int newGames;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("tips_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db);
        repository.installIndexes();
        responses.clear(); posted.clear(); newGames = 0;
        String empty = "<?xml version=\"1.0\"?><rss><channel></channel></rss>";
        for (var s : ConsoleHeadlines.SOURCES) responses.put(s.feed().toString(), empty.getBytes());
    }

    ConsoleTips tips() {
        return new ConsoleTips(db.getCollection(ConsoleTips.COLLECTION), new ConsoleTips.Http() {
            @Override public Optional<byte[]> ifChanged(URI url) throws java.io.IOException { return Optional.of(get(url)); }
            @Override public byte[] get(URI url) throws java.io.IOException {
                byte[] body = responses.get(url.toString());
                if (body == null) throw new java.io.IOException("no answer");
                return body;
            }
            @Override public void commit() {}
            @Override public void rollback() {}
        }, () -> {}, (FreebieStore store, MessageCreateData card) -> posted.add(store.id() + " " + card.getComponents().size()),
                repository, () -> newGames++, Clock.fixed(now, ZoneOffset.UTC));
    }

    void headline(String title, String link, Instant at) {
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atZone(ZoneOffset.UTC));
        responses.put("https://www.purexbox.com/feeds/latest", ("<?xml version=\"1.0\"?><rss><channel><item><title>" + title + "</title><link>" + link
                + "</link><pubDate>" + date + "</pubDate></item></channel></rss>").getBytes());
    }

    @Test void aHeadlineIsTippedOnceAndAnXboxGameIsAddedOnlyAtZeroDollars() throws Exception {
        String link = "https://www.purexbox.com/news/2026/12/xbox-game-free-to-keep";
        headline("Xbox Makes 2021 Game Free For Limited Time, No Game Pass Required", link, now.minus(Duration.ofHours(2)));
        headline("Xbox Makes 2021 Game Free For Limited Time, No Game Pass Required", link, now.minus(Duration.ofHours(2)));
        var tips = tips();
        assertEquals(1, tips.run());
        assertEquals(0, tips.run(), "each headline once");
        assertTrue(posted.getFirst().startsWith("xbox"), "goes where Xbox games are reviewed");
        String tip = ConsoleTips.id(link);

        String store = "https://www.xbox.com/en-US/games/store/sniper/9NV8BWGH15GN";
        byte[] onSale;
        try (var in = getClass().getResourceAsStream("/consoles/catalog-2-products.json")) { onSale = in.readAllBytes(); }
        responses.put(XboxStore.url("9NV8BWGH15GN"), onSale);
        var refused = tips.add(tip, store, "", "");
        assertFalse(refused.added());
        assertTrue(refused.text().contains("$3.99"), refused.text());
        assertEquals(0, db.getCollection(FreebieRepository.OFFERS).countDocuments());

        responses.put(XboxStore.url("9NV8BWGH15GN"), new String(onSale).replace("\"ListPrice\":3.99", "\"ListPrice\":0").getBytes());
        var added = tips.add(tip, store, "", "");
        assertTrue(added.added(), added.text());
        assertEquals(1, newGames);
        Document offer = db.getCollection(FreebieRepository.OFFERS).find().first();
        assertEquals("PENDING", offer.getString("state"), "a normal review comes next");
        assertEquals("console", offer.getString("source"));
        assertEquals("xbox", offer.getString("store"));
        assertEquals("Sniper Ghost Warrior Contracts 2", offer.getString("title"));
        assertEquals("$39.99", offer.getString("worth"));
        assertEquals("https://www.xbox.com/en-US/games/store/x/9NV8BWGH15GN", offer.getString("claimUrl"));
        assertEquals(Date.from(Instant.parse("2026-12-03T09:59:59Z")), offer.getDate("endsAt"), "the store's own end date");
        assertEquals("Microsoft Store", FreebieOffer.read(offer).sourceLabel());
        assertFalse(tips.add(tip, store, "", "").added(), "never twice");
    }

    @Test void aPlayStationLinkNeedsATitleAndOldOrForeignLinksAreRefused() {
        String link = "https://www.purexbox.com/news/a";
        headline("Grab This Xbox Game for Free Before It's Gone", link, now.minus(Duration.ofHours(1)));
        var tips = tips();
        tips.run();
        String tip = ConsoleTips.id(link);
        String ps = "https://store.playstation.com/en-us/concept/10012345";
        assertFalse(tips.add(tip, ps, "", "").added());
        assertFalse(tips.add(tip, "https://evil.example/game", "Game", "").added());
        assertFalse(tips.add(tip, ps, "Game", "2026-11-01").added(), "an end in the past");
        var added = tips.add(tip, ps, "Buffet Boss", "2026-12-05 16:00");
        assertTrue(added.added(), added.text());
        var offer = db.getCollection(FreebieRepository.OFFERS).find().first();
        assertEquals("playstation", offer.getString("store"));
        assertEquals("Buffet Boss", offer.getString("title"));
        assertEquals("PlayStation Store", FreebieOffer.read(offer).sourceLabel());

        headline("Grab This Xbox Game for Free Before It's Gone", "https://www.purexbox.com/news/old", now.minus(Duration.ofDays(5)));
        assertEquals(0, tips.run(), "a headline over three days old is not a tip");
        assertEquals("Dismissed: not a giveaway.", tips.dismiss(tip));
        assertEquals("dismissed", db.getCollection(ConsoleTips.COLLECTION).find(eq("_id", tip)).first().getString("state"));
    }
}
