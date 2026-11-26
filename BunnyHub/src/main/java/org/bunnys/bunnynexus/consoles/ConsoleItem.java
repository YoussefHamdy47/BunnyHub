package org.bunnys.bunnynexus.consoles;

import org.bson.Document;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * A free in-game item for a console game (an outfit, an emblem, a pack of in-game currency), from GamerPower's list:
 * its DLC giveaways that name PlayStation, Xbox or Switch. Usually a key from a partner site, so keys can run out and
 * the site may ask for an account. Pure parsing.
 */
public record ConsoleItem(String id, String title, URI link, List<String> platforms, Optional<Instant> ends, Optional<String> image) {
    public ConsoleItem { platforms = List.copyOf(platforms); }

    static final String HOST = "www.gamerpower.com";
    /** Only console DLC ("loot"): 5 KB instead of the 20 KB full list. */
    static final String FEED = "https://" + HOST + "/api/filter?platform=ps4.ps5.xbox-one.xbox-series-xs.switch&type=loot";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);
    /** Console names in GamerPower's platform text, and how a post shows them. */
    private static final Map<String, String> CONSOLES = new LinkedHashMap<>();
    static {
        CONSOLES.put("playstation", "PlayStation");
        CONSOLES.put("xbox", "Xbox");
        CONSOLES.put("nintendo switch", "Switch");
    }

    /** Active console DLC giveaways in GamerPower's response; anything unreadable is skipped. */
    static List<ConsoleItem> parse(byte[] body) {
        List<ConsoleItem> items = new ArrayList<>();
        List<Document> rows;
        try { rows = Document.parse("{\"l\":" + new String(body, StandardCharsets.UTF_8) + "}").getList("l", Document.class); }
        catch (RuntimeException unreadable) { return List.of(); }
        for (Document row : rows) {
            try { read(row).ifPresent(items::add); }
            catch (RuntimeException unreadable) { /* skip this row */ }
        }
        return items;
    }

    private static Optional<ConsoleItem> read(Document row) {
        if (!"DLC".equals(row.getString("type")) || !"Active".equals(row.getString("status"))) return Optional.empty();
        String platformText = Objects.requireNonNullElse(row.getString("platforms"), "").toLowerCase(Locale.ROOT);
        List<String> platforms = new ArrayList<>();
        CONSOLES.forEach((tag, name) -> { if (platformText.contains(tag)) platforms.add(name); });
        if (platforms.isEmpty()) return Optional.empty();
        if (platformText.contains("pc")) platforms.add("PC");
        Object id = row.get("id");
        String title = row.getString("title");
        if (!(id instanceof Number n) || title == null || title.isBlank()) return Optional.empty();
        var link = URI.create(row.getString("gamerpower_url"));
        if (!"https".equals(link.getScheme()) || !HOST.equals(link.getHost())) return Optional.empty();
        String end = row.getString("end_date");
        Optional<Instant> ends = end == null || end.equals("N/A") ? Optional.empty()
                // GamerPower's dates have no zone; two hours of margin, as for free games.
                : Optional.of(LocalDateTime.parse(end, DATE).toInstant(ZoneOffset.UTC).plus(Duration.ofHours(2)));
        String image = row.getString("image");
        return Optional.of(new ConsoleItem("gamerpower:" + n.longValue(), shortTitle(title), link, platforms, ends,
                Optional.ofNullable(image).filter(ConsoleItem::imageHost)));
    }

    /** "Destiny 2 Emblem Key Giveaway" is "Destiny 2 Emblem Key". */
    static String shortTitle(String title) {
        return title.strip().replaceAll("(?i)\\s+giveaway$", "");
    }

    private static boolean imageHost(String url) {
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost());
        } catch (RuntimeException bad) { return false; }
    }

    Document toDocument() {
        return new Document("id", id).append("title", title).append("link", link.toString()).append("platforms", platforms)
                .append("ends", ends.map(Date::from).orElse(null)).append("image", image.orElse(null));
    }

    static Optional<ConsoleItem> fromSaved(Document d) {
        try {
            var link = URI.create(d.getString("link"));
            if (!HOST.equals(link.getHost())) return Optional.empty();
            return Optional.of(new ConsoleItem(d.getString("id"), d.getString("title"), link, d.getList("platforms", String.class),
                    Optional.ofNullable(d.getDate("ends")).map(Date::toInstant), Optional.ofNullable(d.getString("image"))));
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }
}
