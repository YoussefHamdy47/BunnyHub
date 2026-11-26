package org.bunnys.bunnynexus.consoles;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * PS Plus, Game Pass, Free Play Days and in-game item news for the Console section (see {@link ConsoleNews}): checked every {@link #EVERY} on one
 * daemon thread. CONSOLE_NEWS_ENABLED=false turns it off. Respects the free-games kill switch.
 */
public final class ConsoleNewsSystem {
    static final Duration FIRST = Duration.ofMinutes(2), EVERY = Duration.ofHours(3);
    static final String AGENT = "BunnyNexus-Console/1.0 (Discord bot)";
    private static final Permission[] POST = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS};
    /**
     * What existing Console channels get turned on for, once: PlayStation channels get PS Plus, Xbox channels Game Pass
     * and Free Play Days. In-game items stay off until a server ticks them.
     */
    static final Map<FreebieStore, FreebieStore> TURN_ON = Map.of(FreebieStore.PLAYSTATION, FreebieStore.PS_PLUS,
            FreebieStore.XBOX, FreebieStore.GAME_PASS);
    private static volatile ConsoleNewsSystem instance;

    private final ScheduledExecutorService executor;
    private final ConsoleFeeds feeds;

    private ConsoleNewsSystem(ConsoleNewsStore store, FreebieSubscriptions subscriptions, Supplier<JDA> jda, Clock clock) {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("BunnyConsoleNews").daemon().factory());
        feeds = new ConsoleFeeds(AGENT);
        var news = new ConsoleNews(feeds, store, new DiscordPosts(jda), subscriptions::forStore,
                () -> FreebieSystem.current().map(s -> s.repository().sendingPaused()).orElse(false));
        executor.scheduleWithFixedDelay(() -> {
            try {
                migrate(store, subscriptions, clock.instant());
                news.run(clock.instant());
            } catch (RuntimeException failure) {
                BunnyLog.warning("[Console] Check failed | " + FailureDiagnostics.describe(failure));
            }
        }, FIRST.toSeconds(), EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Once: every channel posting PlayStation (Xbox) free games also gets PS Plus (Game Pass) news, with the same
     * role. Servers can untick either in the dashboard.
     */
    static void migrate(ConsoleNewsStore store, FreebieSubscriptions subscriptions, Instant now) {
        var state = store.state();
        if (state.migrated()) return;
        int added = 0;
        for (var entry : TURN_ON.entrySet())
            for (var s : subscriptions.forStore(entry.getKey()))
                if (subscriptions.find(s.guildId(), s.channelId(), entry.getValue()).isEmpty()
                        && subscriptions.save(new FreebieSubscriptions.Subscription(s.guildId(), s.channelId(), entry.getValue(), s.roleId()),
                        "console-news", now) != FreebieSubscriptions.SaveResult.LIMIT_REACHED) added++;
        store.save(state.withMigrated());
        BunnyLog.success("[Console] Turned on PS Plus / Game Pass news for " + added + " existing console setting(s)");
    }

    public static synchronized void start(BunnyHub client) {
        if (instance != null) return;
        if ("false".equalsIgnoreCase(String.valueOf(TokenLoader.getEnvQuietly("CONSOLE_NEWS_ENABLED")).strip())) {
            BunnyLog.info("[Console] Off (CONSOLE_NEWS_ENABLED=false).");
            return;
        }
        if (client.getMongoManager() == null || !client.getMongoManager().isConnected()) { BunnyLog.warning("[Console] Off: no database."); return; }
        try {
            var store = new ConsoleNewsStore(DB.getDatabase());
            store.installIndexes();
            instance = new ConsoleNewsSystem(store, new FreebieSubscriptions(DB.getDatabase()), client::getJDA, Clock.systemUTC());
            BunnyLog.success("[Console] Started. PS Plus, Game Pass, Free Play Days and in-game items checked every 3 h.");
        } catch (RuntimeException failure) {
            BunnyLog.warning("[Console] Not started | " + FailureDiagnostics.describe(failure));
        }
    }

    public static synchronized void shutdown() {
        var system = instance;
        instance = null;
        if (system == null) return;
        system.executor.shutdownNow();
        system.feeds.close();
    }

    private record DiscordPosts(Supplier<JDA> jda) implements ConsoleNews.Discord {
        @Override public boolean ready() {
            JDA current = jda.get();
            return current != null && current.getStatus() == JDA.Status.CONNECTED;
        }

        @Override public Optional<String> send(String guildId, String channelId, MessageCreateData message) {
            var guild = jda.get().getGuildById(guildId);
            if (guild == null) return Optional.of("not in the server");
            var channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);
            if (channel == null) return Optional.of("channel deleted");
            if (!guild.getSelfMember().hasPermission(channel, POST)) return Optional.of("no permission to post");
            try {
                channel.sendMessage(message).submit().get(30, TimeUnit.SECONDS);
                return Optional.empty();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.of("interrupted");
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                return Optional.of(FailureDiagnostics.describe(failure));
            }
        }
    }
}
