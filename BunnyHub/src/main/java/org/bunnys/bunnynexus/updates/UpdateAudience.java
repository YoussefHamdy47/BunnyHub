package org.bunnys.bunnynexus.updates;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Who gets a product update, and why every other registered server is skipped (docs/product-updates/README.md,
 * "Rules"). Pure: the caller loads the servers, their preferences and what they use.
 */
public final class UpdateAudience {
    public static final Duration COOLDOWN = Duration.ofDays(14);

    private UpdateAudience() {}

    /** A server's saved choice and history; {@link #NONE} for a server that never got one. */
    public record Prefs(boolean off, Optional<Instant> lastSentAt, Set<String> sent) {
        public static final Prefs NONE = new Prefs(false, Optional.empty(), Set.of());
        public Prefs { sent = Set.copyOf(sent); }
    }

    /** A registered server: its main free-game channel and the features it already uses. */
    public record Server(String guildId, String channelId, Set<ProductUpdate.Feature> uses, Prefs prefs) {
        public Server { uses = Set.copyOf(uses); }
    }

    public enum Skip { TURNED_OFF, ALREADY_SENT, COOLDOWN, USES_IT }

    /** Servers to send to, in the given order, and a count per reason for the rest (for the owner's preview). */
    public record Plan(List<Server> send, Map<Skip, Integer> skipped) {
        public Plan {
            send = List.copyOf(send);
            Map<Skip, Integer> copy = new EnumMap<>(Skip.class);
            copy.putAll(skipped);
            skipped = Collections.unmodifiableMap(copy);
        }
    }

    public static Plan plan(ProductUpdate update, List<Server> servers, Instant now) {
        List<Server> send = new ArrayList<>();
        Map<Skip, Integer> skipped = new EnumMap<>(Skip.class);
        for (Server server : servers) {
            Optional<Skip> skip = skip(update, server, now);
            if (skip.isPresent()) skipped.merge(skip.get(), 1, Integer::sum);
            else send.add(server);
        }
        return new Plan(send, skipped);
    }

    /** The first rule that keeps this server from getting the update, checked in the README's order. */
    static Optional<Skip> skip(ProductUpdate update, Server server, Instant now) {
        var prefs = server.prefs();
        if (prefs.off()) return Optional.of(Skip.TURNED_OFF);
        if (prefs.sent().contains(update.id())) return Optional.of(Skip.ALREADY_SENT);
        if (prefs.lastSentAt().filter(last -> last.plus(COOLDOWN).isAfter(now)).isPresent()) return Optional.of(Skip.COOLDOWN);
        if (update.usedBy(server.uses())) return Optional.of(Skip.USES_IT);
        return Optional.empty();
    }

    /** Ads under free-game alerts: at most one every 3 days per server, and each ad at most this many times per server. */
    public static final Duration PROMO_EVERY = Duration.ofDays(3);
    public static final int PROMO_TIMES = 6;

    /** When this server last saw an ad, and how often it saw each one. */
    public record PromoState(Optional<Instant> lastAt, Map<String, Integer> shown) {
        public static final PromoState NONE = new PromoState(Optional.empty(), Map.of());
        public PromoState { shown = Map.copyOf(shown); }
    }

    /**
     * The ad for this server's next free-game alert, if one is due: among the active ads for its audience that it has
     * seen fewer than {@link #PROMO_TIMES} times, the one it has seen least (newest first on a tie). Nothing when it
     * saw an ad in the last {@link #PROMO_EVERY}. Admins cannot turn ads off; the spacing keeps them rare.
     */
    public static Optional<Ad> ad(List<Ad> active, Set<ProductUpdate.Feature> uses, PromoState state, Instant now) {
        return ad(active, uses, state, now, AdContext.NONE);
    }

    /**
     * As above, for this moment: an ad for big games only with a game worth {@link Ad.When#BIG_GAME_CENTS} in the alert,
     * and a live ad only when its placeholders can be filled now ({@link AdTemplate}). Highest priority first, then the
     * one this server has seen least, then the newest. Returns the ad already filled in.
     */
    public static Optional<Ad> ad(List<Ad> active, Set<ProductUpdate.Feature> uses, PromoState state, Instant now, AdContext context) {
        if (state.lastAt().filter(last -> last.plus(PROMO_EVERY).isAfter(now)).isPresent()) return Optional.empty();
        boolean bigGame = context.game().map(g -> g.worthCents() >= Ad.When.BIG_GAME_CENTS).orElse(false);
        Ad best = null;
        int bestShown = Integer.MAX_VALUE;
        for (Ad ad : active) {
            int shown = state.shown().getOrDefault(ad.id(), 0);
            if (!ad.on() || !ad.audience().matches(uses) || shown >= PROMO_TIMES) continue;
            if (ad.when() == Ad.When.BIG_GAME && !bigGame) continue;
            var filled = AdTemplate.render(ad, context);
            if (filled.isEmpty()) continue;
            if (best == null || ad.priority() > best.priority()
                    || ad.priority() == best.priority() && (shown < bestShown || shown == bestShown && ad.createdAt().isAfter(best.createdAt()))) {
                best = filled.get();
                bestShown = shown;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The newest update this server does not use and has not turned off, for the dashboard's "What's new". */
    public static Optional<ProductUpdate> whatsNew(Set<ProductUpdate.Feature> uses) {
        var all = ProductUpdate.values();
        for (int i = all.length - 1; i >= 0; i--) if (!all[i].usedBy(uses)) return Optional.of(all[i]);
        return Optional.empty();
    }
}
