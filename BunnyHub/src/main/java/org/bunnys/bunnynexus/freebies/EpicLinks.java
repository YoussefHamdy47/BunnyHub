package org.bunnys.bunnynexus.freebies;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Epic storefront URLs. Pure functions.
 *
 * <p>Product pages are normalized to their slug: {@code /en-US/p/home-19212a}, {@code /p/home-19212a?lang=en-US} and
 * {@code www.epicgames.com/store/de/p/home-19212a} are the same product page (the store itself redirects the
 * locale form to the {@code ?lang=} form). Locale and tracking parameters never carry identity; the slug does,
 * including edition and platform suffixes such as {@code home-android-9ba07f}.
 *
 * <p>Checkout links repeat the {@code offers} parameter once per offer, each {@code 1-<namespace>-<offerId>}. They
 * are built only from catalog pairs taken from official evidence, never parsed from an imported URL.
 */
final class EpicLinks {
    private EpicLinks() {}

    static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2}(?:-[A-Za-z]{2,4})?");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Discord rejects link buttons whose URL is longer than this. */
    static final int MAX_BUTTON_URL = 512;
    static final URI CHECKOUT = URI.create("https://store.epicgames.com/purchase");

    /** The product slug of an Epic store product page, or empty for anything else (including checkout links). */
    static Optional<String> productSlug(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || uri.getHost() == null || uri.getRawPath() == null) return Optional.empty();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        for (String part : uri.getRawPath().split("/")) if (!part.isEmpty()) parts.add(part);
        if (host.equals("www.epicgames.com")) {
            if (parts.isEmpty() || !parts.getFirst().equals("store")) return Optional.empty();
            parts.removeFirst();
        } else if (!host.equals("store.epicgames.com")) return Optional.empty();
        if (parts.size() == 3 && LOCALE.matcher(parts.getFirst()).matches()) parts.removeFirst();
        if (parts.size() != 2 || !parts.get(0).equals("p")) return Optional.empty();
        String slug = parts.get(1).toLowerCase(Locale.ROOT);
        return slug.length() <= 200 && SLUG.matcher(slug).matches() ? Optional.of(slug) : Optional.empty();
    }

    /** The official product page for a validated slug. */
    static URI productPage(String slug) {
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("Invalid Epic product slug");
        return URI.create("https://store.epicgames.com/en-US/p/" + slug);
    }

    /** An exact catalog offer: namespace (sandbox) plus catalog offer ID, both opaque identifiers. */
    record Offer(String namespace, String offerId) {
        Offer {
            if (namespace == null || offerId == null || !ID.matcher(namespace).matches() || !ID.matcher(offerId).matches())
                throw new IllegalArgumentException("Invalid Epic catalog identity");
        }
    }

    /**
     * One checkout URL for the given offers, in order, duplicates (same namespace and offer) removed. Empty for no
     * offers, or when the URL would not fit in a Discord link button: never truncated.
     */
    static Optional<URI> checkout(List<Offer> offers) {
        var unique = new LinkedHashSet<>(offers);
        if (unique.isEmpty()) return Optional.empty();
        var query = new StringJoiner("&");
        for (Offer offer : unique) query.add("offers=1-" + offer.namespace() + "-" + offer.offerId());
        String url = CHECKOUT + "?" + query;
        return url.length() <= MAX_BUTTON_URL ? Optional.of(URI.create(url)) : Optional.empty();
    }
}
