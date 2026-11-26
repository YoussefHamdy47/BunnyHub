package org.bunnys.bunnynexus.freebies;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GET with "only if changed" ({@code If-None-Match} / {@code If-Modified-Since}): a feed that has not changed answers
 * 304 with no body, so an hourly check of an unchanged 128 KB feed costs a few hundred bytes. Only the validators are
 * kept (two short strings per URL), never a body. A new validator takes effect only on {@link #commit()}, which the
 * caller runs once it has stored what it read: if storing fails, the next check downloads the page again. Not
 * thread-safe: one thread uses it.
 */
public final class ConditionalFetch {
    private record Validators(String etag, String lastModified) {}

    private final OkHttpClient client;
    private final FreebieHttp.Calls calls;
    private final String userAgent;
    private final int maxBytes;
    private final Map<String, Validators> saved = new HashMap<>(), pending = new HashMap<>();

    public ConditionalFetch(OkHttpClient client, FreebieHttp.Calls calls, String userAgent, int maxBytes) {
        this.client = client; this.calls = calls; this.userAgent = userAgent; this.maxBytes = maxBytes;
    }

    /** The body, or empty when the server says it has not changed since the last committed read. */
    public Optional<byte[]> ifChanged(URI url) throws IOException {
        String key = url.toString();
        var request = new Request.Builder().url(key).header("User-Agent", userAgent).get();
        var known = saved.get(key);
        if (known != null && known.etag() != null) request.header("If-None-Match", known.etag());
        if (known != null && known.lastModified() != null) request.header("If-Modified-Since", known.lastModified());
        return calls.execute(client, request.build(), response -> {
            if (response.code() == 304 && known != null) return Optional.empty();
            if (response.code() != 200) throw new IOException("HTTP " + response.code() + " from " + url.getHost());
            byte[] body = response.body().byteStream().readNBytes(maxBytes + 1);
            if (body.length > maxBytes) throw new IOException("response too large");
            String etag = response.header("ETag"), modified = response.header("Last-Modified");
            if (etag != null || modified != null) pending.put(key, new Validators(etag, modified));
            else pending.remove(key);
            return Optional.of(body);
        });
    }

    /** A plain GET, never answered from validators (for pages that are read once, like a store price). */
    public byte[] get(URI url) throws IOException {
        var request = new Request.Builder().url(url.toString()).header("User-Agent", userAgent).get().build();
        return calls.execute(client, request, response -> {
            if (response.code() != 200) throw new IOException("HTTP " + response.code() + " from " + url.getHost());
            byte[] body = response.body().byteStream().readNBytes(maxBytes + 1);
            if (body.length > maxBytes) throw new IOException("response too large");
            return body;
        });
    }

    /** What was read is stored: later checks may skip unchanged pages. */
    public void commit() {
        saved.putAll(pending);
        pending.clear();
    }

    /** What was read could not be stored: forget it, so the next check downloads it again. */
    public void rollback() { pending.clear(); }
}
