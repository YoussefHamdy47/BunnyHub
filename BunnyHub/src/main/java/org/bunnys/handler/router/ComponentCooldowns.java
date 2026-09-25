package org.bunnys.handler.router;

import org.bunnys.handler.CooldownStore;

/** Shared component throttling; cancellation belongs to the accepted invocation. */
public final class ComponentCooldowns {
    public static final long MAX_ENFORCEABLE_MILLIS = CooldownStore.MAX_MILLIS;
    private static final CooldownStore STORE = new CooldownStore("component.last_use", 50_000);
    private ComponentCooldowns() {}
    public static CooldownStore.Reservation reserve(String prefix, String userId, long millis) {
        return STORE.reserve(prefix + ":" + userId, millis);
    }
    /** For callers that never need cancellation. */
    public static long claim(String prefix, String userId, long millis) {
        return reserve(prefix, userId, millis).remainingMillis();
    }
}