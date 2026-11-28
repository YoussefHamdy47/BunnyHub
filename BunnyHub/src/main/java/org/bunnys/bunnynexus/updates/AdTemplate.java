package org.bunnys.bunnynexus.updates;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills an ad's placeholders from what is happening right now ({@link AdContext}). An ad with a placeholder that has
 * no value at this moment (no deal running, no other free game, no end date known) is not shown: a live ad is only
 * ever true. Pure.
 *
 * <p>Placeholders: {@code {deal}} (the title as a link), {@code {deal.name}}, {@code {deal.url}}, {@code {deal.emoji}},
 * {@code {deal.store}}, {@code {deal.price}}, {@code {deal.was}}, {@code {deal.off}}, {@code {deal.ends}};
 * {@code {free}}, {@code {free.name}}, {@code {free.url}}, {@code {free.emoji}}, {@code {free.store}},
 * {@code {free.worth}}, {@code {free.ends}} (a game free right now on a launcher this channel does not post);
 * {@code {game}}, {@code {game.name}}, {@code {game.worth}} (the most valuable game in this alert);
 * {@code {month.count}}, {@code {month.worth}} (free games posted in the last 30 days); {@code {invite}},
 * {@code {server}}, {@code {vote}}.
 */
public final class AdTemplate {
    private AdTemplate() {}

    static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]{2,10}(?:\\.[a-z]{2,10})?)}");
    static final Set<String> KNOWN = Set.of("deal", "deal.name", "deal.url", "deal.emoji", "deal.store", "deal.price", "deal.was",
            "deal.off", "deal.ends", "free", "free.name", "free.url", "free.emoji", "free.store", "free.worth", "free.ends",
            "game", "game.name", "game.worth", "month.count", "month.worth", "invite", "server", "vote");
    static final Set<String> URLS = Set.of("deal.url", "free.url", "invite", "server", "vote");
    public static final String HELP = "Use {deal}, {deal.price}, {deal.off}, {deal.ends}, {free}, {free.ends}, {game}, {game.worth}, "
            + "{month.worth}, {invite}, {server}, {vote} and similar.";

    /** True when the text holds at least one placeholder. */
    static boolean uses(Ad ad) {
        if (holds(ad.text()) || ad.title().map(AdTemplate::holds).orElse(false) || ad.emoji().map(AdTemplate::holds).orElse(false)) return true;
        return ad.links().stream().anyMatch(l -> holds(l.label()) || holds(l.url()) || l.emoji().map(AdTemplate::holds).orElse(false));
    }

    private static boolean holds(String text) { return text != null && PLACEHOLDER.matcher(text).find(); }

    /** A button link that is a whole URL placeholder, like {@code {deal.url}}. */
    static boolean isUrlPlaceholder(String value) {
        var m = PLACEHOLDER.matcher(value.strip());
        return m.matches() && URLS.contains(m.group(1));
    }

    /**
     * The first placeholder-looking name that is not a known one, for the owner. Command links such as
     * {@code {/free-games setup}} are not placeholders.
     */
    static Optional<String> unknown(String text) {
        var m = Pattern.compile("\\{([^}/\\s]{1,30})}").matcher(text);
        while (m.find()) if (!KNOWN.contains(m.group(1))) return Optional.of(m.group(1));
        return Optional.empty();
    }

    /** The ad with every placeholder filled, or empty when one has no value right now. */
    public static Optional<Ad> render(Ad ad, AdContext context) {
        if (!uses(ad)) return Optional.of(ad);
        Map<String, String> values = values(context);
        try {
            var title = ad.title().map(t -> fill(t, values));
            var text = fill(ad.text(), values);
            var emoji = ad.emoji().map(e -> fill(e, values));
            List<Ad.Link> links = new ArrayList<>();
            for (Ad.Link link : ad.links()) {
                String url = fill(link.url(), values);
                if (!Ad.https(url)) return Optional.empty();
                links.add(new Ad.Link(fill(link.label(), values), url, link.emoji().map(e -> fill(e, values))));
            }
            return Optional.of(new Ad(ad.id(), title, text, ad.image(), links, ad.audience(), ad.on(), ad.createdAt(), emoji, ad.when(), ad.priority()));
        } catch (Missing missing) {
            return Optional.empty();
        }
    }

    private static final class Missing extends RuntimeException {
        Missing() { super(null, null, false, false); }
    }

    private static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) throw new Missing();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Map<String, String> values(AdContext c) {
        Map<String, String> v = new HashMap<>();
        c.invite().ifPresent(url -> v.put("invite", url));
        v.put("server", c.server());
        v.put("vote", c.vote());
        c.deal().ifPresent(d -> {
            v.put("deal", "[" + linkText(d.title()) + "](" + d.url() + ")");
            v.put("deal.name", d.title());
            v.put("deal.url", d.url());
            v.put("deal.emoji", d.emoji());
            v.put("deal.store", d.store());
            v.put("deal.price", d.price());
            v.put("deal.was", d.was());
            v.put("deal.off", d.percentOff() + "%");
            d.endsAt().ifPresent(end -> v.put("deal.ends", "<t:" + end.getEpochSecond() + ":R>"));
        });
        c.free().ifPresent(f -> {
            v.put("free", "[" + linkText(f.title()) + "](" + f.url() + ")");
            v.put("free.name", f.title());
            v.put("free.url", f.url());
            v.put("free.emoji", f.emoji());
            v.put("free.store", f.store());
            f.worth().ifPresent(w -> v.put("free.worth", w));
            f.endsAt().ifPresent(end -> v.put("free.ends", "<t:" + end.getEpochSecond() + ":R>"));
        });
        c.game().ifPresent(g -> {
            v.put("game", "[" + linkText(g.title()) + "](" + g.url() + ")");
            v.put("game.name", g.title());
            v.put("game.worth", g.worth());
        });
        if (c.monthCount() >= AdContext.MONTH_MIN && c.monthWorthCents() > 0) {
            v.put("month.count", String.valueOf(c.monthCount()));
            v.put("month.worth", AdContext.dollars(c.monthWorthCents()));
        }
        return v;
    }

    private static String linkText(String title) {
        return net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(title.replace('[', '(').replace(']', ')'));
    }
}
