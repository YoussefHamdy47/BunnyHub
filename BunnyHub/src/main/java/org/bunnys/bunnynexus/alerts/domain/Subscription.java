package org.bunnys.bunnynexus.alerts.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Current configuration projection. Discord ownership/permissions must be verified by the adapter. */
public record Subscription(String id, DestinationRef destination, StoreId store, Market market, Topic topic,
                           boolean guildEnabled, boolean destinationEnabled, boolean enabled,
                           Instant enabledSince, long revision, Set<String> roleIds) {
    /** Technical payload bound, not the proposed five-role product limit. */
    public static final int MAX_ROLE_IDS = 100;
    public Subscription {
        id = token(id); Objects.requireNonNull(destination); Objects.requireNonNull(store);
        Objects.requireNonNull(market); Objects.requireNonNull(topic); Objects.requireNonNull(enabledSince);
        if (revision < 1) throw new IllegalArgumentException("Revision must be positive.");
        roleIds = checkedRoles(roleIds, destination.guildId());
    }
    public static Set<String> checkedRoles(Set<String> roles, String guildId) {
        Objects.requireNonNull(roles);
        if (roles.size() > MAX_ROLE_IDS) throw new IllegalArgumentException("Too many role IDs.");
        Set<String> copy = Set.copyOf(roles);
        for (String role : copy) {
            snowflake(role);
            if (role.equals(guildId)) throw new IllegalArgumentException("The everyone role is forbidden.");
        }
        return copy;
    }
    public enum Match { MATCH, DISABLED, REPLACED_DESTINATION, DIFFERENT_SUBSCRIPTION, WRONG_STORE,
        WRONG_MARKET, WRONG_TOPIC, ACTIVATED_AFTER_EVENT }

    /** Automatic cutoff also applies on send rechecks. Manual catch-up needs a separate approved plan. */
    public Match matchAutomatic(String expectedSubscriptionId, DestinationRef expectedDestination,
                                OfferKey offer, Topic eventTopic, Instant observedAt) {
        Objects.requireNonNull(offer); Objects.requireNonNull(eventTopic); Objects.requireNonNull(observedAt);
        if (!destination.equals(expectedDestination)) return Match.REPLACED_DESTINATION;
        if (!id.equals(expectedSubscriptionId)) return Match.DIFFERENT_SUBSCRIPTION;
        if (!guildEnabled || !destinationEnabled || !enabled) return Match.DISABLED;
        if (topic != eventTopic) return Match.WRONG_TOPIC;
        if (!store.equals(offer.store())) return Match.WRONG_STORE;
        if (!market.equals(offer.market())) return Match.WRONG_MARKET;
        if (enabledSince.isAfter(observedAt)) return Match.ACTIVATED_AFTER_EVENT;
        return Match.MATCH;
    }
}
