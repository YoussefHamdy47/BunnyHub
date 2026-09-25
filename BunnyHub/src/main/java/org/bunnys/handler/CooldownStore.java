package org.bunnys.handler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Bounded, monotonic cooldowns with ownership-safe cancellation. Not a security boundary. */
public final class CooldownStore {
    public static final long MAX_MILLIS = Duration.ofHours(2).toMillis();
    private final Cache<String, Entry> entries;
    private final LongSupplier clock;
    private static final class Entry {
        final long deadline;
        Entry(long deadline) { this.deadline = deadline; }
    }
    public record Reservation(long remainingMillis, Runnable cancellation) {
        public void release() { cancellation.run(); }
    }

    public CooldownStore(String name, int capacity) { this(name, capacity, System::nanoTime); }
    CooldownStore(String name, int capacity, LongSupplier clock) {
        this.clock = clock;
        entries = CacheRegistry.register(name, Caffeine.newBuilder().maximumSize(capacity)
                .expireAfterWrite(Duration.ofHours(2)).recordStats().build());
    }

    public Reservation reserve(String key, long millis) {
        if (millis < 0 || millis > MAX_MILLIS) throw new IllegalArgumentException("Cooldown must be between 0 and two hours.");
        if (millis == 0) return new Reservation(0, () -> {});
        long now = clock.getAsLong();
        Entry proposed = new Entry(now + millis * 1_000_000L);
        Entry chosen = entries.asMap().compute(key, (ignored, current) ->
                current != null && current.deadline - now > 0 ? current : proposed);
        if (chosen != proposed) return new Reservation(Math.max(1, (chosen.deadline - now + 999_999) / 1_000_000), () -> {});
        return new Reservation(0, () -> entries.asMap().remove(key, proposed));
    }
}
