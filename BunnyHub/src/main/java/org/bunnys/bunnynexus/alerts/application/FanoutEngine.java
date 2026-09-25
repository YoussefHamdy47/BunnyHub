package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Pure bounded page planning; durable uniqueness, current guards and atomic cursor commit belong to storage. */
public final class FanoutEngine {
    public record Page(List<DeliveryJob> jobs, Optional<String> lastSubscriptionId, int skipped) {
        public Page {
            Objects.requireNonNull(jobs); Objects.requireNonNull(lastSubscriptionId);
            if (skipped < 0 || jobs.size() + (long) skipped > FanoutPlan.MAX_PAGE_SIZE)
                throw new IllegalArgumentException("Oversized page.");
            jobs = List.copyOf(jobs);
        }
    }
    public Page plan(FanoutPlan plan, DeliveryJob.Lease owner, List<Subscription> subscriptions, Instant now) {
        Objects.requireNonNull(plan); Objects.requireNonNull(subscriptions); plan.requireOwner(owner, now);
        if (subscriptions.size() > FanoutPlan.MAX_PAGE_SIZE) throw new IllegalArgumentException("Oversized page.");
        String after = plan.afterSubscriptionId().orElse(null);
        List<DeliveryJob> jobs = new ArrayList<>();
        int skipped = 0;
        for (Subscription subscription : subscriptions) {
            if (after != null && FanoutPlan.compareIds(subscription.id(), after) <= 0)
                throw new IllegalArgumentException("Subscription page is not strictly ordered after cursor.");
            after = subscription.id();
            if (subscription.matchAutomatic(subscription.id(), subscription.destination(), plan.notificationKey().offer(),
                    Topic.FREE_GAME, plan.observedAt()) != Subscription.Match.MATCH) { skipped++; continue; }
            var key = new DeliveryKey(plan.eventId(), subscription.destination().channelId());
            jobs.add(DeliveryJob.ready(deliveryId(key), key, subscription.destination(), subscription.id(), now));
        }
        return new Page(jobs, subscriptions.isEmpty() ? Optional.empty() : Optional.of(after), skipped);
    }
    /** Length-prefixed structured identity; origin, plan, role changes and attempt metadata are excluded. */
    public static String deliveryId(DeliveryKey key) {
        return StableIdentity.hash("alert-delivery-v1", key.eventId(), key.channelId());
    }
}
