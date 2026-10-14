package org.bunnys.bunnynexus.freebies;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a GamerPower claim link ({@code https://www.gamerpower.com/open/<slug>}) into the store page it redirects
 * to, so a claim button opens Steam, GOG, itch.io and so on directly instead of going through GamerPower.
 *
 * <p>Only GamerPower is ever contacted, with the same rules as the feed (public addresses only, no proxy, redirects
 * not followed automatically): each response's {@code Location} is read, and the first one that leaves GamerPower
 * is the answer. The store itself is never fetched. The answer is used only when it is an {@code https} link on a
 * known storefront or giveaway site; anything else (an interstitial page, an error, an unknown site) leaves the
 * original GamerPower link in place, so a card always has a working claim button.
 */
public final class GamerPowerLinks {
    /** Sites a claim button may open directly; subdomains count. Anything else keeps the GamerPower link. */
    static final List<String> ALLOWED_HOSTS = List.of(
            "steampowered.com", "steamcommunity.com", "epicgames.com", "gog.com", "ubisoft.com", "ea.com",
            "battle.net", "blizzard.com", "itch.io", "playstation.com", "xbox.com", "microsoft.com", "nintendo.com",
            "play.google.com", "apps.apple.com", "amazon.com", "indiegala.com", "fanatical.com", "humblebundle.com",
            "alienwarearena.com");
    static final int MAX_HOPS = 4;
    /** Discord's limit for a link button's URL. */
    static final int MAX_URL = 512;
    private static final String GAMERPOWER = "www.gamerpower.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** One request to GamerPower: the status code and the {@code Location} header, if any. */
    record Hop(int status, Optional<String> location) {}

    interface Transport { Hop get(URI uri) throws IOException; }

    private final Transport transport;

    public GamerPowerLinks() { this(okHttp()); }
    GamerPowerLinks(Transport transport) { this.transport = transport; }

    public static boolean isGamerPower(URI uri) { return GAMERPOWER.equals(uri.getHost()); }

    /** The store link behind {@code claim}, or empty to keep {@code claim} as it is. Blocking; never throws. */
    public Optional<URI> resolve(URI claim) {
        if (!isGamerPower(claim)) return Optional.empty();
        URI current = claim;
        try {
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                Hop response = transport.get(current);
                if (response.status() < 300 || response.status() >= 400 || response.location().isEmpty()) return Optional.empty();
                URI next = current.resolve(response.location().get().strip());
                if (!isGamerPower(next)) return acceptable(next);
                if (!"https".equals(next.getScheme())) return Optional.empty();
                current = next;
            }
        } catch (IOException | IllegalArgumentException failure) {
            BunnyLog.warning("[Freebies] Claim link not resolved, keeping GamerPower's | " + FailureDiagnostics.describe(failure));
        }
        return Optional.empty();
    }

    /** {@code uri} when it is safe to put on a button: https, a plain host on the allowlist, and short enough. */
    static Optional<URI> acceptable(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1) return Optional.empty();
        String host = uri.getHost();
        if (host == null) return Optional.empty();
        host = host.toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String domain : ALLOWED_HOSTS) if (host.equals(domain) || host.endsWith("." + domain)) { allowed = true; break; }
        if (!allowed || uri.toASCIIString().length() > MAX_URL) return Optional.empty();
        return Optional.of(uri);
    }

    private static Transport okHttp() {
        OkHttpClient client = new OkHttpClient.Builder().dns(GamerPowerLinks::gamerPowerOnly).proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(TIMEOUT).connectTimeout(TIMEOUT).readTimeout(TIMEOUT).writeTimeout(TIMEOUT).build();
        return uri -> {
            var request = new Request.Builder().url(uri.toString()).header("User-Agent", "BunnyNexus-Freebies/2.0").get().build();
            try (Response response = client.newCall(request).execute()) {
                return new Hop(response.code(), Optional.ofNullable(response.header("Location")));
            }
        };
    }

    private static List<InetAddress> gamerPowerOnly(String host) throws UnknownHostException {
        if (!GAMERPOWER.equals(host)) throw new UnknownHostException("Only GamerPower is contacted to resolve claim links.");
        var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
        if (addresses.isEmpty()) throw new UnknownHostException("Provider resolved only to non-public addresses.");
        return addresses;
    }
}
