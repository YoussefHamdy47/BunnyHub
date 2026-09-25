package org.bunnys.bunnynexus.alerts.domain;

import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.ConfigurationChange.*;
import static org.bunnys.bunnynexus.alerts.domain.GuildConfiguration.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Pure administrator transitions. Limits are deployment input, not adopted launch defaults. */
public final class ConfigurationPolicy {
    public record Limits(int destinations, int subscriptions, int rolesPerDestination, Set<StoreId> stores,
                         Set<Market> markets, Set<Topic> topics) {
        public Limits {
            if (destinations < 1 || destinations > MAX_DESTINATIONS || subscriptions < 1 || subscriptions > MAX_SUBSCRIPTIONS
                    || rolesPerDestination < 0 || rolesPerDestination > Subscription.MAX_ROLE_IDS)
                throw new IllegalArgumentException("Invalid configuration limits.");
            stores = Set.copyOf(stores); markets = Set.copyOf(markets); topics = Set.copyOf(topics);
            if (stores.isEmpty() || markets.isEmpty() || topics.isEmpty()) throw new IllegalArgumentException("Explicit supported selections required.");
        }
    }
    public enum Failure { REVISION_CONFLICT, NOT_FOUND, LIMIT_REACHED, UNSUPPORTED_SELECTION }
    public static final class Rejected extends RuntimeException {
        private final Failure failure;
        public Rejected(Failure failure) { super(failure.name()); this.failure = failure; }
        public Failure failure() { return failure; }
    }
    private final Limits limits;
    public ConfigurationPolicy(Limits limits) { this.limits = Objects.requireNonNull(limits); }
    public GuildConfiguration apply(GuildConfiguration before, ConfigurationChange change, Instant now) {
        Objects.requireNonNull(now);
        if (!before.guildId().equals(change.guildId())) throw new IllegalArgumentException("Foreign configuration.");
        if (before.revision() != change.expectedRevision()) throw new Rejected(Failure.REVISION_CONFLICT);
        var destinations = new ArrayList<>(before.destinations());
        boolean enabled = before.enabled(), deleted = before.deleted();
        switch (change.operation()) {
            case PutSubscription p -> {
                requireSupported(p.store(), p.market(), p.topic());
                var old = before.destination(p.channelId()).orElse(null);
                var ref = old == null ? new DestinationRef(change.guildId(), p.channelId(),
                        StableIdentity.hash("alert-destination-v1", change.actionId()), StableIdentity.hash("alert-incarnation-v1", change.actionId())) : old.ref();
                var settings = new ArrayList<>(old == null ? List.<Setting>of() : old.subscriptions());
                var previous = settings.stream().filter(s -> s.store().equals(p.store()) && s.topic() == p.topic()).findFirst().orElse(null);
                if (previous != null && now.isBefore(previous.enabledSince())) throw new IllegalArgumentException("Configuration clock moved backwards.");
                Instant since = previous == null || (!previous.enabled() && p.enabled()) || !previous.market().equals(p.market()) ? now : previous.enabledSince();
                String id = previous == null ? StableIdentity.hash("alert-subscription-v1", change.actionId()) : previous.id();
                settings.remove(previous);
                settings.add(new Setting(id, p.store(), p.market(), p.topic(), p.enabled(), since,
                        previous == null ? 1 : Math.incrementExact(previous.revision()), p.roles()));
                destinations.remove(old);
                destinations.add(new Destination(ref, old == null ? 1 : Math.incrementExact(old.revision()), old == null || old.enabled(), settings));
                if (deleted) { enabled = true; deleted = false; }
            }
            case SetGuildEnabled p -> {
                requirePresent(before);
                if (p.enabled() && !enabled) destinations.replaceAll(d -> activate(d, now));
                enabled = p.enabled();
            }
            case SetDestinationEnabled p -> {
                var old = requireDestination(before, p.channelId()); destinations.remove(old);
                var refreshed = p.enabled() && !old.enabled() ? activate(old, now) : old;
                destinations.add(new Destination(old.ref(), Math.incrementExact(old.revision()), p.enabled(), refreshed.subscriptions()));
            }
            case RemoveSubscription p -> {
                var old = requireDestination(before, p.channelId()); var settings = new ArrayList<>(old.subscriptions());
                if (!settings.removeIf(s -> s.store().equals(p.store()) && s.topic() == p.topic())) throw new Rejected(Failure.NOT_FOUND);
                destinations.remove(old); destinations.add(new Destination(old.ref(), Math.incrementExact(old.revision()), old.enabled(), settings));
            }
            case SetSubscriptionEnabled p -> {
                var old = requireDestination(before, p.channelId()); var settings = new ArrayList<>(old.subscriptions());
                var sub = settings.stream().filter(s -> s.store().equals(p.store()) && s.topic() == p.topic()).findFirst()
                        .orElseThrow(() -> new Rejected(Failure.NOT_FOUND));
                var activated = p.enabled() && !sub.enabled() ? sub.activated(now) : sub;
                settings.remove(sub);
                settings.add(new Setting(sub.id(), sub.store(), sub.market(), sub.topic(), p.enabled(), activated.enabledSince(),
                        Math.incrementExact(sub.revision()), sub.roles()));
                destinations.remove(old); destinations.add(new Destination(old.ref(), Math.incrementExact(old.revision()), old.enabled(), settings));
            }
            case RemoveDestination p -> destinations.remove(requireDestination(before, p.channelId()));
            case RemoveGuild ignored -> { requirePresent(before); destinations.clear(); enabled = false; deleted = true; }
        }
        // A lower configured cap must never prevent a disable or removal needed to drain existing state.
        if (change.operation() instanceof PutSubscription) checkCapacity(destinations);
        for (var d : destinations) for (var s : d.subscriptions()) {
            boolean enabling = switch (change.operation()) {
                case SetGuildEnabled p -> p.enabled() && d.enabled() && s.enabled();
                case SetDestinationEnabled p -> p.enabled() && p.channelId().equals(d.ref().channelId()) && s.enabled();
                case SetSubscriptionEnabled p -> p.enabled() && p.channelId().equals(d.ref().channelId()) && p.store().equals(s.store()) && p.topic() == s.topic();
                default -> false;
            };
            if (enabling) requireSupported(s.store(), s.market(), s.topic());
        }
        return new GuildConfiguration(before.guildId(), Math.incrementExact(before.revision()), enabled, deleted, destinations);
    }
    private void requireSupported(StoreId store, Market market, Topic topic) {
        if (!limits.stores().contains(store) || !limits.markets().contains(market) || !limits.topics().contains(topic))
            throw new Rejected(Failure.UNSUPPORTED_SELECTION);
    }
    private void checkCapacity(List<Destination> destinations) {
        if (destinations.size() > limits.destinations() || destinations.stream().mapToInt(d -> d.subscriptions().size()).sum() > limits.subscriptions())
            throw new Rejected(Failure.LIMIT_REACHED);
        for (var destination : destinations) {
            var roles = new HashSet<String>(); destination.subscriptions().forEach(s -> roles.addAll(s.roles()));
            if (roles.size() > limits.rolesPerDestination()) throw new Rejected(Failure.LIMIT_REACHED);
        }
    }
    private static Destination activate(Destination d, Instant now) {
        return new Destination(d.ref(), Math.incrementExact(d.revision()), d.enabled(),
                d.subscriptions().stream().map(s -> s.enabled() ? s.activated(now) : s).toList());
    }
    private static void requirePresent(GuildConfiguration config) { if (config.deleted()) throw new Rejected(Failure.NOT_FOUND); }
    private static Destination requireDestination(GuildConfiguration config, String channel) {
        return config.destination(channel).orElseThrow(() -> new Rejected(Failure.NOT_FOUND));
    }
}
