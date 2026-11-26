package org.bunnys.bunnynexus.freebies;

import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** An unchanged feed costs a 304, and only after the caller committed what it read. No sockets: a fake server. */
class ConditionalFetchTest {
    final List<String> conditions = new ArrayList<>();
    String etag = "\"v1\"";
    final URI url = URI.create("https://feed.example/feed");

    OkHttpClient fakeServer() {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String sent = request.header("If-None-Match");
            conditions.add(String.valueOf(sent));
            var response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1);
            if (etag.equals(sent)) return response.code(304).message("Not Modified").body(ResponseBody.create(new byte[0], null)).build();
            return response.code(200).message("OK").header("ETag", etag)
                    .body(ResponseBody.create(("body " + etag).getBytes(), MediaType.get("text/plain"))).build();
        }).build();
    }

    @Test void unchangedPagesAreSkippedOnlyAfterACommit() throws Exception {
        try (var calls = new FreebieHttp.Calls()) {
            var fetch = new ConditionalFetch(fakeServer(), calls, "test", 1024);
            assertEquals("body \"v1\"", new String(fetch.ifChanged(url).orElseThrow()));
            fetch.rollback();
            assertTrue(fetch.ifChanged(url).isPresent(), "not stored: read again");
            fetch.commit();
            assertEquals(Optional.empty(), fetch.ifChanged(url), "unchanged: 304, no body");
            etag = "\"v2\"";
            assertEquals("body \"v2\"", new String(fetch.ifChanged(url).orElseThrow()));
            assertEquals(List.of("null", "null", "\"v1\"", "\"v1\""), conditions);
        }
    }

    @Test void tooLargeAndFailedResponsesAreErrors() {
        var fetch = new ConditionalFetch(fakeServer(), new FreebieHttp.Calls(), "test", 4);
        assertThrows(java.io.IOException.class, () -> fetch.ifChanged(url), "over the size cap");
    }
}
