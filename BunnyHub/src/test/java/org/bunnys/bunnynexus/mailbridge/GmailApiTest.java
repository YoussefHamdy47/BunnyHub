package org.bunnys.bunnynexus.mailbridge;

import okhttp3.*;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException.Kind;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Google is faked with an interceptor, so these run offline and see every request the client makes. */
class GmailApiTest {
    final List<Request> requests = new ArrayList<>();
    final MailBridgeTest.MutableClock clock = new MailBridgeTest.MutableClock(Instant.parse("2026-10-20T08:00:00Z"));

    GmailApi api(Function<Request, Response.Builder> google) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            requests.add(request);
            return google.apply(request).request(request).protocol(Protocol.HTTP_1_1).message("x").build();
        }).build();
        return new GmailApi("client-id", "client-secret", "refresh-token", client, clock);
    }

    static Response.Builder json(int code, String body) {
        return new Response.Builder().code(code).body(ResponseBody.create(body, MediaType.get("application/json")));
    }

    static boolean isToken(Request request) { return request.url().host().equals("oauth2.googleapis.com"); }

    static final String TOKEN = "{\"access_token\":\"ya29.token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}";

    @Test void searchesWithTheQueryAndFollowsPages() throws Exception {
        var gmail = api(request -> {
            if (isToken(request)) return json(200, TOKEN);
            return request.url().queryParameter("pageToken") == null
                    ? json(200, "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t\"},{\"id\":\"m2\",\"threadId\":\"t\"}],\"nextPageToken\":\"p2\"}")
                    : json(200, "{\"messages\":[{\"id\":\"m3\",\"threadId\":\"t\"}]}");
        });
        assertEquals(List.of("m1", "m2", "m3"), gmail.search("from:openai.com after:1", 10));
        Request first = requests.get(1);
        assertEquals("from:openai.com after:1", first.url().queryParameter("q"));
        assertEquals("Bearer ya29.token", first.header("Authorization"));
        assertEquals(1, requests.stream().filter(GmailApiTest::isToken).count(), "access token reused");
    }

    @Test void onlyReadsNeverChangesTheMailbox() throws Exception {
        var gmail = api(request -> isToken(request) ? json(200, TOKEN) : json(200, "{\"id\":\"m1\",\"payload\":{}}"));
        gmail.fetch("m1");
        gmail.search("x", 5);
        requests.stream().filter(r -> !isToken(r)).forEach(r -> {
            assertEquals("GET", r.method());
            assertFalse(r.url().encodedPath().matches(".*/(modify|trash|untrash|batchModify|batchDelete|send)$"));
        });
        assertEquals("full", requests.get(1).url().queryParameter("format"));
        assertThrows(MailSourceException.class, () -> gmail.fetch("../../users/me/settings"));
    }

    @Test void refreshesTheAccessTokenBeforeItExpiresAndAfterA401() throws Exception {
        int[] gmailCalls = {0};
        var gmail = api(request -> {
            if (isToken(request)) return json(200, TOKEN);
            return ++gmailCalls[0] == 2 ? json(401, "{}") : json(200, "{}");
        });
        gmail.search("x", 1);
        gmail.search("x", 1); // 401 → refresh → retry
        assertEquals(2, requests.stream().filter(GmailApiTest::isToken).count());
        clock.advance(Duration.ofMinutes(59));
        gmail.search("x", 1);
        assertEquals(3, requests.stream().filter(GmailApiTest::isToken).count(), "refreshed a minute before expiry");
    }

    @Test void revokedOrExpiredRefreshTokensAreAuthProblems() {
        var gmail = api(request -> json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));
        var failure = assertThrows(MailSourceException.class, () -> gmail.search("x", 1));
        assertEquals(Kind.AUTH, failure.kind());
        assertFalse(failure.getMessage().contains("refresh-token") || failure.getMessage().contains("client-secret"));
    }

    @Test void wrongScopeIsAnAuthProblemButRateLimitsAreTransient() {
        var scope = api(request -> isToken(request) ? json(200, TOKEN)
                : json(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"));
        assertEquals(Kind.AUTH, assertThrows(MailSourceException.class, () -> scope.search("x", 1)).kind());

        var limited = api(request -> isToken(request) ? json(200, TOKEN)
                : json(429, "{}").header("Retry-After", "120"));
        var failure = assertThrows(MailSourceException.class, () -> limited.search("x", 1));
        assertEquals(Kind.TRANSIENT, failure.kind());
        assertEquals(Optional.of(clock.instant().plusSeconds(120)), failure.retryAfter());

        var quota = api(request -> isToken(request) ? json(200, TOKEN)
                : json(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"));
        assertEquals(Kind.TRANSIENT, assertThrows(MailSourceException.class, () -> quota.search("x", 1)).kind());
    }

    @Test void outagesAndMissingMessages() {
        var down = api(request -> isToken(request) ? json(200, TOKEN) : json(503, "{}"));
        assertEquals(Kind.TRANSIENT, assertThrows(MailSourceException.class, () -> down.search("x", 1)).kind());
        var gone = api(request -> isToken(request) ? json(200, TOKEN) : json(404, "{}"));
        assertEquals(Kind.PERMANENT, assertThrows(MailSourceException.class, () -> gone.fetch("m1")).kind());
        var garbage = api(request -> isToken(request) ? json(200, TOKEN) : json(200, "<html>not json"));
        assertEquals(Kind.TRANSIENT, assertThrows(MailSourceException.class, () -> garbage.search("x", 1)).kind());
    }
}
