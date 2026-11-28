package org.bunnys.bunnynexus.updates;

import org.bunnys.bunnynexus.deals.Deal;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.bunnynexus.freebies.FreebieInvite;
import org.bunnys.bunnynexus.freebies.FreebieOffer;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSystem;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

/**
 * Builds the {@link AdContext} for one server's alert, only once the server is due an ad. The live free games and the
 * monthly total are cached (5 minutes and an hour), so an ad costs one subscriptions read on top of what it did before.
 * Blocking; on the sending worker.
 */
final class AdContexts {
    static final Duration LIVE_KEPT = Duration.ofMinutes(5), MONTH_KEPT = Duration.ofHours(1);
    /** Deals a server may be shown: the top few, so servers do not all see the same one on the same day. */
    static final int DEAL_CHOICES = 5;

    private record Cached<T>(T value, Instant at) {}
    private volatile Cached<List<FreebieOffer>> live;
    private volatile Cached<long[]> month;

    AdContext build(String guildId, List<FreebieOffer> page, Instant now) {
        var freebies = FreebieSystem.current();
        Set<FreebieStore> posted = freebies.map(s -> s.subscriptions().forGuild(guildId)).orElse(List.of()).stream()
                .map(FreebieSubscriptions.Subscription::store).collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(FreebieStore.class)));
        List<FreebieOffer> liveNow = freebies.isEmpty() ? List.of() : cached(live, LIVE_KEPT, now, at -> freebies.get().liveNow(Optional.empty()), c -> live = c);
        long[] monthTotal = freebies.isEmpty() ? new long[]{0, 0} : cached(month, MONTH_KEPT, now, at -> total(freebies.get().givenAwaySince(at.minus(Duration.ofDays(30)), 300)), c -> month = c);
        var deal = DealSystem.current().map(DealSystem::snapshot).filter(s -> !s.stale(now)).flatMap(s -> pickDeal(s.deals(), guildId, now));
        return new AdContext(deal, otherFree(liveNow, page, posted), game(page), (int) monthTotal[0], monthTotal[1],
                FreebieInvite.url(), FreebieInvite.serverUrl(), FreebieInvite.VOTE);
    }

    private static <T> T cached(Cached<T> current, Duration keep, Instant now, Function<Instant, T> load, java.util.function.Consumer<Cached<T>> store) {
        if (current != null && current.at().plus(keep).isAfter(now)) return current.value();
        T value = load.apply(now);
        store.accept(new Cached<>(value, now));
        return value;
    }

    /** How many games were given away, and their combined worth in cents (games without a price count as $0). */
    static long[] total(List<FreebieOffer> offers) {
        long count = 0, cents = 0;
        for (var o : offers) {
            if (o.store().news()) continue;
            count++;
            cents += o.worth().flatMap(AdContext::cents).orElse(0L);
        }
        return new long[]{count, cents};
    }

    /** One of the top deals, the same one all day for a server; deals with a known end first (urgency sells). */
    static Optional<AdContext.Deal> pickDeal(List<Deal> deals, String guildId, Instant now) {
        if (deals.isEmpty()) return Optional.empty();
        List<Deal> top = deals.stream().sorted(Comparator.comparing((Deal d) -> d.endsAt().isEmpty())).limit(DEAL_CHOICES).toList();
        int pick = Math.floorMod((guildId + LocalDate.ofInstant(now, ZoneOffset.UTC)).hashCode(), top.size());
        Deal d = top.get(pick);
        return Optional.of(new AdContext.Deal(d.title(), d.link().toString(), d.store().label, d.store().emoji.orElse("🏷️"),
                money(d.saleCents()), money(d.normalCents()), d.percentOff(), d.endsAt()));
    }

    /** A game free right now on a launcher this server does not post, and not in this alert: the most valuable one. */
    static Optional<AdContext.Free> otherFree(List<FreebieOffer> live, List<FreebieOffer> page, Set<FreebieStore> posted) {
        Set<String> onPage = new HashSet<>();
        page.forEach(o -> onPage.add(o.id()));
        return live.stream().filter(o -> !o.store().news() && !posted.contains(o.store()) && !onPage.contains(o.id()))
                .max(Comparator.comparingLong(o -> o.worth().flatMap(AdContext::cents).orElse(0L)))
                .map(o -> new AdContext.Free(o.title().replaceFirst("\\s+Giveaway$", ""), o.claimUrl().toString(), o.store().label().replaceFirst(" Store$", ""),
                        FreebieSystem.emoji(o.store()), o.worth(), o.endsAt()));
    }

    /** The alert's most valuable game, when one has a price. */
    static Optional<AdContext.Game> game(List<FreebieOffer> page) {
        return page.stream().filter(o -> o.worth().flatMap(AdContext::cents).isPresent())
                .max(Comparator.comparingLong(o -> o.worth().flatMap(AdContext::cents).orElse(0L)))
                .map(o -> new AdContext.Game(o.title().replaceFirst("\\s+Giveaway$", ""), o.claimUrl().toString(),
                        AdContext.dollars(o.worth().flatMap(AdContext::cents).orElseThrow()), o.worth().flatMap(AdContext::cents).orElseThrow()));
    }

    private static String money(long cents) { return String.format(Locale.ROOT, "$%d.%02d", cents / 100, cents % 100); }
}
