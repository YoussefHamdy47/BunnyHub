package org.bunnys.bunnynexus.freebies;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * News headlines that may announce a console game free to keep ("PS5 Game Free to Keep for a Limited Time, No PS Plus
 * Required"). Only a hint for the owner: a headline has no store link and "free" is used loosely, so a tip is never
 * posted to servers until the owner adds the game ({@link ConsoleTips}). Pure.
 */
final class ConsoleHeadlines {
    /** One news feed and the console its free-game headlines are about. */
    record Source(String name, URI feed, FreebieStore store) {}

    static final List<Source> SOURCES = List.of(
            new Source("PlayStation Blog", URI.create("https://blog.playstation.com/feed/"), FreebieStore.PLAYSTATION),
            new Source("Push Square", URI.create("https://www.pushsquare.com/feeds/latest"), FreebieStore.PLAYSTATION),
            new Source("Pure Xbox", URI.create("https://www.purexbox.com/feeds/latest"), FreebieStore.XBOX));

    record Headline(String link, String title, Source source, Instant published) {}

    /** Says the game is free to keep, or free for a limited time without a subscription. */
    private static final Pattern GIVEAWAY = Pattern.compile("(?i)\\bfree\\b.*\\b(to keep|keep (it )?forever|yours to keep|for a limited time"
            + "|limited[- ]time|free download|claim)\\b|\\b(to keep|yours to keep|grab|claim|download)\\b.*\\bfree\\b");
    /** "No PS Plus required" is the opposite of a subscription game: removed before the exclusions are checked. */
    private static final Pattern NO_SUBSCRIPTION = Pattern.compile("(?i)\\b(no|without( an?)?|don't need( an?)?)\\s+(ps plus|playstation plus|game pass|subscriptions?)(\\s+(required|needed))?");
    /** Subscriptions, trials and free-to-play games are covered elsewhere or are not giveaways. */
    private static final Pattern NOT_A_GIVEAWAY = Pattern.compile("(?i)\\b(ps plus|playstation plus|game pass|free play days|free weekend|"
            + "free trial|trial|free[- ]to[- ]play|demo|dlc|update|upgrade|multiplayer weekend|beta|playtest|wallpaper|theme|avatar)\\b");

    private ConsoleHeadlines() {}

    static boolean giveaway(String title) {
        String plain = title.replace('’', '\'');
        if (!GIVEAWAY.matcher(plain).find()) return false;
        return !NOT_A_GIVEAWAY.matcher(NO_SUBSCRIPTION.matcher(plain).replaceAll(" ")).find();
    }

    /** The feed's giveaway headlines whose link is on the feed's own site; anything unreadable is skipped. */
    static List<Headline> parse(byte[] rss, Source source) {
        List<Headline> found = new ArrayList<>();
        org.w3c.dom.Document xml;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(rss));
        } catch (Exception unreadable) { return List.of(); }
        NodeList items = xml.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            try {
                var item = (Element) items.item(i);
                String title = text(item, "title").replaceAll("[\\p{Z}\\s]+", " ").strip();
                if (title.isEmpty() || title.length() > 300 || !giveaway(title)) continue;
                var link = URI.create(text(item, "link").strip());
                if (!"https".equals(link.getScheme()) || !source.feed().getHost().equals(link.getHost())) continue;
                var published = ZonedDateTime.parse(text(item, "pubDate").strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                found.add(new Headline(link.toString(), title, source, published));
            } catch (RuntimeException unreadable) { /* skip this item */ }
        }
        return found;
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }
}
