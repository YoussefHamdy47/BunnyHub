package org.bunnys.bunnynexus.freebies;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Steam's own store as a source (see {@link SteamIntake}): two requests per check, fixed public hosts, bounded bodies
 * and timeouts; no redirects, proxies or private DNS answers. Blocking: call from the discovery thread only.
 */
final class SteamFeed implements FreebieFeed {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final Clock clock;
    private final OkHttpClient client;
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();
    private final SteamIntake intake = new SteamIntake();

    SteamFeed(Clock clock) {
        this.clock = clock;
        client = FreebieHttp.client(host -> {
            if (!SteamIntake.SEARCH_HOST.equals(host) && !SteamIntake.API_HOST.equals(host))
                throw new UnknownHostException("Unapproved provider host");
            var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
            if (addresses.isEmpty()) throw new UnknownHostException("No public provider address");
            return addresses;
        }, TIMEOUT);
    }

    @Override public Provider provider() { return Provider.STEAM; }

    @Override public Result poll() {
        try {
            var page = get(SteamIntake.SEARCH);
            if (page.failure().isPresent()) return page.failure().get();
            var search = intake.search(page.body());
            if (search.isEmpty()) return failed("invalid Steam search response", Optional.empty());
            List<Long> ids = search.get().appIds();
            if (ids.isEmpty()) return new Result(search.get().complete() ? State.COMPLETE : State.PARTIAL, List.of(), Optional.empty(),
                    search.get().complete() ? "" : "partial feed: more Steam results than one page");
            var items = get(SteamIntake.items(ids));
            if (items.failure().isPresent()) return items.failure().get();
            return intake.decode(items.body(), ids, search.get().complete(), clock.instant());
        } catch (IOException failure) {
            return failed("network: " + failure.getClass().getSimpleName(), Optional.empty());
        }
    }

    private record Fetched(byte[] body, Optional<Result> failure) {}

    private Fetched get(URI uri) throws IOException {
        var request = new Request.Builder().url(uri.toString()).header("Accept", "application/json")
                .header("User-Agent", "BunnyNexus-Freebies/2.0").get().build();
        return calls.execute(client, request, response -> {
            if (response.code() != 200)
                return new Fetched(new byte[0], Optional.of(failed("HTTP " + response.code(),
                        GamerPowerFeed.retryAfter(response.header("Retry-After"), clock.instant()))));
            byte[] body = response.body().byteStream().readNBytes(SteamIntake.MAX_BYTES + 1);
            if (body.length > SteamIntake.MAX_BYTES) return new Fetched(new byte[0], Optional.of(failed("Steam response too large", Optional.empty())));
            return new Fetched(body, Optional.empty());
        });
    }

    private static Result failed(String problem, Optional<Duration> retryAfter) {
        return new Result(State.FAILED, List.of(), retryAfter, problem);
    }

    OkHttpClient client() { return client; }

    @Override public void close() { calls.close(); }
}
