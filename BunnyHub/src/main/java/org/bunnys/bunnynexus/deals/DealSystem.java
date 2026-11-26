package org.bunnys.bunnynexus.deals;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * The deals area (docs/deals): refreshes good deals from Steam and GOG every 3 hours and keeps each server's deals board
 * current, all on one daemon thread. Commands and menus only read {@link #snapshot()}, which never blocks. Runs
 * whether or not the free-games system is on.
 */
public final class DealSystem {
    static final Duration AFTER_ERROR = Duration.ofMinutes(30);
    /** The bot needs these in a channel to keep a board there. */
    public static final Permission[] BOARD_PERMISSIONS = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS};
    private static volatile DealSystem instance;

    private final DealConfig config;
    private final DealRepository repository;
    private final ScheduledExecutorService executor;
    private final DealFeed feed;
    private final Clock clock;
    private final DealBoard boards;
    private final DiscordBoards discord;
    private volatile DealSnapshot snapshot;

    private DealSystem(DealConfig config, DealRepository repository, Supplier<JDA> jda, Clock clock) {
        this.config = config;
        this.repository = repository;
        this.clock = clock;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("BunnyDeals").daemon().factory());
        feed = new DealFeed(clock, config.userAgent());
        discord = new DiscordBoards(jda);
        boards = new DealBoard(repository, discord, clock, Duration.ofSeconds(1));
        var saved = repository.loadSnapshot();
        prices = repository.prices();
        snapshot = saved.map(s -> s.withLowest(lowest(() -> DealPrices.current(prices, s.deals(), clock.instant())))).orElse(DealSnapshot.EMPTY);
        var refresh = new DealRefresh(new DealRefresh.Source() {
            @Override public DealFeed.Fetch steamSearch(String order) { return feed.steamSearch(order); }
            @Override public DealFeed.Fetch steamItems(List<Long> appIds) { return feed.steamItems(appIds); }
            @Override public DealFeed.Fetch gog() { return feed.gog(); }
        }, repository, clock, this::snapshot, published -> {
            // Each refresh records its prices; deals at the lowest price tracked for at least two weeks get a badge.
            var marked = published.withLowest(lowest(() -> DealPrices.update(prices, published.deals(), clock.instant())));
            snapshot = marked;
            boards.updateAll(marked);
        });
        repeat(refresh::run, DealRefresh.firstDelay(saved, clock.instant()));
    }

    public static Optional<DealSystem> current() { return Optional.ofNullable(instance); }

    private final DealPrices.Store prices;

    /** Price history is a nice-to-have: if the database refuses it, deals still show, without badges. */
    private static Set<String> lowest(java.util.function.Supplier<Set<String>> read) {
        try { return read.get(); }
        catch (RuntimeException failure) {
            BunnyLog.warning("[Deals] Price history skipped | " + org.bunnys.utils.FailureDiagnostics.describe(failure));
            return Set.of();
        }
    }

    /** Off, with a logged reason, when DEALS_ENABLED=false or there is no database. */
    public static synchronized void start(BunnyHub client) {
        if (instance != null) return;
        List<String> warnings = new ArrayList<>();
        var config = DealConfig.fromEnvironment(warnings);
        warnings.forEach(w -> BunnyLog.warning("[Deals] " + w));
        if (!config.enabled()) { BunnyLog.info("[Deals] Off (DEALS_ENABLED=false)."); return; }
        if (client.getMongoManager() == null || !client.getMongoManager().isConnected()) { BunnyLog.warning("[Deals] Off: no database."); return; }
        try {
            var repository = new DealRepository(DB.getDatabase());
            repository.installIndexes();
            instance = new DealSystem(config, repository, client::getJDA, Clock.systemUTC());
            BunnyLog.success("[Deals] Started. Refresh every 3 h; boards edit in place, repost new deals at most once a day.");
        } catch (RuntimeException failure) {
            BunnyLog.warning("[Deals] Not started | " + FailureDiagnostics.describe(failure));
        }
    }

    public static synchronized void shutdown() {
        var system = instance;
        instance = null;
        if (system == null) return;
        system.executor.shutdownNow();
        system.feed.close();
    }

    /** The last good refresh; {@link DealSnapshot#EMPTY} before the first. */
    public DealSnapshot snapshot() { return snapshot; }
    public DealRepository repository() { return repository; }
    public DealConfig config() { return config; }
    public Instant now() { return clock.instant(); }

    /** After setup turned a board on or moved it: post it now (on the deals thread). */
    public void boardChanged(String guildId) {
        background(() -> boards.updateOne(guildId, snapshot));
    }

    /** After setup turned a board off or moved it: remove the old message (best effort). */
    public void removeBoardMessage(String guildId, String channelId, String messageId) {
        background(() -> discord.delete(guildId, channelId, messageId));
    }

    private void background(Runnable work) {
        try {
            executor.execute(() -> {
                try { work.run(); }
                catch (RuntimeException failure) { BunnyLog.warning("[Deals] Board update failed | " + FailureDiagnostics.describe(failure)); }
            });
        } catch (RejectedExecutionException stopped) { /* shutting down */ }
    }

    /** Runs {@code task} after {@code delay}, then again after whatever delay it returns. The thread never dies. */
    private void repeat(Callable<Duration> task, Duration delay) {
        try {
            executor.schedule(() -> {
                Duration next;
                try { next = task.call(); }
                catch (Exception failure) {
                    BunnyLog.warning("[Deals] Refresh failed; next try in 30 min | " + FailureDiagnostics.describe(failure));
                    next = AFTER_ERROR;
                }
                if (!executor.isShutdown()) repeat(task, next);
            }, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) { /* shutting down */ }
    }

    /** Posts, edits and deletes board messages, with the same checks as free-game delivery. Blocking. */
    private record DiscordBoards(Supplier<JDA> jda) implements DealBoard.Discord {
        @Override public boolean ready() {
            JDA current = jda.get();
            return current != null && current.getStatus() == JDA.Status.CONNECTED;
        }

        @Override public DealBoard.Result post(String guildId, String channelId, MessageCreateData message) {
            var problem = channel(guildId, channelId);
            if (problem.failure != null) return DealBoard.Result.failed(problem.failure);
            try {
                var sent = problem.channel.sendMessage(message).setAllowedMentions(List.of()).submit().get(30, TimeUnit.SECONDS);
                return DealBoard.Result.ok(sent.getId());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return DealBoard.Result.failed("Discord refused the post");
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                BunnyLog.warning("[Deals] Board post in " + guildId + " failed | " + FailureDiagnostics.describe(failure));
                return DealBoard.Result.failed("Discord refused the post");
            }
        }

        @Override public DealBoard.Result edit(String guildId, String channelId, String messageId, MessageEditData message) {
            var problem = channel(guildId, channelId);
            if (problem.failure != null) return DealBoard.Result.failed(problem.failure);
            try {
                problem.channel.editMessageById(messageId, message).submit().get(30, TimeUnit.SECONDS);
                return DealBoard.Result.edited();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return DealBoard.Result.failed("Discord refused the edit");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof ErrorResponseException error && error.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE)
                    return DealBoard.Result.missing();
                BunnyLog.warning("[Deals] Board edit in " + guildId + " failed | " + FailureDiagnostics.describe(failure));
                return DealBoard.Result.failed("Discord refused the edit");
            } catch (TimeoutException | RuntimeException failure) {
                return DealBoard.Result.failed("Discord refused the edit");
            }
        }

        @Override public void delete(String guildId, String channelId, String messageId) {
            var problem = channel(guildId, channelId);
            if (problem.channel == null) return;
            try { problem.channel.deleteMessageById(messageId).submit().get(30, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | TimeoutException | RuntimeException gone) { /* already deleted, or no access: nothing to clean */ }
        }

        private record Channel(StandardGuildMessageChannel channel, String failure) {}

        private Channel channel(String guildId, String channelId) {
            var guild = jda.get().getGuildById(guildId);
            if (guild == null) return new Channel(null, "I'm no longer in this server");
            var channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);
            if (channel == null) return new Channel(null, "the channel was deleted");
            if (!guild.getSelfMember().hasPermission(channel, BOARD_PERMISSIONS)) return new Channel(channel, "I can't post in that channel");
            return new Channel(channel, null);
        }
    }
}
