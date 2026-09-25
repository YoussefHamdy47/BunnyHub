package org.bunnys.bunnynexus.alerts.adapters.providers;

import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Offline shadow intake only. No Offer, canonical store mapping, database write or network request. */
public final class GamerPowerIntake {
    public static final URI ENDPOINT = URI.create("https://www.gamerpower.com/api/giveaways?type=game");
    public static final URI ATTRIBUTION = URI.create("https://www.gamerpower.com/");
    public static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ITEMS = 500;
    private static final Set<String> FIELDS = Set.of("id", "title", "description", "instructions", "platforms",
            "published_date", "end_date", "type", "status", "gamerpower_url", "open_giveaway_url", "image", "worth");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(16_384)
                    .maxNameLength(128).maxNumberLength(20).maxDocumentLength(MAX_BYTES).maxTokenCount(100_000).build()).build();

    public enum State { CANDIDATES, EMPTY, PARTIAL, FAILED }
    public enum Problem { NONE, HTTP_STATUS, TOO_LARGE, TOO_MANY_ITEMS, MALFORMED_RESPONSE, INVALID_ITEMS, DUPLICATE_ID }
    /** Platform labels are untrusted source metadata, never canonical stores or verified entitlements. */
    public record Candidate(String sourceItemId, String title, String description, String instructions,
                            String platforms, LocalDateTime publishedLocal, Optional<LocalDateTime> endsLocal,
                            URI sourcePage, URI claimLink, Instant fetchedAt, Optional<URI> image, Optional<String> worth) {
        public Candidate { Objects.requireNonNull(endsLocal); Objects.requireNonNull(image); Objects.requireNonNull(worth); }
        public URI attribution() { return ATTRIBUTION; }
    }
    /** EMPTY says only that this response has no rows; it never authorizes withdrawals or cursor advancement. */
    public record Batch(State state, Problem problem, List<Candidate> candidates, int rejectedItems) {
        public Batch { candidates = List.copyOf(candidates); }
    }

    public Batch decode(int httpStatus, byte[] body, Instant fetchedAt) {
        Objects.requireNonNull(body); Objects.requireNonNull(fetchedAt);
        if (body.length > MAX_BYTES) return failed(Problem.TOO_LARGE);
        // Only the documented and captured no-results envelope is accepted, never an arbitrary 201 body.
        if (httpStatus == 201) return emptyEnvelope(body);
        if (httpStatus != 200) return failed(Problem.HTTP_STATUS);
        var candidates = new ArrayList<Candidate>(); var ids = new HashSet<String>();
        int seen = 0, rejected = 0;
        try (var parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) return failed(Problem.MALFORMED_RESPONSE);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (++seen > MAX_ITEMS) return failed(Problem.TOO_MANY_ITEMS);
                if (parser.currentToken() == null) return failed(Problem.MALFORMED_RESPONSE);
                if (parser.currentToken() != JsonToken.START_OBJECT) {
                    parser.skipChildren(); rejected++; continue;
                }
                var fields = new HashMap<String, String>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) return failed(Problem.MALFORMED_RESPONSE);
                    String name = parser.currentName();
                    JsonToken token = parser.nextToken();
                    if (FIELDS.contains(name) && (token == JsonToken.VALUE_STRING || (name.equals("id") && token == JsonToken.VALUE_NUMBER_INT)))
                        fields.put(name, parser.getText());
                    else parser.skipChildren();
                }
                String sourceId = fields.get("id");
                // Identity collisions remain ambiguous even when one row fails other validation.
                if (sourceId != null && sourceId.matches("[1-9][0-9]{0,18}") && !ids.add(sourceId)) return failed(Problem.DUPLICATE_ID);
                try {
                    var candidate = candidate(fields, fetchedAt);
                    candidates.add(candidate);
                } catch (IllegalArgumentException | DateTimeException invalid) { rejected++; }
            }
            if (parser.nextToken() != null) return failed(Problem.MALFORMED_RESPONSE);
        } catch (IOException | RuntimeException invalid) {
            // Never retain/log raw parser errors: they may quote untrusted response content.
            return failed(Problem.MALFORMED_RESPONSE);
        }
        if (rejected > 0) return new Batch(State.PARTIAL, Problem.INVALID_ITEMS, candidates, rejected);
        return new Batch(candidates.isEmpty() ? State.EMPTY : State.CANDIDATES, Problem.NONE, candidates, 0);
    }

    private static Batch emptyEnvelope(byte[] body) {
        boolean status = false, message = false;
        try (var parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return failed(Problem.MALFORMED_RESPONSE);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return failed(Problem.MALFORMED_RESPONSE);
                String name = parser.currentName(); JsonToken value = parser.nextToken();
                if (name.equals("status") && value == JsonToken.VALUE_NUMBER_INT && parser.getLongValue() == 0) status = true;
                else if (name.equals("status_message") && value == JsonToken.VALUE_STRING
                        && parser.getText().equals("No active giveaways available at the moment, please try again later.")) message = true;
                else return failed(Problem.MALFORMED_RESPONSE);
            }
            if (status && message && parser.nextToken() == null) return new Batch(State.EMPTY, Problem.NONE, List.of(), 0);
        } catch (IOException | RuntimeException invalid) { /* Never expose raw provider content. */ }
        return failed(Problem.MALFORMED_RESPONSE);
    }

    private static Candidate candidate(Map<String, String> fields, Instant fetchedAt) {
        String id = field(fields, "id", 20);
        if (!id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        if (!field(fields, "type", 32).equals("Game") || !field(fields, "status", 32).equals("Active"))
            throw new IllegalArgumentException();
        LocalDateTime published = LocalDateTime.parse(field(fields, "published_date", 19), DATE);
        String end = field(fields, "end_date", 19);
        Optional<LocalDateTime> ends = end.equals("N/A") ? Optional.empty() : Optional.of(LocalDateTime.parse(end, DATE));
        if (ends.isPresent() && !ends.get().isAfter(published)) throw new IllegalArgumentException();
        return new Candidate(id, field(fields, "title", 256), field(fields, "description", 8000),
                field(fields, "instructions", 8000), field(fields, "platforms", 256), published, ends,
                link(field(fields, "gamerpower_url", 512), false), link(field(fields, "open_giveaway_url", 512), true), fetchedAt,
                image(fields.get("image")), worth(fields.get("worth")));
    }
    /** Optional artwork: only GamerPower-hosted HTTPS images; a bad value is dropped instead of rejecting the row. */
    private static Optional<URI> image(String text) {
        try {
            if (text == null || text.length() > 512) return Optional.empty();
            URI uri = URI.create(text);
            return "https".equals(uri.getScheme()) && "www.gamerpower.com".equals(uri.getHost()) && uri.getRawUserInfo() == null
                    && uri.getPort() == -1 && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().matches("/offers/[A-Za-z0-9/_-]+\\.(?:jpg|jpeg|png|webp)") ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    private static Optional<String> worth(String text) {
        return text != null && text.matches("\\$[0-9]{1,5}(?:\\.[0-9]{2})?") ? Optional.of(text) : Optional.empty();
    }
    private static String field(Map<String, String> fields, String key, int maximum) {
        String value = fields.get(key);
        if (value == null || value.isBlank() || value.length() > maximum) throw new IllegalArgumentException();
        return value;
    }
    private static URI link(String text, boolean claim) {
        URI uri = URI.create(text);
        if (!"https".equals(uri.getScheme()) || !"www.gamerpower.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().matches(claim ? "/open/[a-z0-9-]+" : "/[a-z0-9-]+")) throw new IllegalArgumentException();
        return uri;
    }
    private static Batch failed(Problem problem) { return new Batch(State.FAILED, problem, List.of(), 0); }
}
