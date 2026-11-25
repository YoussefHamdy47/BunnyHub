package org.bunnys.bunnynexus.deals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The live Steam and GOG responses captured 2026-11-23 (docs/deals/06-testing.md). */
final class DealFixtures {
    static final Instant FETCHED = Instant.parse("2026-11-23T15:00:00Z");

    static byte[] file(String name) throws Exception { return Files.readAllBytes(Path.of("src/test/resources/deals/" + name + "-2026-11-23.json")); }
    static byte[] steamSearch(String order) throws Exception { return file("steam-search-" + order); }
    static byte[] steamItems(String order) throws Exception { return file("steam-items-" + order); }
    static byte[] gog() throws Exception { return file("gog-catalog"); }

    /** Steam's item details for these apps, in request order, answered from both captured item pages. */
    static byte[] steamItemsFor(List<Long> appIds) throws Exception {
        java.util.Map<Long, org.bson.Document> byId = new java.util.HashMap<>();
        for (String order : List.of("topsellers", "reviews"))
            for (var item : org.bson.Document.parse(new String(steamItems(order), java.nio.charset.StandardCharsets.UTF_8))
                    .get("response", org.bson.Document.class).getList("store_items", org.bson.Document.class))
                byId.putIfAbsent(((Number) item.get("appid")).longValue(), item);
        var items = appIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return new org.bson.Document("response", new org.bson.Document("store_items", items)).toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Every row on sale, as a refresh reads them: Steam's two pages, then GOG. */
    static List<Deal> rows() throws Exception {
        List<Deal> rows = new ArrayList<>(DealIntake.steamItems(steamItems("topsellers")));
        rows.addAll(DealIntake.steamItems(steamItems("reviews")));
        rows.addAll(DealIntake.gog(gog()));
        return rows;
    }

    /** The fixture as a refresh would publish it. */
    static DealSnapshot snapshot() throws Exception {
        var rows = rows();
        return new DealSnapshot(DealRules.rank(rows), FETCHED, rows.size());
    }

    static Deal deal(String id, DealStore store, long sale, long normal) {
        return new Deal(id, store, "Game " + id, URI.create(store == DealStore.GOG ? "https://www.gog.com/en/game/game_" + id.replace(':', '_')
                : "https://store.steampowered.com/app/" + id.replaceAll("[^0-9]", "") + "/"), sale, normal,
                (int) Math.round(100.0 * (normal - sale) / normal), store == DealStore.GOG ? 47 : 90, 5000, Optional.empty());
    }

    private DealFixtures() {}
}
