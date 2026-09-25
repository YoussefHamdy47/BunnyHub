package org.bunnys.bunnynexus.alerts.domain;

import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** One administrator intent, identified by its Discord interaction ID before any retry. */
public record ConfigurationChange(String actionId, String actorId, String guildId, long expectedRevision, Operation operation) {
    public ConfigurationChange {
        actionId = snowflake(actionId); actorId = snowflake(actorId); guildId = snowflake(guildId);
        if (expectedRevision < 0) throw new IllegalArgumentException("Invalid expected revision.");
        Objects.requireNonNull(operation);
        if (operation instanceof PutSubscription put) Subscription.checkedRoles(put.roles(), guildId);
    }
    public sealed interface Operation permits PutSubscription, SetGuildEnabled, SetDestinationEnabled, SetSubscriptionEnabled,
            RemoveSubscription, RemoveDestination, RemoveGuild {}
    public record PutSubscription(String channelId, StoreId store, Market market, Topic topic,
                                  boolean enabled, Set<String> roles) implements Operation {
        public PutSubscription {
            channelId = snowflake(channelId); Objects.requireNonNull(store); Objects.requireNonNull(market);
            Objects.requireNonNull(topic); roles = Set.copyOf(roles);
            if (roles.size() > Subscription.MAX_ROLE_IDS) throw new IllegalArgumentException("Too many roles.");
            roles.forEach(AlertIdentity::snowflake);
        }
    }
    public record SetGuildEnabled(boolean enabled) implements Operation {}
    public record SetDestinationEnabled(String channelId, boolean enabled) implements Operation {
        public SetDestinationEnabled { channelId = snowflake(channelId); }
    }
    public record SetSubscriptionEnabled(String channelId, StoreId store, Topic topic, boolean enabled) implements Operation {
        public SetSubscriptionEnabled { channelId = snowflake(channelId); Objects.requireNonNull(store); Objects.requireNonNull(topic); }
    }
    public record RemoveSubscription(String channelId, StoreId store, Topic topic) implements Operation {
        public RemoveSubscription { channelId = snowflake(channelId); Objects.requireNonNull(store); Objects.requireNonNull(topic); }
    }
    public record RemoveDestination(String channelId) implements Operation {
        public RemoveDestination { channelId = snowflake(channelId); }
    }
    public record RemoveGuild() implements Operation {}

    /** Explicit, stable wire description; never hash record.toString or unordered sets. */
    public List<String> identityParts() {
        var parts = new ArrayList<>(List.of("alert-config-change-v1", actionId, actorId, guildId, Long.toString(expectedRevision)));
        switch (operation) {
            case PutSubscription p -> {
                parts.addAll(List.of("PUT_SUBSCRIPTION", p.channelId(), p.store().value(), p.market().value(), p.topic().name(), Boolean.toString(p.enabled())));
                parts.addAll(p.roles().stream().sorted().toList());
            }
            case SetGuildEnabled p -> parts.addAll(List.of("SET_GUILD_ENABLED", Boolean.toString(p.enabled())));
            case SetDestinationEnabled p -> parts.addAll(List.of("SET_DESTINATION_ENABLED", p.channelId(), Boolean.toString(p.enabled())));
            case SetSubscriptionEnabled p -> parts.addAll(List.of("SET_SUBSCRIPTION_ENABLED", p.channelId(), p.store().value(), p.topic().name(), Boolean.toString(p.enabled())));
            case RemoveSubscription p -> parts.addAll(List.of("REMOVE_SUBSCRIPTION", p.channelId(), p.store().value(), p.topic().name()));
            case RemoveDestination p -> parts.addAll(List.of("REMOVE_DESTINATION", p.channelId()));
            case RemoveGuild ignored -> parts.add("REMOVE_GUILD");
        }
        return List.copyOf(parts);
    }
    public String fingerprint() { return StableIdentity.hash(identityParts().toArray(String[]::new)); }
}
