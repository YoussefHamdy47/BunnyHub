package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.domain.ConfigurationChange.*;

/** Trusted Discord boundary, invoked outside database transactions. No implementation is activated yet. */
public interface ConfigurationAccess {
    /** Require an actual guild interaction and current Manage Server/Administrator before reading configuration. */
    void requireAdministrator(String actorId, String guildId);
    /**
     * Recheck the administrator and resolve every requested channel/role within that guild. Require supported
     * channel type, bot view/send/embed permissions and actual mentionability of exactly these roles.
     * Empty destination requirements permit disables/removal even if a channel or role was deleted on Discord.
     * Throw AccessDenied on any failure. The caller must not manufacture successful evidence from user input.
     */
    Proof verify(Requirements requirements);

    final class AccessDenied extends RuntimeException {
        public AccessDenied() { super("Configuration access denied."); }
    }
    record DestinationRequirement(String channelId, Set<String> roles) {
        public DestinationRequirement {
            channelId = snowflake(channelId); roles = Set.copyOf(roles);
            if (roles.size() > Subscription.MAX_ROLE_IDS) throw new IllegalArgumentException("Too many destination roles.");
            roles.forEach(AlertIdentity::snowflake);
        }
    }
    record Requirements(String fingerprint, String actorId, String guildId, List<DestinationRequirement> destinations) {
        public Requirements {
            fingerprint = token(fingerprint); actorId = snowflake(actorId); guildId = snowflake(guildId);
            destinations = List.copyOf(destinations);
            if (destinations.size() > GuildConfiguration.MAX_DESTINATIONS) throw new IllegalArgumentException("Too many destinations.");
            var channels = new HashSet<String>();
            for (var d : destinations) {
                if (!channels.add(d.channelId())) throw new IllegalArgumentException("Duplicate channel proof.");
                Subscription.checkedRoles(d.roles(), guildId);
            }
        }
    }
    record Proof(Requirements requirements, Instant checkedAt, Instant validUntil) {
        public Proof {
            Objects.requireNonNull(requirements); Objects.requireNonNull(checkedAt); Objects.requireNonNull(validUntil);
            if (!checkedAt.isBefore(validUntil) || Duration.between(checkedAt, validUntil).compareTo(Duration.ofSeconds(60)) > 0)
                throw new IllegalArgumentException("Permission evidence must have a bounded lifetime.");
        }
        public void requireCurrent(Requirements expected, Instant now) {
            if (!requirements.equals(expected) || now.isBefore(checkedAt) || !now.isBefore(validUntil)) throw new AccessDenied();
        }
    }
    static Requirements requirements(ConfigurationChange change, GuildConfiguration next) {
        var selected = new ArrayList<DestinationRequirement>();
        for (var d : next.destinations()) {
            boolean include = switch (change.operation()) {
                case PutSubscription p -> d.ref().channelId().equals(p.channelId());
                case SetDestinationEnabled p -> p.enabled() && d.ref().channelId().equals(p.channelId());
                case SetSubscriptionEnabled p -> p.enabled() && d.ref().channelId().equals(p.channelId());
                case SetGuildEnabled p -> p.enabled() && d.enabled();
                default -> false;
            };
            if (!include) continue;
            var roles = new HashSet<String>();
            d.subscriptions().stream().filter(s -> s.enabled() || change.operation() instanceof PutSubscription)
                    .forEach(s -> roles.addAll(s.roles()));
            selected.add(new DestinationRequirement(d.ref().channelId(), roles));
        }
        selected.sort(Comparator.comparing(DestinationRequirement::channelId));
        return new Requirements(change.fingerprint(), change.actorId(), change.guildId(), selected);
    }
}
