package org.bunnys.bunnynexus.alerts.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Structured keys: never concatenate or hash titles, source IDs or mutable content for identity. */
public final class AlertIdentity {
    private AlertIdentity() {}

    public record StoreId(String value) {
        public StoreId { value = token(value); }
    }
    public record SourceId(String value) {
        public SourceId { value = token(value); }
    }
    /** Explicit market identifier from a certified adapter; no implicit worldwide fallback. */
    public record Market(String value) {
        public Market { value = token(value); }
    }
    public record OfferKey(StoreId store, String campaignKey, Market market) {
        public OfferKey {
            Objects.requireNonNull(store); Objects.requireNonNull(market);
            campaignKey = token(campaignKey);
        }
    }
    public enum Topic { FREE_GAME, GOOD_DEAL }
    public enum Origin { AUTOMATIC, MANUAL }

    public sealed interface NotificationKey permits FreeGameKey, DigestKey {}
    /** Origin and audience plan are deliberately absent: manual and automatic sends deduplicate. */
    public record FreeGameKey(OfferKey offer) implements NotificationKey {
        public FreeGameKey { Objects.requireNonNull(offer); }
    }
    public record DigestKey(String destinationId, String incarnation, LocalDate localDate,
                            String scheduleKind) implements NotificationKey {
        public DigestKey {
            destinationId = token(destinationId); incarnation = token(incarnation);
            Objects.requireNonNull(localDate); scheduleKind = token(scheduleKind);
        }
    }
    public record DeliveryKey(String eventId, String channelId) {
        public DeliveryKey { eventId = token(eventId); channelId = snowflake(channelId); }
    }
    public record DestinationRef(String guildId, String channelId, String destinationId, String incarnation) {
        public DestinationRef {
            guildId = snowflake(guildId); channelId = snowflake(channelId);
            destinationId = token(destinationId); incarnation = token(incarnation);
        }
    }

    public static String token(String value) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > 256 || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)
                || value.codePoints().anyMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))
            throw new IllegalArgumentException("Expected a bounded, nonblank identifier without surrounding whitespace.");
        return value;
    }
    public static String snowflake(String value) {
        Objects.requireNonNull(value);
        if (!value.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid Discord ID.");
        try { Long.parseUnsignedLong(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Discord ID exceeds unsigned 64 bits."); }
        return value;
    }
}
