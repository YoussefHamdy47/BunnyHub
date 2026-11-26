package org.bunnys.bunnynexus.consoles;

import org.bson.Document;
import org.bunnys.bunnynexus.ratings.GameRating;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
                         Optional<LocalDate> from, Optional<LocalDate> until, Optional<String> image, List<Game> details) {
    /**
     * One game as the post describes it: its own heading ("Dragon Ball Xenoverse 2 | PS5, PS4"), the picture above it
     * and the first sentence below it; {@code rating}: its Steam players' rating, when exactly one Steam game has that title.
     */
    public record Game(String name, List<String> platforms, Optional<String> image, Optional<String> blurb, Optional<GameRating> rating) {
        public Game { platforms = List.copyOf(platforms); }

        Game withRating(Optional<GameRating> found) { return new Game(name, platforms, image, blurb, found); }

        /** The PlayStation Store search for this game (the blog links no store pages). */
        public String storeSearch() {
            return "https://store.playstation.com/en-us/search/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        }

        Document toDocument(Instant at) {
            return new Document("name", name).append("platforms", platforms).append("image", image.orElse(null))
                    .append("blurb", blurb.orElse(null)).append("rating", rating.map(r -> r.toDocument(at)).orElse(null));
        }

        static Game read(Document d) {
            return new Game(d.getString("name"), d.getList("platforms", String.class, List.of()),
                    Optional.ofNullable(d.getString("image")).filter(PsPlusPost::ownImage), Optional.ofNullable(d.getString("blurb")),
                    Optional.ofNullable(d.get("rating", Document.class)).flatMap(GameRating::read));
        }
    }

    public PsPlusPost(String id, Kind kind, String month, List<String> games, boolean more, URI link, Instant published,
                      Optional<LocalDate> from, Optional<LocalDate> until, Optional<String> image) {
        this(id, kind, month, games, more, link, published, from, until, image, List.of());
    }

    PsPlusPost withDetails(List<Game> found) {
        return new PsPlusPost(id, kind, month, games, more, link, published, from, until, image, found);
    }
    public enum Kind {
        MONTHLY("monthly", "Monthly Games", "PS Plus Essential, Extra or Premium"),
        CATALOG("catalog", "Game Catalog", "PS Plus Extra or Premium");

        final String id, title, tiers;
        Kind(String id, String title, String tiers) { this.id = id; this.title = title; this.tiers = tiers; }

        static Optional<Kind> byId(String id) { return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst(); }
    }

    public PsPlusPost {
        games = List.copyOf(games);
        details = List.copyOf(details);
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
        String content = text(item, "content:encoded");
        var image = IMAGE.matcher(content);
        return Optional.of(new PsPlusPost(id, kind, title.group(2), games, more, link, published, from, until,
                image.find() ? Optional.of(image.group(1).replace("&amp;", "&")) : Optional.empty(), details(content, games)));
    }

    private static final Pattern HEADING = Pattern.compile("(?is)<h[2-4][^>]*>(.*?)</h[2-4]>");
    private static final Pattern PLATFORM = Pattern.compile("\\b(PS5|PS4|PS VR2)\\b");
    private static final String IMAGE_BOX = "View and download image Download the image close Close Download this image";
    static final int MAX_BLURB = 180;

    /**
     * Each listed game's section of the post: a heading "Name | PS5, PS4", the last picture before it and the first
     * sentence after it. Games without such a heading are left out; the order is the title's.
     */
    static List<Game> details(String html, List<String> games) {
        record Section(String name, List<String> platforms, Optional<String> image, Optional<String> blurb) {}
        List<int[]> spans = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        Matcher h = HEADING.matcher(html);
        while (h.find()) { spans.add(new int[]{h.start(), h.end()}); headings.add(plain(h.group(1))); }
        List<Section> sections = new ArrayList<>();
        int previousEnd = 0;
        for (int i = 0; i < spans.size(); i++) {
            String heading = headings.get(i);
            int bar = heading.lastIndexOf('|');
            int start = spans.get(i)[0], end = spans.get(i)[1], next = i + 1 < spans.size() ? spans.get(i + 1)[0] : html.length();
            if (bar > 0) {
                List<String> platforms = new ArrayList<>();
                Matcher p = PLATFORM.matcher(heading.substring(bar));
                while (p.find()) if (!platforms.contains(p.group(1))) platforms.add(p.group(1));
                Optional<String> picture = Optional.empty();
                Matcher img = IMAGE.matcher(html.substring(previousEnd, start));
                while (img.find()) picture = Optional.of(img.group(1).replace("&amp;", "&"));
                sections.add(new Section(heading.substring(0, bar).strip(), platforms, picture, sentence(plain(html.substring(end, next)))));
            }
            previousEnd = end;
        }
        List<Game> found = new ArrayList<>();
        for (String game : games) {
            String wanted = key(game);
            sections.stream().filter(sec -> !wanted.isEmpty() && (key(sec.name()).startsWith(wanted) || wanted.startsWith(key(sec.name()))))
                    .findFirst().ifPresent(sec -> found.add(new Game(game, sec.platforms(), sec.image(), sec.blurb(), Optional.empty())));
        }
        return found;
    }

    /** Letters and digits only, lower case: "Tony Hawk’s Pro Skater 3 + 4" matches "Tony Hawk's Pro Skater 3+4". */
    private static String key(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }

    /** The first sentence, without the image box's button text, at most {@link #MAX_BLURB} characters. */
    static Optional<String> sentence(String text) {
        String clean = text.replace(IMAGE_BOX, " ").replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) return Optional.empty();
        int stop = -1;
        for (String end : List.of(". ", "! ", "? ")) {
            int at = clean.indexOf(end);
            if (at > 0 && (stop < 0 || at < stop)) stop = at + 1;
        }
        String first = stop > 0 ? clean.substring(0, stop) : clean;
        if (first.length() > MAX_BLURB) {
            int cut = first.lastIndexOf(' ', MAX_BLURB - 1);
            first = first.substring(0, cut > 40 ? cut : MAX_BLURB - 1) + "…";
        }
        return Optional.of(first);
    }

    static boolean ownImage(String url) {
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost());
        } catch (RuntimeException bad) { return false; }
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
                .append("image", image.orElse(null)).append("details", details.stream().map(g -> g.toDocument(published)).toList());
    }

    static Optional<PsPlusPost> read(Document d) {
        try {
            var link = URI.create(d.getString("link"));
            if (!HOST.equals(link.getHost())) return Optional.empty();
            return Optional.of(new PsPlusPost(d.getString("id"), Kind.byId(d.getString("kind")).orElseThrow(), d.getString("month"),
                    d.getList("games", String.class), d.getBoolean("more", false), link, d.getDate("published").toInstant(),
                    Optional.ofNullable(d.getString("from")).map(LocalDate::parse), Optional.ofNullable(d.getString("until")).map(LocalDate::parse),
                    Optional.ofNullable(d.getString("image")),
                    d.getList("details", Document.class, List.of()).stream().map(Game::read).toList()));
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }

}
