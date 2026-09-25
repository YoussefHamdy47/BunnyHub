package org.bunnys.bunnynexus.alerts.adapters.providers;

import java.io.IOException;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;

/** Inactive synchronous source owner. Invoke on a dedicated discovery worker, never a gateway/command thread. */
public final class GamerPowerFetcher implements AutoCloseable {
    public enum State { FETCHED, FAILED, BUSY, THROTTLED, PAUSED, CLOSED }
    public enum Failure { NONE, NETWORK, DEADLINE, HTTP_STATUS, MEDIA_TYPE, ENCODING, TOO_LARGE, INVALID_FEED }
    public record Result(State state, Failure failure, int httpStatus, Optional<GamerPowerIntake.Batch> batch) {
        public Result { Objects.requireNonNull(state); Objects.requireNonNull(failure); Objects.requireNonNull(batch); }
    }
    public record Budgets(Duration timeout, Duration minimumInterval) {
        public Budgets {
            Objects.requireNonNull(timeout); Objects.requireNonNull(minimumInterval);
            if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0
                    || minimumInterval.compareTo(Duration.ofSeconds(1)) < 0 || minimumInterval.compareTo(Duration.ofHours(1)) > 0)
                throw new IllegalArgumentException("Explicit finite transport and pacing budgets required.");
        }
    }
    private final Call.Factory calls;
    private final Runnable cleanup;
    private final Budgets budgets;
    private final Clock clock;
    private final LongSupplier ticker;
    private final GamerPowerIntake intake = new GamerPowerIntake();
    private boolean busy, closed, paused, cooling;
    private long completedAt, cooldown;
    private int failures;
    private Call active;
    public Duration maximumFetchTime() { return budgets.timeout(); }

    public GamerPowerFetcher(List<InetAddress> approvedAddresses, Budgets budgets) {
        this(client(approvedAddresses, budgets), budgets);
    }
    private GamerPowerFetcher(OkHttpClient client, Budgets budgets) {
        this(client, () -> client.connectionPool().evictAll(), budgets, Clock.systemUTC(), System::nanoTime);
    }
    // Package-private seam: production callers cannot substitute an unsafe resolver or redirect policy.
    GamerPowerFetcher(Call.Factory calls, Runnable cleanup, Budgets budgets, Clock clock, LongSupplier ticker) {
        this.calls = Objects.requireNonNull(calls); this.cleanup = Objects.requireNonNull(cleanup);
        this.budgets = Objects.requireNonNull(budgets); this.clock = Objects.requireNonNull(clock); this.ticker = Objects.requireNonNull(ticker);
    }
    static OkHttpClient client(List<InetAddress> pins, Budgets budgets) {
        Objects.requireNonNull(budgets);
        return new OkHttpClient.Builder().dns(ProviderAddresses.gamerPower(pins)).proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addNetworkInterceptor(chain -> {
                    var once = chain.request().tag(AtomicBoolean.class);
                    if (once == null || !once.compareAndSet(false, true)) throw new IOException("Provider exchange already attempted.");
                    return chain.proceed(chain.request());
                })
                .callTimeout(budgets.timeout()).connectTimeout(budgets.timeout()).readTimeout(budgets.timeout())
                .writeTimeout(budgets.timeout()).build();
    }
    public Result fetch() {
        synchronized (this) {
            if (closed) return result(State.CLOSED, Failure.NONE, 0);
            if (paused) return result(State.PAUSED, Failure.NONE, 0);
            if (busy) return result(State.BUSY, Failure.NONE, 0);
            if (cooling && ticker.getAsLong() - completedAt < cooldown) return result(State.THROTTLED, Failure.NONE, 0);
            busy = true;
        }
        boolean success = false;
        long started = ticker.getAsLong();
        try {
            var request = new Request.Builder().url(GamerPowerIntake.ENDPOINT.toString())
                    .tag(AtomicBoolean.class, new AtomicBoolean())
                    .header("Accept", "application/json").header("Accept-Encoding", "identity")
                    .header("User-Agent", "BunnyNexus-Discovery/1.0").get().build();
            Call call = calls.newCall(request);
            synchronized (this) {
                active = call;
                if (closed) { call.cancel(); return result(State.CLOSED, Failure.NONE, 0); }
            }
            try (Response response = call.execute()) {
                int status = response.code();
                // Do not automatically retry rate limits or access denials, regardless of Retry-After format.
                if (status == 429 || status == 401 || status == 403) synchronized (this) { paused = true; }
                if (status != 200 && status != 201) return result(State.FAILED, Failure.HTTP_STATUS, status);
                String encoding = response.header("Content-Encoding", "identity");
                if (!encoding.equalsIgnoreCase("identity")) return result(State.FAILED, Failure.ENCODING, status);
                var body = response.body();
                var type = body == null ? null : body.contentType();
                if (type == null || !type.type().equals("application") || !type.subtype().equals("json"))
                    return result(State.FAILED, Failure.MEDIA_TYPE, status);
                if (body.contentLength() > GamerPowerIntake.MAX_BYTES) return result(State.FAILED, Failure.TOO_LARGE, status);
                byte[] bytes = body.byteStream().readNBytes(GamerPowerIntake.MAX_BYTES + 1);
                if (bytes.length > GamerPowerIntake.MAX_BYTES) return result(State.FAILED, Failure.TOO_LARGE, status);
                if (ticker.getAsLong() - started >= budgets.timeout().toNanos()) return result(State.FAILED, Failure.DEADLINE, status);
                var batch = intake.decode(status, bytes, clock.instant());
                if (ticker.getAsLong() - started >= budgets.timeout().toNanos()) return result(State.FAILED, Failure.DEADLINE, status);
                synchronized (this) { if (closed) return result(State.CLOSED, Failure.NONE, status); }
                success = batch.state() == GamerPowerIntake.State.CANDIDATES || batch.state() == GamerPowerIntake.State.EMPTY;
                return new Result(success ? State.FETCHED : State.FAILED, success ? Failure.NONE : Failure.INVALID_FEED, status, Optional.of(batch));
            }
        } catch (IOException failure) {
            return result(State.FAILED, ticker.getAsLong() - started >= budgets.timeout().toNanos() ? Failure.DEADLINE : Failure.NETWORK, 0);
        } finally {
            synchronized (this) {
                active = null; busy = false;
                failures = success ? 0 : Math.min(6, failures + 1);
                cooldown = Math.min(Duration.ofHours(1).toNanos(), budgets.minimumInterval().toNanos() * (1L << failures));
                completedAt = ticker.getAsLong(); cooling = true;
                if (closed) cleanup.run();
            }
        }
    }
    @Override public synchronized void close() {
        closed = true;
        if (active != null) active.cancel();
        cleanup.run();
    }
    private static Result result(State state, Failure failure, int status) {
        return new Result(state, failure, status, Optional.empty());
    }
}
