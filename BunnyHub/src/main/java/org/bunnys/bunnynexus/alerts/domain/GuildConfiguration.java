package org.bunnys.bunnynexus.alerts.domain;

import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Bounded current aggregate. Removed identities are retained separately, never accumulated here. */
public record GuildConfiguration(String guildId, long revision, boolean enabled, boolean deleted, List<Destination> destinations) {
    public static final int MAX_DESTINATIONS = 100, MAX_SUBSCRIPTIONS = 1000;
    public GuildConfiguration {
        guildId = snowflake(guildId); destinations = List.copyOf(destinations);
        if (revision < 0 || destinations.size() > MAX_DESTINATIONS || (deleted && (enabled || !destinations.isEmpty())))
            throw new IllegalArgumentException("Invalid configuration aggregate.");
        var channels = new HashSet<String>(); var ids = new HashSet<String>(); int subscriptions = 0;
        for (var destination : destinations) {
            if (!destination.ref().guildId().equals(guildId) || !channels.add(destination.ref().channelId())
                    || !ids.add(destination.ref().destinationId())) throw new IllegalArgumentException("Duplicate/foreign destination.");
            subscriptions += destination.subscriptions().size();
            for (var sub : destination.subscriptions()) if (!ids.add(sub.id())) throw new IllegalArgumentException("Duplicate subscription identity.");
        }
        if (subscriptions > MAX_SUBSCRIPTIONS || (revision == 0 && (!destinations.isEmpty() || enabled || !deleted)))
            throw new IllegalArgumentException("Invalid aggregate size or initial state.");
    }
    public static GuildConfiguration absent(String guildId) { return new GuildConfiguration(guildId, 0, false, true, List.of()); }
    public Optional<Destination> destination(String channel) {
        return destinations.stream().filter(d -> d.ref().channelId().equals(channel)).findFirst();
    }
    public record Destination(DestinationRef ref, long revision, boolean enabled, List<Setting> subscriptions) {
        public Destination {
            Objects.requireNonNull(ref); subscriptions = List.copyOf(subscriptions);
            if (revision < 1 || subscriptions.size() > MAX_SUBSCRIPTIONS) throw new IllegalArgumentException("Invalid destination.");
            var keys = new HashSet<List<Object>>();
            for (var sub : subscriptions) {
                Subscription.checkedRoles(sub.roles(), ref.guildId());
                if (!keys.add(List.of(sub.store(), sub.topic()))) throw new IllegalArgumentException("Duplicate subscription selection.");
            }
        }
        public Subscription projection(Setting s, boolean guildEnabled) {
            return new Subscription(s.id(), ref, s.store(), s.market(), s.topic(), guildEnabled, enabled, s.enabled(), s.enabledSince(), s.revision(), s.roles());
        }
    }
    public record Setting(String id, StoreId store, Market market, Topic topic, boolean enabled,
                          Instant enabledSince, long revision, Set<String> roles) {
        public Setting {
            id = token(id); Objects.requireNonNull(store); Objects.requireNonNull(market); Objects.requireNonNull(topic);
            Objects.requireNonNull(enabledSince); roles = Set.copyOf(roles);
            if (revision < 1 || roles.size() > Subscription.MAX_ROLE_IDS) throw new IllegalArgumentException("Invalid subscription setting.");
            roles.forEach(AlertIdentity::snowflake);
        }
        public Setting activated(Instant now) {
            if (now.isBefore(enabledSince)) throw new IllegalArgumentException("Configuration clock moved backwards.");
            return new Setting(id, store, market, topic, enabled, now, Math.incrementExact(revision), roles);
        }
    }
}
