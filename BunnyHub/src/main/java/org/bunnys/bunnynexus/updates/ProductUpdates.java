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
        org.bunnys.bunnynexus.freebies.FreebiePromos.use(instance::adFor);
        try {
            // Services start once Discord is ready, so the bot's own id is known for the invite button.
            var jda = client.getJDA();
            if (jda != null && instance.ads.seedOnce(StarterAds.all(jda.getSelfUser().getApplicationId(), Instant.now())))
                BunnyLog.info("[Updates] Added the starter ads (get-bunnynexus, deals-board).");
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
        List<UpdateAudience.Server> servers = new ArrayList<>();
        channels.forEach((guild, channel) -> servers.add(new UpdateAudience.Server(guild, channel, uses(guild), saved.get(guild))));
        return servers;
    }

    /** The features this server already uses. Blocking. */
    static Set<ProductUpdate.Feature> uses(String guildId) {
        Set<ProductUpdate.Feature> uses = EnumSet.noneOf(ProductUpdate.Feature.class);
        if (DealSystem.current().map(DealSystem::repository).flatMap(r -> r.board(guildId)).filter(DealRepository.Board::on).isPresent())
            uses.add(ProductUpdate.Feature.DEALS_BOARD);
        return uses;
    }

    /** The ad card under this server's next free-game alert, counted as shown (docs/product-updates, "Ads in alerts"). */
    Optional<net.dv8tion.jda.api.components.container.Container> adFor(String guildId, Instant now) {
        var active = ads.active();
        if (active.isEmpty()) return Optional.empty();
        var ad = UpdateAudience.ad(active, uses(guildId), prefs.promoState(guildId), now);
        ad.ifPresent(chosen -> prefs.promoShown(guildId, chosen.id(), now));
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
