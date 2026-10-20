package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import okhttp3.*;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gmail REST API over plain HTTPS, authorised with a stored OAuth refresh token and the read-only scope.
 * The access token lives only in memory and is refreshed a minute before it expires. Only GETs are ever sent to
 * Gmail, so the mailbox is never changed: nothing is deleted, archived, labelled or marked as read.
 */
public final class GmailApi implements MailSource {
    static final HttpUrl TOKEN_URL = HttpUrl.get("https://oauth2.googleapis.com/token");
    static final HttpUrl MESSAGES_URL = HttpUrl.get("https://gmail.googleapis.com/gmail/v1/users/me/messages");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final String clientId, clientSecret, refreshToken;
    private final OkHttpClient client;
    private final Clock clock;
    private String accessToken;
    private Instant accessExpires = Instant.EPOCH;

    public GmailApi(MailBridgeConfig config, Clock clock) {
        this(config.clientId(), config.clientSecret(), config.refreshToken(), new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).callTimeout(Duration.ofSeconds(30))
                .connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofSeconds(20)).build(), clock);
    }

    GmailApi(String clientId, String clientSecret, String refreshToken, OkHttpClient client, Clock clock) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.client = client;
        this.clock = clock;
    }

    @Override public List<String> search(String query, int max) throws MailSourceException {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            HttpUrl.Builder url = MESSAGES_URL.newBuilder().addQueryParameter("q", query)
                    .addQueryParameter("maxResults", String.valueOf(Math.min(100, max - ids.size())));
            if (pageToken != null) url.addQueryParameter("pageToken", pageToken);
            DataObject page = get(url.build());
            DataArray messages = page.optArray("messages").orElse(DataArray.empty());
            for (int i = 0; i < messages.length() && ids.size() < max; i++) {
                String id = messages.getObject(i).getString("id", "");
                if (!id.isEmpty()) ids.add(id);
            }
            pageToken = page.getString("nextPageToken", null);
        } while (pageToken != null && ids.size() < max);
        return ids;
    }

    @Override public DataObject fetch(String id) throws MailSourceException {
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new MailSourceException(Kind.PERMANENT, "invalid message id");
        return get(MESSAGES_URL.newBuilder().addPathSegment(id).addQueryParameter("format", "full").build());
    }

    private DataObject get(HttpUrl url) throws MailSourceException {
        for (int attempt = 0; ; attempt++) {
            Request request = new Request.Builder().url(url).header("Authorization", "Bearer " + token())
                    .header("Accept", "application/json").get().build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 401 && attempt == 0) {
                    // The cached access token went stale early; refresh once and retry.
                    accessExpires = Instant.EPOCH;
                    continue;
                }
                if (response.isSuccessful()) return json(response);
                throw failure("Gmail", response);
            } catch (IOException network) {
                throw new MailSourceException(Kind.TRANSIENT, "Gmail unreachable: " + network.getClass().getSimpleName());
            }
        }
    }

    private synchronized String token() throws MailSourceException {
        if (accessToken != null && clock.instant().isBefore(accessExpires)) return accessToken;
        RequestBody form = new FormBody.Builder().add("client_id", clientId).add("client_secret", clientSecret)
                .add("refresh_token", refreshToken).add("grant_type", "refresh_token").build();
        try (Response response = client.newCall(new Request.Builder().url(TOKEN_URL).post(form).build()).execute()) {
            if (!response.isSuccessful()) {
                String error = response.code() >= 400 && response.code() < 500 ? errorCode(response) : "";
                if ("invalid_grant".equals(error) || "invalid_client".equals(error) || "unauthorized_client".equals(error))
                    throw new MailSourceException(Kind.AUTH, "Google rejected the stored credentials (" + error + ")");
                throw failure("Google sign-in", response);
            }
            DataObject body = json(response);
            String token = body.getString("access_token", "");
            if (token.isEmpty()) throw new MailSourceException(Kind.TRANSIENT, "Google sign-in returned no access token");
            long seconds = Math.max(60, body.getLong("expires_in", 3600));
            accessToken = token;
            accessExpires = clock.instant().plusSeconds(seconds - 60);
            return accessToken;
        } catch (IOException network) {
            throw new MailSourceException(Kind.TRANSIENT, "Google sign-in unreachable: " + network.getClass().getSimpleName());
        }
    }

    private MailSourceException failure(String what, Response response) {
        int code = response.code();
        Optional<Instant> retryAfter = retryAfter(response.header("Retry-After"));
        if (code == 401 || code == 403 && !"rateLimitExceeded".equals(errorReason(response)) && !"userRateLimitExceeded".equals(errorReason(response)))
            return new MailSourceException(Kind.AUTH, what + " refused access (HTTP " + code + ")", retryAfter);
        if (code == 429 || code == 403 || code >= 500)
            return new MailSourceException(Kind.TRANSIENT, what + " is busy or unavailable (HTTP " + code + ")", retryAfter);
        return new MailSourceException(Kind.PERMANENT, what + " rejected the request (HTTP " + code + ")", retryAfter);
    }

    private Optional<Instant> retryAfter(String header) {
        if (header == null) return Optional.empty();
        try {
            long seconds = Long.parseLong(header.strip());
            return seconds >= 0 && seconds <= 86_400 ? Optional.of(clock.instant().plusSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException notSeconds) {
            return Optional.empty();
        }
    }

    /** Peeked, never logged: only the short machine-readable code is used. */
    private static String errorCode(Response response) {
        try {
            DataObject body = DataObject.fromJson(response.peekBody(16 * 1024).bytes());
            return body.isType("error", net.dv8tion.jda.api.utils.data.DataType.STRING) ? body.getString("error") : "";
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static String errorReason(Response response) {
        try {
            DataObject body = DataObject.fromJson(response.peekBody(16 * 1024).bytes());
            DataArray errors = body.getObject("error").getArray("errors");
            return errors.isEmpty() ? "" : errors.getObject(0).getString("reason", "");
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static DataObject json(Response response) throws MailSourceException, IOException {
        ResponseBody body = response.body();
        try (InputStream in = body.byteStream()) {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new MailSourceException(Kind.PERMANENT, "Gmail response too large");
            return DataObject.fromJson(bytes);
        } catch (RuntimeException malformed) {
            throw new MailSourceException(Kind.TRANSIENT, "Gmail returned unreadable data");
        }
    }
}
