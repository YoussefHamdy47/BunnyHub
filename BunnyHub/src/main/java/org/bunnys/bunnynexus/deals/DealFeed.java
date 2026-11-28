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
    static final String XBOX = "emerald.xboxservices.com", PLAYSTATION_API = "web.np.playstation.com",
            PLAYSTATION_STORE = "store.playstation.com", NINTENDO = "www.nintendo.com";
    static final Set<String> HOSTS = Set.of(STEAM_STORE, STEAM_API, GOG_CATALOG, XBOX, PLAYSTATION_API, PLAYSTATION_STORE, NINTENDO);

    /** Xbox: console games on sale, most popular first, 25 a page. */
    static final String XBOX_BROWSE = "https://" + XBOX + "/xboxcomfd/browse?locale=en-US";
    static final String XBOX_FILTERS = java.util.Base64.getEncoder().encodeToString("""
            {"orderby":{"id":"orderby","choices":[{"id":"MostPopular desc"}]},"Price":{"id":"Price","choices":[{"id":"OnSale"}]},\
            "PlayWith":{"id":"PlayWith","choices":[{"id":"XboxSeriesX|S"},{"id":"XboxOne"}]}}""".getBytes(StandardCharsets.UTF_8));
    /** Pages read per refresh: the 50 most popular console games on sale. */
    static final int XBOX_PAGES = 2;

    /** PlayStation Store "All Deals": full games, best selling first, one page of 100. */
    static final String PLAYSTATION_DEALS = "https://" + PLAYSTATION_API + "/api/graphql/v1/op?operationName=categoryGridRetrieve&variables="
            + URLEncoder.encode("""
            {"id":"3f772501-f6f8-49b7-abac-874a88ca4897","pageArgs":{"size":100,"offset":0},"sortBy":{"name":"sales30","isAscending":false},\
            "filterBy":["storeDisplayClassification:FULL_GAME"],"facetOptions":[]}""", StandardCharsets.UTF_8)
            + "&extensions=" + URLEncoder.encode("""
            {"persistedQuery":{"version":1,"sha256Hash":"4ce7d410a4db2c8b635a48c1dcec375906ff63b19dadd87e073f8fd0c0481d35"}}""", StandardCharsets.UTF_8);
    static final java.util.regex.Pattern PLAYSTATION_ID = java.util.regex.Pattern.compile("[A-Z]{2}[0-9]{4}-[A-Z]{4}[0-9]{5}_00-[A-Z0-9]{16}");

    /** Nintendo eShop's sales page; its page data lists the featured deals. */
    static final String NINTENDO_DEALS = "https://" + NINTENDO + "/us/store/sales-and-deals/";
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

    /** One page of Xbox console games on sale; {@code next} is the previous page's continuation token. */
    Fetch xbox(Optional<String> next) {
        String body = "{\"Filters\":\"" + XBOX_FILTERS + "\",\"ReturnFilters\":false,\"ChannelKeyToBeUsedInResponse\":\"BROWSE_CHANNELID=_FILTERS=\","
                + next.filter(t -> t.matches("[A-Za-z0-9+/=]{1,8000}")).map(t -> "\"EncodedCT\":\"" + t + "\",").orElse("") + "\"ChannelId\":\"\"}";
        // MS-CV is the store's request trace ID, which it requires; any value is accepted.
        var request = new Request.Builder().url(XBOX_BROWSE).header("Accept", "application/json").header("User-Agent", userAgent)
                .header("x-ms-api-version", "1.1").header("MS-CV", "BunnyNexusDeals.0")
                .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.get("application/json"))).build();
        return send(request, DealIntake.MAX_BYTES);
    }

    Fetch playstation() {
        return send(new Request.Builder().url(PLAYSTATION_DEALS).header("Accept", "application/json").header("User-Agent", userAgent)
                .header("x-psn-store-locale-override", "en-US").get().build(), DealIntake.MAX_BYTES);
    }

    /** A PlayStation game's store page, for its star rating and sale end. */
    Fetch playstationGame(String productId) {
        if (!PLAYSTATION_ID.matcher(productId).matches()) throw new IllegalArgumentException("not a PlayStation product");
        return send(new Request.Builder().url("https://" + PLAYSTATION_STORE + "/en-us/product/" + productId).header("User-Agent", userAgent)
                .get().build(), DealIntake.MAX_BYTES);
    }

    Fetch nintendo() {
        return send(new Request.Builder().url(NINTENDO_DEALS).header("User-Agent", userAgent).get().build(), DealConsoleIntake.MAX_NINTENDO_BYTES);
    }

    private Fetch get(String url) {
        return send(new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", userAgent).get().build(),
                DealIntake.MAX_BYTES);
    }

    private Fetch send(Request request, int maxBytes) {
        try {
            return calls.execute(client, request, response -> read(response, maxBytes));
        } catch (IOException failure) {
            return new Failed("network: " + failure.getClass().getSimpleName(), Optional.empty());
        }
    }

    Fetch read(Response response) throws IOException { return read(response, DealIntake.MAX_BYTES); }

    Fetch read(Response response, int maxBytes) throws IOException {
        if (response.code() == 429)
            return new Failed("HTTP 429", GamerPowerFeed.retryAfter(response.header("Retry-After"), clock.instant()));
        if (response.code() != 200) return new Failed("HTTP " + response.code(), Optional.empty());
        byte[] body = response.body().byteStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) return new Failed("response too large", Optional.empty());
        return new Ok(body);
    }

    @Override public void close() { calls.close(); }
}
