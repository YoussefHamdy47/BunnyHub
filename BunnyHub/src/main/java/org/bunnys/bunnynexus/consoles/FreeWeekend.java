package org.bunnys.bunnynexus.consoles;

import org.bson.Document;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Xbox Free Play Days weekend: a few games anyone can try for free for a few days (not keep), most with a Game Pass
 * plan. Xbox Wire refuses automated readers, so the weekly Pure Xbox roundup is the signal; its post lists the games
 * and is updated once Xbox confirms them, so the alert links to it rather than copying a list that may change.
 */
public record FreeWeekend(String id, String dates, URI link, Instant published, Optional<String> image) {
    static final String HOST = "www.purexbox.com", IMAGES = "images.purexbox.com";
    static final String FEED = "https://" + HOST + "/feeds/latest";
    private static final Pattern TITLE = Pattern.compile("^Free Play Days\\b.*?\\(([^()]{3,40})\\)\\s*$");

    static List<FreeWeekend> parse(byte[] rss) {
        List<FreeWeekend> weekends = new ArrayList<>();
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
                Matcher title = TITLE.matcher(PsPlusPost.spaces(text(item, "title")));
                if (!title.matches()) continue;
                var link = URI.create(text(item, "link").strip());
                if (!"https".equals(link.getScheme()) || !HOST.equals(link.getHost())) continue;
                var published = ZonedDateTime.parse(text(item, "pubDate").strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Optional<String> image = Optional.empty();
                NodeList media = item.getElementsByTagName("media:content");
                if (media.getLength() > 0) image = Optional.of(((Element) media.item(0)).getAttribute("url")).filter(FreeWeekend::imageHost);
                weekends.add(new FreeWeekend("freeplay:" + link.getPath(), title.group(1).strip(), link, published, image));
            } catch (RuntimeException unreadable) { /* skip this item */ }
        }
        return weekends;
    }

    private static boolean imageHost(String url) {
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && IMAGES.equals(uri.getHost());
        } catch (RuntimeException bad) { return false; }
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    Document toDocument() {
        return new Document("id", id).append("dates", dates).append("link", link.toString()).append("published", Date.from(published))
                .append("image", image.orElse(null));
    }

    static Optional<FreeWeekend> read(Document d) {
        try {
            var link = URI.create(d.getString("link"));
            if (!HOST.equals(link.getHost())) return Optional.empty();
            return Optional.of(new FreeWeekend(d.getString("id"), d.getString("dates"), link, d.getDate("published").toInstant(),
                    Optional.ofNullable(d.getString("image")).filter(FreeWeekend::imageHost)));
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }
}
