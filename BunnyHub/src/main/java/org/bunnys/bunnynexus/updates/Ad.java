package org.bunnys.bunnynexus.updates;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One owner-written ad shown as a small card under free-game alerts (docs/product-updates/README.md, "Ads in
 * alerts"). Everything but the text is optional. Images and links must be https.
 */
public record Ad(String id, Optional<String> title, String text, Optional<URI> image, Optional<Link> link, Audience audience,
                 boolean on, Instant createdAt) {
    public static final int MAX_TITLE = 100, MAX_TEXT = 600, MAX_LABEL = 40;
    static final Pattern ID = Pattern.compile("[a-z0-9-]{2,32}");

    /** A link button under the ad. */
    public record Link(String label, URI url) {}

    /** Who sees it. */
    public enum Audience {
        ALL("Every server"), NO_DEALS_BOARD("Servers without a Deals Board");
        public final String label;
        Audience(String label) { this.label = label; }

        boolean matches(java.util.Set<ProductUpdate.Feature> uses) {
            return this == ALL || !uses.contains(ProductUpdate.Feature.DEALS_BOARD);
        }
    }

    /** What is wrong with these values, or nothing. */
    public static Optional<String> problem(String id, String title, String text, String image, String label, String url) {
        if (id == null || !ID.matcher(id).matches()) return Optional.of("The id must be 2-32 lowercase letters, numbers or dashes.");
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) return Optional.of("The text must be 1-" + MAX_TEXT + " characters.");
        if (title != null && title.length() > MAX_TITLE) return Optional.of("Keep the title within " + MAX_TITLE + " characters.");
        if (image != null && !https(image)) return Optional.of("The image must be an https link to a picture.");
        if ((label == null) != (url == null)) return Optional.of("A button needs both a label and a link.");
        if (label != null && (label.isBlank() || label.length() > MAX_LABEL)) return Optional.of("Keep the button label within " + MAX_LABEL + " characters.");
        if (url != null && !https(url)) return Optional.of("The button link must start with https://.");
        return Optional.empty();
    }

    static boolean https(String value) {
        try {
            URI uri = URI.create(value.strip());
            return "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException bad) { return false; }
    }

    /** Typed "\n" becomes a line break; slash-command options cannot contain real line breaks. */
    static String lines(String raw) { return raw == null ? null : raw.replace("\\n", "\n").strip(); }
}
