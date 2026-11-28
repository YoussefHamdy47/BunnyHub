package org.bunnys.bunnynexus.updates;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bunnys.bunnynexus.deals.DealRepository;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.cards.Card;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Product updates (docs/product-updates/README.md): the owner previews one catalog entry, sees who gets it and why
 * the rest are skipped, and confirms within 15 minutes; then one message per server goes out on its own thread, a
 * second apart, each recorded as sent so a restart or a second confirmation never sends twice.
 */
public final class ProductUpdates {
    static final Duration CONFIRM_WITHIN = Duration.ofMinutes(15), SPACING = Duration.ofMillis(1500);
    public static final String SEND = "send", CANCEL = "cancel";
    private static volatile ProductUpdates instance;

    /** A previewed update waiting for its owner's confirmation. */
    record Pending(ProductUpdate update, String ownerId, Instant expires) {}

    private final UpdatePrefs prefs;
    private final AdStore ads;
    private final Supplier<JDA> jda;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("BunnyUpdates").daemon().factory());
    private final SecureRandom random = new SecureRandom();

    ProductUpdates(UpdatePrefs prefs, AdStore ads, Supplier<JDA> jda, Clock clock) {
        this.prefs = prefs; this.ads = ads; this.jda = jda; this.clock = clock;
    }

    public static synchronized void start(org.bunnys.handler.BunnyHub client) {
        if (instance != null || client.getMongoManager() == null || !client.getMongoManager().isConnected()) return;
        var db = org.bunnys.handler.database.DB.getDatabase();
        instance = new ProductUpdates(new UpdatePrefs(db), new AdStore(db), client::getJDA, Clock.systemUTC());
        instance.users = new UpdatePrefs(db, USER_COLLECTION);
        org.bunnys.bunnynexus.freebies.FreebiePromos.use(instance::adFor);
        try {
            // Services start once Discord is ready, so the bot's own id is known for the invite button.
            var jda = client.getJDA();
            int written = jda == null ? 0 : instance.ads.seed(StarterAds.all(jda.getSelfUser().getApplicationId(), Instant.now()));
            if (written > 0) BunnyLog.info("[Updates] Added or updated " + written + " of the bot's own ads.");
        } catch (RuntimeException failure) {
            BunnyLog.warning("[Updates] Starter ads not added | " + FailureDiagnostics.describe(failure));
        }
    }

    public static synchronized void shutdown() {
        var current = instance;
        instance = null;
        org.bunnys.bunnynexus.freebies.FreebiePromos.use(null);
        if (current != null) current.sender.shutdownNow();
    }

    public static Optional<ProductUpdates> current() { return Optional.ofNullable(instance); }
    public UpdatePrefs prefs() { return prefs; }
    public AdStore ads() { return ads; }

    /** Every registered server with its main channel, what it uses and its preferences. Blocking. */
    List<UpdateAudience.Server> servers() {
        var channels = FreebieSystem.current().map(FreebieSystem::mainChannels).orElse(Map.of());
        var saved = prefs.get(channels.keySet());
        // One query for every server's board instead of one per server.
        Set<String> boards = DealSystem.current().map(d -> d.repository().guildsWithBoardOn()).orElse(Set.of());
        List<UpdateAudience.Server> servers = new ArrayList<>();
        channels.forEach((guild, channel) -> servers.add(new UpdateAudience.Server(guild, channel,
                boards.contains(guild) ? EnumSet.of(ProductUpdate.Feature.DEALS_BOARD) : Set.of(), saved.get(guild))));
        return servers;
    }

    /** The features this server already uses. Blocking. */
    static Set<ProductUpdate.Feature> uses(String guildId) {
        Set<ProductUpdate.Feature> uses = EnumSet.noneOf(ProductUpdate.Feature.class);
        if (DealSystem.current().map(DealSystem::repository).flatMap(r -> r.board(guildId)).filter(DealRepository.Board::on).isPresent())
            uses.add(ProductUpdate.Feature.DEALS_BOARD);
        return uses;
    }

    /**
     * The ad card under this server's next free-game alert, counted as shown (docs/product-updates, "Ads in alerts").
     * Cheapest checks first: no active ads costs nothing, a server not yet due costs one read, and the board is only
     * read when an ad's audience depends on it. The claim is atomic, so parallel alerts never double up.
     */
    Optional<net.dv8tion.jda.api.components.container.Container> adFor(String guildId, Instant now) { return adFor(guildId, now, List.of()); }

    private final AdContexts contexts = new AdContexts();

    /**
     * For the owner's showcase: every active ad as this server would see it now, filled with live data (live ads that
     * have nothing to say right now come back unfilled, flagged false), and the ad this alert would carry if the server
     * were due one. Nothing is counted. Blocking.
     */
    public record Showcase(List<Map.Entry<Ad, Boolean>> ads, Optional<Ad> forAlert) {}

    public Showcase showcase(String guildId, List<org.bunnys.bunnynexus.freebies.FreebieOffer> page) {
        Instant now = clock.instant();
        var context = contexts.build(guildId, page, now);
        List<Map.Entry<Ad, Boolean>> all = new ArrayList<>();
        for (Ad ad : ads.active()) {
            var filled = AdTemplate.render(ad, context);
            all.add(Map.entry(filled.orElse(ad), filled.isPresent()));
        }
        return new Showcase(all, UpdateAudience.ad(ads.active(), uses(guildId), UpdateAudience.PromoState.NONE, now, context));
    }

    /** Each person's ad history for private command replies, by user id. */
    static final String USER_COLLECTION = "UserAdPrefs";
    /** Private replies: at most one ad per person this often, and each ad at most this many times per person. */
    public static final Duration USER_EVERY = Duration.ofDays(3);
    public static final int USER_TIMES = 3;
    private volatile UpdatePrefs users;

    /**
     * An ad for a private reply to {@code userId} ({@code /free-games list}, {@code /deals}, a mention), counted as
     * shown, or nothing when this person saw one in the last {@link #USER_EVERY}. Server-only ads (a Deals Board pitch)
     * are judged by the server the command ran in; in DMs only ads for every server qualify. Blocking.
     */
    public Optional<net.dv8tion.jda.api.components.container.Container> adForUser(String userId, Optional<String> guildId) {
        var people = users;
        if (people == null) return Optional.empty();
        Instant now = clock.instant();
        var active = ads.active();
        if (active.isEmpty()) return Optional.empty();
        var state = people.promoState(userId);
        if (state.lastAt().filter(last -> last.plus(USER_EVERY).isAfter(now)).isPresent()) return Optional.empty();
        Set<ProductUpdate.Feature> uses = guildId.map(ProductUpdates::uses).orElse(EnumSet.of(ProductUpdate.Feature.DEALS_BOARD));
        var context = active.stream().anyMatch(Ad::live) ? contexts.build(guildId.orElse(""), List.of(), now) : AdContext.NONE;
        var capped = new UpdateAudience.PromoState(state.lastAt(), cap(state.shown()));
        var ad = UpdateAudience.ad(active, uses, capped, now, context);
        if (ad.isEmpty() || !people.claimPromo(userId, ad.get().id(), now, USER_EVERY, USER_TIMES)) return Optional.empty();
        return ad.map(AdCards::card);
    }

    /** Counts at {@link #USER_TIMES} read as used up, since the selection itself allows {@link UpdateAudience#PROMO_TIMES}. */
    static Map<String, Integer> cap(Map<String, Integer> shown) {
        Map<String, Integer> capped = new HashMap<>();
        shown.forEach((id, n) -> capped.put(id, n >= USER_TIMES ? UpdateAudience.PROMO_TIMES : n));
        return capped;
    }

    /** As {@link #adForUser}, from anywhere: empty while product updates are not running. */
    public static Optional<net.dv8tion.jda.api.components.container.Container> userAd(String userId, Optional<String> guildId) {
        try { return current().flatMap(u -> u.adForUser(userId, guildId)); }
        catch (RuntimeException failure) {
            BunnyLog.warning("[Updates] Ad skipped for a reply | " + FailureDiagnostics.describe(failure));
            return Optional.empty();
        }
    }

    /**
     * The ad as a server would see it right now, for the owner's preview: live ads filled from today's deals and free
     * games. Empty when a live ad has nothing to say at the moment. Blocking.
     */
    public Optional<Ad> filled(Ad ad, String guildId) {
        return AdTemplate.render(ad, ad.live() ? contexts.build(guildId, List.of(), clock.instant()) : AdContext.NONE);
    }

    /** As above, for an alert carrying these games: live ads may talk about them, or about what else is free or on sale. */
    Optional<net.dv8tion.jda.api.components.container.Container> adFor(String guildId, Instant now,
                                                                        List<org.bunnys.bunnynexus.freebies.FreebieOffer> page) {
        var active = ads.active();
        if (active.isEmpty()) return Optional.empty();
        var state = prefs.promoState(guildId);
        if (state.lastAt().filter(last -> last.plus(UpdateAudience.PROMO_EVERY).isAfter(now)).isPresent()) return Optional.empty();
        boolean needsBoard = active.stream().anyMatch(a -> a.audience() != Ad.Audience.ALL);
        var context = active.stream().anyMatch(Ad::live) ? contexts.build(guildId, page, now) : AdContext.NONE;
        var ad = UpdateAudience.ad(active, needsBoard ? uses(guildId) : Set.of(), state, now, context);
        if (ad.isEmpty() || !prefs.claimPromo(guildId, ad.get().id(), now, UpdateAudience.PROMO_EVERY, UpdateAudience.PROMO_TIMES))
            return Optional.empty();
        return ad.map(AdCards::card);
    }

    /** The owner's preview: the message exactly as servers get it, who gets it, and Send / Cancel. Blocking. */
    public Card preview(ProductUpdate update, String ownerId) {
        var plan = UpdateAudience.plan(update, servers(), clock.instant());
        pending.values().removeIf(p -> p.expires().isBefore(clock.instant()));
        if (plan.send().isEmpty()) return previewCard(update, plan, null);
        String token = Long.toHexString(random.nextLong());
        pending.put(token, new Pending(update, ownerId, clock.instant().plus(CONFIRM_WITHIN)));
        return previewCard(update, plan, token);
    }

    /** The preview card; {@code token} null when nobody would get it (no buttons). */
    static Card previewCard(ProductUpdate update, UpdateAudience.Plan plan, String token) {
        var summary = new StringBuilder("Goes to **").append(plan.send().size()).append("** server(s), one message each in their main free-game channel, no ping.");
        plan.skipped().forEach((reason, count) -> summary.append("\n-# Skipped ").append(count).append(": ").append(switch (reason) {
            case TURNED_OFF -> "turned product updates off";
            case ALREADY_SENT -> "already got this one";
            case COOLDOWN -> "got one in the last 14 days";
            case USES_IT -> "already use it";
        }));
        var builder = Card.builder().title("Send this product update?").text(summary.toString()).divider()
                .text(UpdateCards.update(update).plainText());
        if (token == null) return builder.text("Nobody to send to right now.").build();
        return builder.buttons(Button.primary(UpdateCards.PREFIX + ":" + SEND + ":" + token, "Send to " + plan.send().size() + " server(s)"),
                Button.secondary(UpdateCards.PREFIX + ":" + CANCEL + ":" + token, "Cancel")).build();
    }

    /** The owner pressed Send: starts sending in the background and says how many it is sending to. */
    public String confirm(String token, String ownerId) {
        var p = pending.remove(token);
        if (p == null || !p.ownerId().equals(ownerId) || p.expires().isBefore(clock.instant()))
            return "This preview expired or belongs to someone else. Nothing was sent.";
        var plan = UpdateAudience.plan(p.update(), servers(), clock.instant());
        sender.execute(() -> send(p.update(), plan.send()));
        return "Sending **" + p.update().title() + "** to " + plan.send().size() + " server(s), one every " + SPACING.toMillis() / 1000.0 + " s.";
    }

    public void cancel(String token) { pending.remove(token); }

    private void send(ProductUpdate update, List<UpdateAudience.Server> servers) {
        int sent = 0, failed = 0;
        var message = UpdateCards.update(update).message();
        for (var server : servers) {
            if (Thread.currentThread().isInterrupted()) break;
            // Checked again right before sending: an admin may have turned updates off since the preview.
            if (UpdateAudience.skip(update, new UpdateAudience.Server(server.guildId(), server.channelId(), server.uses(),
                    prefs.get(server.guildId())), clock.instant()).isPresent()) continue;
            try {
                var guild = jda.get().getGuildById(server.guildId());
                var channel = guild == null ? null : guild.getChannelById(StandardGuildMessageChannel.class, server.channelId());
                if (channel == null || !guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) { failed++; continue; }
                channel.sendMessage(message).setAllowedMentions(List.of()).submit().get(30, TimeUnit.SECONDS);
                prefs.sent(server.guildId(), update.id(), clock.instant());
                sent++;
                Thread.sleep(SPACING);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                failed++;
                BunnyLog.warning("[Updates] " + update.id() + " to " + server.guildId() + " failed | " + FailureDiagnostics.describe(failure));
            }
        }
        BunnyLog.info("[Updates] " + update.id() + ": sent to " + sent + ", could not send to " + failed + ".");
    }
}
