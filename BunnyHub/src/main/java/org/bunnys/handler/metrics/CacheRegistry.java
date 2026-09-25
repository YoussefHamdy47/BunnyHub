package org.bunnys.handler.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Every Caffeine cache in the bot, in one place that can be read.
 *
 * <p>The caches already asked Caffeine to keep statistics and nothing ever looked at
 * them - the numbers that would say whether a {@code maximumSize} is generous or
 * strangling were being collected and thrown away. Registering at construction costs one
 * wrapped call per cache and makes the whole set reportable without any of them knowing
 * a reporter exists.
 *
 * <pre>
 *   private static final Cache&lt;String, LegData&gt; USERS =
 *           CacheRegistry.register("leg.users", Caffeine.newBuilder()
 *                   .maximumSize(50_000)
 *                   .recordStats()
 *                   .build());
 * </pre>
 *
 * <h2>What the numbers are for</h2>
 * <ul>
 *   <li><b>Hit rate</b> - how much database traffic the cache is actually preventing. A
 *       low rate on a hot cache means the expiry is shorter than the access pattern.</li>
 *   <li><b>Size against its maximum</b> - a cache pinned at its ceiling is evicting live
 *       entries, and every eviction is a database read somebody waits for.</li>
 *   <li><b>Load failures</b> - loaders here return null on database trouble so the
 *       failure is not cached; a rising count is Mongo misbehaving, not the cache.</li>
 * </ul>
 *
 * <p>Ordered by name so the report reads the same way twice, and concurrent because
 * registration happens during class initialisation on whichever thread touches a service
 * first. That laziness is visible in the report: a cache only appears once its feature
 * has been used at least once, which is honest - an untouched cache has nothing to say -
 * but worth knowing before reading an absence as a missing cache.
 */
public final class CacheRegistry {

    private static final Map<String, Cache<?, ?>> CACHES = new ConcurrentSkipListMap<>();

    private CacheRegistry() {}

    /**
     * Registers a cache and hands it straight back, so a declaration stays one statement.
     *
     * @param name dotted and lower case, {@code feature.purpose} - it is a report label,
     *             not an identifier, and reads better than the field name
     * @return the same cache, for assignment
     */
    public static <K, V> Cache<K, V> register(String name, Cache<K, V> cache) {
        CACHES.put(name, cache);
        return cache;
    }

    /**
     * @param statsEnabled false when the cache was built without {@code recordStats()},
     *                     in which case every counter below is zero and means nothing
     */
    public record CacheReport(String name, long size, long hits, long misses,
                              double hitRate, long evictions, long loadFailures,
                              boolean statsEnabled) {

        /** Hit rate as a percentage, or -1 when nothing has been asked of the cache yet. */
        public double hitPercent() {
            return (hits + misses) == 0 ? -1 : hitRate * 100.0;
        }
    }

    public static List<CacheReport> report() {
        List<CacheReport> reports = new ArrayList<>(CACHES.size());

        CACHES.forEach((name, cache) -> {
            CacheStats stats = cache.stats();

            // Caffeine returns an all-zero CacheStats when recording is off. A cache
            // holding entries that has never recorded a request is the giveaway; without
            // this the report would show a healthy-looking 0% rather than "not measured".
            boolean measured = stats.requestCount() > 0 || cache.estimatedSize() == 0;

            reports.add(new CacheReport(
                    name,
                    cache.estimatedSize(),
                    stats.hitCount(),
                    stats.missCount(),
                    stats.hitRate(),
                    stats.evictionCount(),
                    stats.loadFailureCount(),
                    measured));
        });

        return reports;
    }

    /** How many caches are registered, for the startup summary. */
    public static int count() {
        return CACHES.size();
    }
}
