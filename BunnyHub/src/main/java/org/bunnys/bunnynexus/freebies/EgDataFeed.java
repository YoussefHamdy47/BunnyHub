package org.bunnys.bunnynexus.freebies;

import okhttp3.*;
import java.io.IOException;
import java.net.*;
import java.time.*;
import java.util.List;

/** Fixed public EGData endpoint, bounded body and timeouts; no redirects, proxies or private DNS answers. */
class EgDataFeed implements AutoCloseable {
    private final Clock clock;
    private final OkHttpClient client;

    EgDataFeed(Clock clock) {
        this.clock = clock;
        client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .dns(host -> {
                    if (!EgDataIntake.HOST.equals(host)) throw new UnknownHostException("Unapproved provider host");
                    var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
                    if (addresses.isEmpty()) throw new UnknownHostException("No public provider address");
                    return addresses;
                }).callTimeout(Duration.ofSeconds(20)).connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(20)).writeTimeout(Duration.ofSeconds(20)).build();
    }

    /** The current list; any failure is a FAILED result (unknown), never an empty "no restrictions" answer. */
    EgDataIntake.Result poll() {
        var request = new Request.Builder().url(EgDataIntake.ENDPOINT).header("Accept", "application/json")
                .header("User-Agent", "BunnyNexus-Freebies/2.0").get().build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 200) return new EgDataIntake.Result(EgDataIntake.State.FAILED, List.of(), "HTTP " + response.code());
            return new EgDataIntake().decode(response.body().byteStream().readNBytes(EgDataIntake.MAX_BYTES + 1), clock.instant());
        } catch (IOException failure) {
            return new EgDataIntake.Result(EgDataIntake.State.FAILED, List.of(), "network: " + failure.getClass().getSimpleName());
        }
    }

    @Override public void close() {
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
}
