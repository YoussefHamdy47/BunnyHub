package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.domain.ConfigurationChange.*;
import static org.bunnys.bunnynexus.alerts.domain.ConfigurationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final StoreId EPIC = new StoreId("epic");
    private static final Market EG = new Market("EG"), US = new Market("US");
    private final ConfigurationPolicy policy = policy(2, 3, 2);
    private static ConfigurationPolicy policy(int destinations, int subscriptions, int roles) {
        return new ConfigurationPolicy(new Limits(destinations, subscriptions, roles, Set.of(EPIC, new StoreId("steam")), Set.of(EG, US), Set.of(Topic.FREE_GAME)));
    }
    private static ConfigurationChange change(String action, long revision, Operation operation) { return new ConfigurationChange(action, "300", "100", revision, operation); }
    private static PutSubscription put(String channel, Market market, boolean enabled, Set<String> roles) {
        return new PutSubscription(channel, EPIC, market, Topic.FREE_GAME, enabled, roles);
    }
    private GuildConfiguration initial() { return policy.apply(GuildConfiguration.absent("100"), change("400", 0, put("200", EG, true, Set.of("500"))), NOW); }
    private static GuildConfiguration.Setting setting(GuildConfiguration configuration) { return configuration.destinations().getFirst().subscriptions().getFirst(); }

    @Test void roleEditsPreserveEligibilityAndMarketChangesResetIt() {
        var first = initial();
        var roles = policy.apply(first, change("401", 1, put("200", EG, true, Set.of("501"))), NOW.plusSeconds(10));
        assertEquals(NOW, setting(roles).enabledSince());
        assertEquals(setting(first).id(), setting(roles).id());
        assertEquals(2, setting(roles).revision());
        var market = policy.apply(roles, change("402", 2, put("200", US, true, Set.of("501"))), NOW.plusSeconds(20));
        assertEquals(NOW.plusSeconds(20), setting(market).enabledSince());
        var sub = market.destinations().getFirst().projection(setting(market), true);
        assertEquals(Subscription.Match.WRONG_MARKET, sub.matchAutomatic(sub.id(), sub.destination(),
                new OfferKey(EPIC, "offer", EG), Topic.FREE_GAME, NOW));
    }
    @Test void reenablingAtEachLevelPreventsImplicitCatchup() {
        for (int level = 0; level < 3; level++) {
            var first = initial();
            Operation disable = level == 0 ? new SetGuildEnabled(false) : level == 1 ? new SetDestinationEnabled("200", false) : new SetSubscriptionEnabled("200", EPIC, Topic.FREE_GAME, false);
            Operation enable = level == 0 ? new SetGuildEnabled(true) : level == 1 ? new SetDestinationEnabled("200", true) : new SetSubscriptionEnabled("200", EPIC, Topic.FREE_GAME, true);
            var disabled = policy.apply(first, change("401", 1, disable), NOW.plusSeconds(1));
            var next = policy.apply(disabled, change("402", 2, enable), NOW.plusSeconds(2));
            assertEquals(NOW.plusSeconds(2), setting(next).enabledSince());
            var sub = next.destinations().getFirst().projection(setting(next), next.enabled());
            assertEquals(Subscription.Match.ACTIVATED_AFTER_EVENT, sub.matchAutomatic(sub.id(), sub.destination(),
                    new OfferKey(EPIC, "offer", EG), Topic.FREE_GAME, NOW));
        }
    }
    @Test void removedChannelAndSubscriptionNeverReuseTheirOldIdentity() {
        var before = initial(); var ref = before.destinations().getFirst().ref();
        var removed = policy.apply(before, change("401", 1, new RemoveDestination("200")), NOW.plusSeconds(1));
        var after = policy.apply(removed, change("402", 2, put("200", EG, true, Set.of())), NOW.plusSeconds(2));
        assertNotEquals(ref, after.destinations().getFirst().ref());
        assertNotEquals(setting(before).id(), setting(after).id());
        var noSub = policy.apply(before, change("403", 1, new RemoveSubscription("200", EPIC, Topic.FREE_GAME)), NOW.plusSeconds(1));
        var resub = policy.apply(noSub, change("404", 2, put("200", EG, true, Set.of())), NOW.plusSeconds(2));
        assertEquals(ref, resub.destinations().getFirst().ref());
        assertNotEquals(setting(before).id(), setting(resub).id());
    }
    @Test void staleEditorsCannotIndependentlyConsumeTheFinalSlot() {
        var oneSlot = policy(1, 1, 1); var before = initial();
        assertEquals(Failure.REVISION_CONFLICT, assertThrows(Rejected.class,
                () -> oneSlot.apply(before, change("401", 0, put("201", EG, true, Set.of())), NOW)).failure());
        assertEquals(Failure.LIMIT_REACHED, assertThrows(Rejected.class,
                () -> oneSlot.apply(before, change("401", 1, put("201", EG, true, Set.of())), NOW)).failure());
        assertEquals(Failure.LIMIT_REACHED, assertThrows(Rejected.class,
                () -> oneSlot.apply(before, change("401", 1, put("200", EG, true, Set.of("501", "502"))), NOW)).failure());
    }
    @Test void limitsDoNotPreventRemovalAndNoPaidTopicIsImplicitlyEnabled() {
        var first = initial(); var smaller = policy(1, 1, 0);
        assertFalse(smaller.apply(first, change("401", 1, new SetGuildEnabled(false)), NOW).enabled());
        var deleted = smaller.apply(first, change("401", 1, new RemoveGuild()), NOW);
        assertTrue(deleted.deleted()); assertTrue(deleted.destinations().isEmpty());
        assertEquals(Failure.UNSUPPORTED_SELECTION, assertThrows(Rejected.class, () -> policy.apply(first,
                change("401", 1, new PutSubscription("200", EPIC, EG, Topic.GOOD_DEAL, true, Set.of())), NOW)).failure());
    }
    @Test void fingerprintsAreOrderIndependentButBoundToActorIntentAndRevision() {
        var a = change("400", 0, put("200", EG, true, new LinkedHashSet<>(List.of("500", "501"))));
        var b = change("400", 0, put("200", EG, true, new LinkedHashSet<>(List.of("501", "500"))));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), new ConfigurationChange("400", "301", "100", 0, a.operation()).fingerprint());
        assertNotEquals(a.fingerprint(), change("400", 1, a.operation()).fingerprint());
        assertThrows(IllegalArgumentException.class, () -> change("400", 0, put("200", EG, true, Set.of("100"))));
        assertThrows(IllegalArgumentException.class, () -> new GuildConfiguration("101", 1, true, false, initial().destinations()));
        assertThrows(UnsupportedOperationException.class, () -> initial().destinations().clear());
    }
    @Test void removingSupportStillAllowsDisablingButCannotBeBypassedByReenable() {
        var restricted = new ConfigurationPolicy(new Limits(2, 3, 2, Set.of(new StoreId("steam")), Set.of(EG), Set.of(Topic.FREE_GAME)));
        var before = initial();
        var disabled = restricted.apply(before, change("401", 1, new SetSubscriptionEnabled("200", EPIC, Topic.FREE_GAME, false)), NOW);
        assertFalse(setting(disabled).enabled());
        assertEquals(Failure.UNSUPPORTED_SELECTION, assertThrows(Rejected.class, () -> restricted.apply(disabled,
                change("402", 2, new SetSubscriptionEnabled("200", EPIC, Topic.FREE_GAME, true)), NOW)).failure());
        assertEquals(Failure.UNSUPPORTED_SELECTION, assertThrows(Rejected.class, () -> restricted.apply(before,
                change("402", 1, new SetGuildEnabled(true)), NOW)).failure());
    }
}
