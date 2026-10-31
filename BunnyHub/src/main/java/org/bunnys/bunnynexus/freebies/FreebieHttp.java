package org.bunnys.bunnynexus.freebies;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One OkHttp base for every free-game provider, so the feeds share one connection pool and dispatcher instead of
 * each keeping its own. Each feed derives its client with {@link #client}, which always sets that feed's DNS guard,
 * no proxy, no redirects and its timeouts; nothing on the base is trusted for those.
 */
final class FreebieHttp {
    private static final OkHttpClient BASE = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).build();

    private FreebieHttp() {}

    static OkHttpClient base() { return BASE; }

    /** A client sharing the base's pool and dispatcher, with {@code dns} as its only resolver. */
    static OkHttpClient client(Dns dns, Duration timeout) {
        return BASE.newBuilder().dns(dns).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .callTimeout(timeout).connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).build();
    }

    interface Reader<T> { T read(Response response) throws IOException; }

    /**
     * The calls one feed has open. The pool and dispatcher are shared, so closing a feed cancels only its own calls
     * (including a body still being read) and leaves the other feeds working.
     */
    static final class Calls implements AutoCloseable {
        private final Set<Call> open = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        <T> T execute(OkHttpClient client, Request request, Reader<T> reader) throws IOException {
            Call call = client.newCall(request);
            open.add(call);
            try {
                if (closed) call.cancel();
                try (Response response = call.execute()) { return reader.read(response); }
            } finally { open.remove(call); }
        }

        int openCalls() { return open.size(); }

        @Override public void close() {
            closed = true;
            open.forEach(Call::cancel);
            BASE.connectionPool().evictAll(); // Only idle connections; calls of other feeds keep theirs.
        }
    }
}
