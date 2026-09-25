package org.bunnys.bunnynexus.alerts.adapters.discord;

import com.fasterxml.jackson.core.*;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.bunnys.bunnynexus.alerts.application.AutomaticSendEngine;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Pure, inactive preparation. No JDA instance, REST call, persistence or authorization is created here. */
public final class AlertMessageRenderer {
    public static final String TEMPLATE = "free-game-en-v1";
    private static final JsonFactory JSON = new JsonFactory();
    private final Set<String> approvedHosts;

    /** Exact hosts from trusted composition, never a per-interaction/provider-supplied allowlist. */
    public AlertMessageRenderer(Set<String> approvedHosts) {
        this.approvedHosts = Set.copyOf(approvedHosts);
        if (this.approvedHosts.isEmpty() || this.approvedHosts.size() > 64 || this.approvedHosts.stream().anyMatch(host ->
                !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+") || host.matches("[0-9.]+")))
            throw new IllegalArgumentException("Explicit bounded exact-host policy required.");
    }

    /** Immutable exact JSON envelope; future transport must preserve these bytes/fields, including nonce. */
    public static final class FrozenMessage {
        private final String json;
        private final AutomaticSendEngine.PreparedPayload prepared;
        private FrozenMessage(String json, AutomaticSendEngine.PreparedPayload prepared) { this.json = json; this.prepared = prepared; }
        public String json() { return json; }
        public byte[] bodyUtf8() { return json.getBytes(StandardCharsets.UTF_8); }
        public AutomaticSendEngine.PreparedPayload prepared() { return prepared; }
    }

    public FrozenMessage render(Offer offer, AlertDisplayContent content, Subscription subscription,
                                FreeGamePolicy policy, Instant now, String attemptId, String nonce) {
        Objects.requireNonNull(offer); Objects.requireNonNull(content); Objects.requireNonNull(subscription);
        Objects.requireNonNull(policy); Objects.requireNonNull(now); AlertIdentity.token(attemptId);
        // Discord nonce length is narrower than our general internal token contract.
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{1,25}")) throw new IllegalArgumentException("Invalid Discord nonce.");
        if (!content.offerKey().equals(offer.key()) || content.contentRevision() != offer.contentRevision())
            throw new IllegalArgumentException("Display content does not match the current offer revision.");
        if (!policy.evaluate(offer, now).eligible() || !subscription.guildEnabled() || !subscription.destinationEnabled()
                || !subscription.enabled() || subscription.topic() != AlertIdentity.Topic.FREE_GAME
                || !subscription.store().equals(offer.key().store()) || !subscription.market().equals(offer.key().market()))
            throw new IllegalArgumentException("Current eligible offer and matching enabled subscription required.");
        if (!approvedHosts.contains(content.claimUrl().getHost()) || !approvedHosts.contains(content.attributionUrl().getHost()))
            throw new IllegalArgumentException("Unapproved display link host.");
        var roles = new TreeSet<>(Subscription.checkedRoles(subscription.roleIds(), subscription.destination().guildId()));
        String mentions = String.join(" ", roles.stream().map(role -> "<@&" + role + ">").toList());
        // Never silently truncate role pings or split an event into additional messages.
        if (mentions.length() > 2000) throw new IllegalArgumentException("Configured role mentions exceed one message.");
        var embed = new EmbedBuilder().setTitle(safeText(content.title(), 256), content.claimUrl().toASCIIString())
                .setDescription(safeText(content.description(), 2048))
                .setAuthor("Source: " + safeText(content.sourceName(), 128), content.attributionUrl().toASCIIString())
                .addField("Store", safeText(offer.key().store().value(), 256), true)
                .addField("Market", safeText(offer.key().market().value(), 256), true)
                .addField("Claim deadline", offer.endsAt().map(end -> "<t:" + end.getEpochSecond() + ":F>")
                        .orElse("End time not supplied"), false).build();
        try (var data = new MessageCreateBuilder().setContent(mentions).setEmbeds(embed)
                .setAllowedMentions(List.of()).mentionUsers(List.of()).mentionRoles(roles).mentionRepliedUser(false).build()) {
            // Canonical object key order also freezes allowed-mention role ordering independently of JDA's internal sets.
            var wire = data.toData().put("nonce", nonce);
            wire.getObject("allowed_mentions").put("roles", roles.stream().toList());
            String body = canonical(wire.toJson());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            return new FrozenMessage(body, new AutomaticSendEngine.PreparedPayload(offer.key(), subscription.id(), subscription.destination(), attemptId, nonce, offer.contentRevision(),
                    subscription.revision(), TEMPLATE, hash, roles, PublicationReviewPolicy.materialHash(offer, content)));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException("Payload preparation failed.", impossible); }
    }

    /** Escape formatting/mentions, hide bidi controls and break raw URL autolinks. Never split a surrogate pair or escape. */
    static String safeText(String text, int maximum) {
        var out = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            String part = Character.getType(cp) == Character.FORMAT ? ""
                    : cp == '@' ? "@\u200B" : cp == ':' ? ":\u200B"
                    : "\\`*_~|>[]()#".indexOf(cp) >= 0 ? "\\" + (char) cp : new String(Character.toChars(cp));
            if (out.length() + part.length() > maximum - 1) { out.append('…'); break; }
            out.append(part);
        }
        return out.toString().isBlank() ? "Details unavailable" : out.toString();
    }

    private static String canonical(byte[] json) throws IOException {
        try (var parser = JSON.createParser(json); var output = new ByteArrayOutputStream()) {
            parser.nextToken(); Object value = read(parser);
            try (var writer = JSON.createGenerator(output)) { write(writer, value); }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
    private static Object read(JsonParser parser) throws IOException {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                var map = new TreeMap<String, Object>();
                while (parser.nextToken() != JsonToken.END_OBJECT) { String key = parser.currentName(); parser.nextToken(); map.put(key, read(parser)); }
                yield map;
            }
            case START_ARRAY -> { var list = new ArrayList<>(); while (parser.nextToken() != JsonToken.END_ARRAY) list.add(read(parser)); yield list; }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Unexpected generated payload value.");
        };
    }
    private static void write(JsonGenerator writer, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) { writer.writeStartObject(); for (var entry : map.entrySet()) { writer.writeFieldName((String) entry.getKey()); write(writer, entry.getValue()); } writer.writeEndObject(); }
        else if (value instanceof List<?> list) { writer.writeStartArray(); for (var item : list) write(writer, item); writer.writeEndArray(); }
        else if (value instanceof String text) writer.writeString(text);
        else if (value instanceof Long number) writer.writeNumber(number);
        else if (value instanceof Boolean bool) writer.writeBoolean(bool);
        else if (value == null) writer.writeNull();
        else throw new IllegalArgumentException("Unexpected generated payload type.");
    }
}
