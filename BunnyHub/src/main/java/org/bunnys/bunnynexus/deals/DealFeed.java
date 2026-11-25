package org.bunnys.bunnynexus.deals;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bunnys.bunnynexus.freebies.FreebieHttp;
import org.bunnys.bunnynexus.freebies.GamerPowerFeed;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The stores' own endpoints (docs/deals/01-source.md): Steam's store search and item details, and GOG's catalog.
 * Same guard as the free-game feeds: fixed public hosts, no redirects or proxies, bounded body and timeouts.
 * Blocking: call from the deals thread only.
 */
final class DealFeed implements AutoCloseable {
    static final String STEAM_STORE = "store.steampowered.com", STEAM_API = "api.steampowered.com", GOG_CATALOG = "catalog.gog.com";
    static final Set<String> HOSTS = Set.of(STEAM_STORE, STEAM_API, GOG_CATALOG);
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Steam's two orders: what sells, and what players rate best. */
    static final List<String> STEAM_ORDERS = List.of("filter=topsellers", "sort_by=Reviews_DESC");
    static final String GOG = "https://" + GOG_CATALOG + "/v1/catalog?limit=100&order=desc:trending&discounted=eq:true"
            + "&productType=in:game,pack&countryCode=US&currencyCode=USD&locale=en-US";

    sealed interface Fetch permits Ok, Failed {}
    record Ok(byte[] body) implements Fetch {}
    record Failed(String reason, Optional<Duration> retryAfter) implements Fetch {}

    private final Clock clock;
    private final String userAgent;
    private final OkHttpClient client;
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();

    DealFeed(Clock clock, String userAgent) {
        this.clock = clock;
        this.userAgent = userAgent;
        client = FreebieHttp.client(DealFeed::lookup, TIMEOUT);
    }

    /** Only the stores' own hosts resolve, and only to public addresses. */
    static List<InetAddress> lookup(String host) throws UnknownHostException {
        if (!HOSTS.contains(host)) throw new UnknownHostException("Unapproved deals host");
        var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
        if (addresses.isEmpty()) throw new UnknownHostException("No public deals address");
        return addresses;
    }

    static String steamSearchUrl(String order) {
        if (!STEAM_ORDERS.contains(order)) throw new IllegalArgumentException("unknown Steam order");
        return "https://" + STEAM_STORE + "/search/results/?specials=1&" + order + "&infinite=1&count=100&cc=us&l=english";
    }

    /** Price, discount, reviews and sale end for up to 100 apps, US store. */
    static String steamItemsUrl(List<Long> appIds) {
        if (appIds.isEmpty() || appIds.size() > DealIntake.MAX_ROWS) throw new IllegalArgumentException("1 to 100 apps");
        var ids = new StringJoiner(",");
        for (long id : appIds) ids.add("{\"appid\":" + id + "}");
        String input = "{\"ids\":[" + ids + "],\"context\":{\"language\":\"english\",\"country_code\":\"US\"},"
                // Reviews only: name, type and best_purchase_option (price, discount, sale end) come with every item;
                // basic info and all purchase options were 85% of the body and are never read.
                + "\"data_request\":{\"include_reviews\":true}}";
        return "https://" + STEAM_API + "/IStoreBrowseService/GetItems/v1/?input_json=" + URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    Fetch steamSearch(String order) { return get(steamSearchUrl(order)); }
    Fetch steamItems(List<Long> appIds) { return get(steamItemsUrl(appIds)); }
    Fetch gog() { return get(GOG); }

    private Fetch get(String url) {
        var request = new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", userAgent).get().build();
        try {
            return calls.execute(client, request, this::read);
        } catch (IOException failure) {
            return new Failed("network: " + failure.getClass().getSimpleName(), Optional.empty());
        }
    }

    Fetch read(Response response) throws IOException {
        if (response.code() == 429)
            return new Failed("HTTP 429", GamerPowerFeed.retryAfter(response.header("Retry-After"), clock.instant()));
        if (response.code() != 200) return new Failed("HTTP " + response.code(), Optional.empty());
        byte[] body = response.body().byteStream().readNBytes(DealIntake.MAX_BYTES + 1);
        if (body.length > DealIntake.MAX_BYTES) return new Failed("response too large", Optional.empty());
        return new Ok(body);
    }

    @Override public void close() { calls.close(); }
}
