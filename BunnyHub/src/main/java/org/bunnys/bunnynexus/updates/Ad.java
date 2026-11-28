package org.bunnys.bunnynexus.updates;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One ad shown as a small card under free-game alerts (docs/product-updates/README.md, "Ads in alerts"). Everything
 * but the text is optional. The text may hold links written as {@code [words](https://...)}; {@code emoji} leads the
 * title, and each button may carry its own emoji.
 *
 * <p>Live ads: title, text, emoji and buttons may hold placeholders such as {@code {deal}} or {@code {free.ends}},
 * filled from what is on sale or free right now when the ad is shown ({@link AdTemplate}). An ad whose placeholders
 * cannot be filled at that moment is not shown, so "X is 75% off" only appears while X really is.
 *
 * @param when     the moment it is for: any alert, or only an alert with a game worth {@link When#BIG_GAME_CENTS} or more
 * @param priority higher first among the ads a server may see now; live ads outrank evergreen ones
 */
public record Ad(String id, Optional<String> title, String text, Optional<URI> image, List<Link> links, Audience audience,
                 boolean on, Instant createdAt, Optional<String> emoji, When when, int priority) {
    public static final int MAX_TITLE = 100, MAX_TEXT = 600, MAX_LABEL = 40, MAX_LINKS = 3;
    static final Pattern ID = Pattern.compile("[a-z0-9-]{2,32}");
    /** One emoji: a Unicode emoji (a few code points), a custom one ({@code <:name:id>}), or a placeholder like {@code {deal.emoji}}. */
    static final Pattern EMOJI = Pattern.compile("<a?:[A-Za-z0-9_]{2,32}:[0-9]{17,20}>|[^\\sA-Za-z0-9<>{}]{1,8}|\\{[a-z.]{2,20}}");
    public static final Pattern EMOJI_PATTERN = EMOJI;

    public Ad {
        Objects.requireNonNull(emoji); Objects.requireNonNull(when);
        links = List.copyOf(links);
        if (links.size() > MAX_LINKS) throw new IllegalArgumentException("at most " + MAX_LINKS + " buttons");
    }

    /** An evergreen ad for any alert. */
    public Ad(String id, Optional<String> title, String text, Optional<URI> image, List<Link> links, Audience audience,
              boolean on, Instant createdAt, Optional<String> emoji) {
        this(id, title, text, image, links, audience, on, createdAt, emoji, When.ANY, 0);
    }

    /** An ad with at most one plain button and no emoji (ads saved before buttons had emojis). */
    public Ad(String id, Optional<String> title, String text, Optional<URI> image, Optional<Link> link, Audience audience,
              boolean on, Instant createdAt) {
        this(id, title, text, image, link.map(List::of).orElse(List.of()), audience, on, createdAt, Optional.empty());
    }

    /** The first button, if any. */
    public Optional<Link> link() { return links.isEmpty() ? Optional.empty() : Optional.of(links.getFirst()); }

    /** True when any part of the ad is filled from live data. */
    public boolean live() { return AdTemplate.uses(this); }

    Ad withOn(boolean value) { return new Ad(id, title, text, image, links, audience, value, createdAt, emoji, when, priority); }

    /** A link button inside the ad card. {@code url} is https, or a placeholder such as {@code {deal.url}}. */
    public record Link(String label, String url, Optional<String> emoji) {
        public Link { Objects.requireNonNull(url); Objects.requireNonNull(emoji); }
        public Link(String label, URI url) { this(label, url.toString(), Optional.empty()); }
        public Link(String label, URI url, Optional<String> emoji) { this(label, url.toString(), emoji); }
    }

    /** Who sees it. */
    public enum Audience {
        ALL("Every server"), NO_DEALS_BOARD("Servers without a Deals Board");
        public final String label;
        Audience(String label) { this.label = label; }

        boolean matches(java.util.Set<ProductUpdate.Feature> uses) {
            return this == ALL || !uses.contains(ProductUpdate.Feature.DEALS_BOARD);
        }
    }

    /** The moment an ad is for. */
    public enum When {
        ANY("Any free-game alert"),
        /** People are happiest right after a big free game: the moment to ask them to share, add or vote. */
        BIG_GAME("Only with a game worth $20 or more");
        public static final long BIG_GAME_CENTS = 2000;
        public final String label;
        When(String label) { this.label = label; }
    }

    /** What is wrong with these values, or nothing. */
    public static Optional<String> problem(String id, String title, String text, String image, String label, String url) {
        if (id == null || !ID.matcher(id).matches()) return Optional.of("The id must be 2-32 lowercase letters, numbers or dashes.");
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) return Optional.of("The text must be 1-" + MAX_TEXT + " characters.");
        if (title != null && title.length() > MAX_TITLE) return Optional.of("Keep the title within " + MAX_TITLE + " characters.");
        if (image != null && !https(image)) return Optional.of("The image must be an https link to a picture.");
        if ((label == null) != (url == null)) return Optional.of("A button needs both a label and a link.");
        if (label != null && (label.isBlank() || label.length() > MAX_LABEL)) return Optional.of("Keep the button label within " + MAX_LABEL + " characters.");
        if (url != null && !https(url) && !AdTemplate.isUrlPlaceholder(url)) return Optional.of("The button link must start with https:// or be a link placeholder like {deal.url}.");
        return Optional.empty();
    }

    /** As above, plus the ad's emoji, up to three buttons, and that every placeholder is a known one. */
    public static Optional<String> problems(String id, String title, String text, String image, String emoji,
                                            List<String[]> buttons) {
        if (emoji != null && !EMOJI.matcher(emoji.strip()).matches()) return Optional.of("The emoji must be one emoji, like 🎮 or <:name:id>.");
        if (buttons.size() > MAX_LINKS) return Optional.of("An ad can have at most " + MAX_LINKS + " buttons.");
        for (String[] b : buttons) {
            var problem = problem(id, title, text, image, b[0], b[1]);
            if (problem.isPresent()) return problem;
            if (b.length > 2 && b[2] != null && !EMOJI.matcher(b[2].strip()).matches()) return Optional.of("A button emoji must be one emoji, like ⭐.");
        }
        var unknown = AdTemplate.unknown(String.join(" ", java.util.stream.Stream.concat(
                java.util.stream.Stream.of(title, text, emoji), buttons.stream().flatMap(java.util.Arrays::stream))
                .filter(Objects::nonNull).toList()));
        if (unknown.isPresent()) return Optional.of("Unknown placeholder {" + unknown.get() + "}. " + AdTemplate.HELP);
        return problem(id, title, text, image, (String) null, (String) null);
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
