package org.bunnys.bunnynexus.consoles;

import org.bson.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PlayStation Blog post announcing PlayStation Plus games: the monthly games (every PS Plus tier) or the Game Catalog
 * (Extra and Premium). Read from the blog's RSS feed: the games come from the post title ("PlayStation Plus Monthly
 * Games for December: A, B, C"), the dates from its first paragraph ("from Tuesday, December 1 to Monday January 4").
 * Pure.
 */
public record PsPlusPost(String id, Kind kind, String month, List<String> games, boolean more, URI link, Instant published,
                         Optional<LocalDate> from, Optional<LocalDate> until, Optional<String> image) {
    public enum Kind {
        MONTHLY("monthly", "Monthly Games", "PS Plus Essential, Extra or Premium"),
        CATALOG("catalog", "Game Catalog", "PS Plus Extra or Premium");

        final String id, title, tiers;
        Kind(String id, String title, String tiers) { this.id = id; this.title = title; this.tiers = tiers; }

        static Optional<Kind> byId(String id) { return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst(); }
    }

    public PsPlusPost {
        games = List.copyOf(games);
    }

    static final String HOST = "blog.playstation.com";
    static final int MAX_GAMES = 20;
    private static final String MONTHS = "(January|February|March|April|May|June|July|August|September|October|November|December)";
    private static final Pattern TITLE = Pattern.compile("^PlayStation Plus (Monthly Games|Game Catalog) for " + MONTHS + "(?: \\d{4})?: (.+)$");
    private static final String DAY = "(?:(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),?\\s+)?";
    private static final Pattern DATES = Pattern.compile("\\b(?:from|on|starting)\\s+" + DAY + MONTHS + "\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?"
            + "(?:\\s+(?:to|until|through|-|–)\\s+" + DAY + MONTHS + "\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?)?");
    private static final Pattern IMAGE = Pattern.compile("<img[^>]+src=\"(https://blog\\.playstation\\.com/[^\"\\s]+)\"");

    /** The PS Plus posts in an RSS feed, newest first as the feed lists them; other posts and unreadable items are skipped. */
    static List<PsPlusPost> parse(byte[] rss) {
        List<PsPlusPost> posts = new ArrayList<>();
        org.w3c.dom.Document xml;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(rss));
        } catch (Exception unreadable) { return List.of(); }
        NodeList items = xml.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            try { read((Element) items.item(i)).ifPresent(posts::add); }
            catch (RuntimeException unreadable) { /* skip this item */ }
        }
        return posts;
    }

    private static Optional<PsPlusPost> read(Element item) {
        // The blog uses no-break spaces in titles.
        Matcher title = TITLE.matcher(spaces(text(item, "title")));
        if (!title.matches()) return Optional.empty();
        var kind = title.group(1).equals("Monthly Games") ? Kind.MONTHLY : Kind.CATALOG;
        var link = URI.create(text(item, "link").strip());
        if (!"https".equals(link.getScheme()) || !HOST.equals(link.getHost())) return Optional.empty();
        var published = ZonedDateTime.parse(text(item, "pubDate").strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        var games = new ArrayList<String>();
        boolean more = games(title.group(3), games);
        if (games.isEmpty()) return Optional.empty();
        String guid = text(item, "guid").strip();
        String id = "psplus:" + kind.id + ":" + (guid.isEmpty() ? link.getPath() : guid);

        String intro = plain(text(item, "description"));
        Optional<LocalDate> from = Optional.empty(), until = Optional.empty();
        Matcher dates = DATES.matcher(intro);
        if (dates.find()) {
            LocalDate pub = published.atZone(ZoneOffset.UTC).toLocalDate();
            from = Optional.of(date(dates.group(1), dates.group(2), dates.group(3), pub));
            if (dates.group(4) != null) until = Optional.of(date(dates.group(4), dates.group(5), dates.group(6), from.get()));
        }
        var image = IMAGE.matcher(text(item, "content:encoded"));
        return Optional.of(new PsPlusPost(id, kind, title.group(2), games, more, link, published, from, until,
                image.find() ? Optional.of(image.group(1).replace("&amp;", "&")) : Optional.empty()));
    }

    /** "A, B, and C" / "A, B and more" into the games; true when the title ends in "and more". */
    static boolean games(String list, List<String> into) {
        boolean more = false;
        for (String part : list.split(",")) {
            String game = part.strip();
            if (game.startsWith("and ")) game = game.substring(4).strip();
            if (game.equals("more") || game.equals("more!")) { more = true; continue; }
            if (game.endsWith(" and more")) { more = true; game = game.substring(0, game.length() - 9).strip(); }
            if (!game.isEmpty() && into.size() < MAX_GAMES) into.add(game);
        }
        return more;
    }

    /** The day {@code month day} on or after (about) {@code after}: a January date in a December post is next year. */
    private static LocalDate date(String month, String day, String year, LocalDate after) {
        Month m = Month.valueOf(month.toUpperCase(Locale.ROOT));
        int d = Integer.parseInt(day);
        if (year != null) return LocalDate.of(Integer.parseInt(year), m, d);
        LocalDate guess = LocalDate.of(after.getYear(), m, d);
        return guess.isBefore(after.minusDays(60)) ? guess.plusYears(1) : guess;
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    static String spaces(String text) { return text.replaceAll("[\\p{Z}\\s]+", " ").strip(); }

    /** HTML to plain text, enough for one sentence of the description. */
    static String plain(String html) {
        String text = html.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replace("&#8217;", "’").replace("&#8211;", "–")
                .replace("&#8230;", "…").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return spaces(text);
    }

    /** "December" and the title as the blog names it: "Monthly Games for December". */
    public String heading() { return "PlayStation Plus " + kind.title + " for " + month; }

    public String tiers() { return kind.tiers; }

    Document toDocument() {
        return new Document("id", id).append("kind", kind.id).append("month", month).append("games", games).append("more", more)
                .append("link", link.toString()).append("published", Date.from(published))
                .append("from", from.map(LocalDate::toString).orElse(null)).append("until", until.map(LocalDate::toString).orElse(null))
                .append("image", image.orElse(null));
    }

    static Optional<PsPlusPost> read(Document d) {
        try {
            var link = URI.create(d.getString("link"));
            if (!HOST.equals(link.getHost())) return Optional.empty();
            return Optional.of(new PsPlusPost(d.getString("id"), Kind.byId(d.getString("kind")).orElseThrow(), d.getString("month"),
                    d.getList("games", String.class), d.getBoolean("more", false), link, d.getDate("published").toInstant(),
                    Optional.ofNullable(d.getString("from")).map(LocalDate::parse), Optional.ofNullable(d.getString("until")).map(LocalDate::parse),
                    Optional.ofNullable(d.getString("image"))));
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }

}
