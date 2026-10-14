package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.bunnys.utils.BunnyLog;
import java.time.Instant;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * Removes settings that point at deleted channels or ping roles. Live delete events call the same writes as the
 * housekeeping sweep, which reconciles deletions missed while the bot was offline.
 *
 * <p>Only a guild the connected gateway has fully loaded is evidence: an uncached, unavailable or disconnected
 * guild is {@link Presence#UNKNOWN}, never deleted.
 */
final class FreebieCleanup {
    enum Presence { PRESENT, DELETED, UNKNOWN }
    record Sweep(long channels, long roles, long cancelled) {}
    static final String CHANNEL_DELETED = "CHANNEL_DELETED";
    static final String CHANNEL_DELETED_TEXT = "The alert channel was deleted; nothing more is sent there.";
    /** Bounds one sweep; every server has at most 5 channels, so this covers well over a thousand servers. */
    static final int SWEEP_SUBSCRIPTIONS = 10_000, SWEEP_DESTINATIONS = 500;

    private final FreebieSubscriptions subscriptions;
    private final FreebieDeliveryStore deliveries;
    private final FreebieNoticeStore notices;

    FreebieCleanup(FreebieSubscriptions subscriptions, FreebieDeliveryStore deliveries, FreebieNoticeStore notices) {
        this.subscriptions = subscriptions; this.deliveries = deliveries; this.notices = notices;
    }

    /** The guild, only when the connected gateway has it loaded and available. */
    static Optional<Guild> loaded(JDA client, String guildId) {
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return Optional.empty();
        long id;
        try { id = Long.parseLong(guildId); } catch (NumberFormatException invalid) { return Optional.empty(); }
        if (client.isUnavailable(id)) return Optional.empty();
        return Optional.ofNullable(client.getGuildById(id));
    }

    static Presence channel(JDA client, String guildId, String channelId) {
        return loaded(client, guildId).map(g -> g.getGuildChannelById(channelId) == null ? Presence.DELETED : Presence.PRESENT)
                .orElse(Presence.UNKNOWN);
    }

    static Presence role(JDA client, String guildId, String roleId) {
        return loaded(client, guildId).map(g -> g.getRoleById(roleId) == null ? Presence.DELETED : Presence.PRESENT)
                .orElse(Presence.UNKNOWN);
    }

    /** Fan-out audience without channels known to be deleted; unknown channels stay so the sender decides later. */
    static Iterable<FreebieSubscriptions.Subscription> deliverable(JDA client, Iterable<FreebieSubscriptions.Subscription> audience) {
        return () -> StreamSupport.stream(audience.spliterator(), false)
                .filter(s -> channel(client, s.guildId(), s.channelId()) != Presence.DELETED).iterator();
    }

    /**
     * A channel was deleted: its subscriptions go, its queued or blocked alerts end as cancelled, and the server
     * owner is told once (DM, or the system channel) which launchers stopped and how to pick a new channel.
     */
    long channelDeleted(String guildId, String channelId, Optional<String> name, Instant now) {
        var stores = subscriptions.forGuild(guildId).stream().filter(s -> s.channelId().equals(channelId))
                .map(s -> s.store().id()).toList();
        long removed = subscriptions.remove(guildId, channelId, Optional.empty());
        long cancelled = deliveries.cancelForChannel(guildId, channelId, now);
        if (removed > 0 && !stores.isEmpty())
            notices.enqueueChange(guildId, FreebieNoticeStore.CHANNEL_DELETED, channelId, name, stores, List.of(channelId), now);
        if (removed + cancelled > 0) BunnyLog.info("[Freebies] Channel " + channelId + " in server " + guildId
                + " was deleted: removed " + removed + " setting(s), cancelled " + cancelled + " alert(s).");
        return cancelled;
    }

    /** A ping role was deleted: alerts keep posting to the same channels, without a ping; the owner is told once. */
    long roleDeleted(String guildId, String roleId, Optional<String> name, Instant now) {
        var affected = subscriptions.forGuild(guildId).stream().filter(s -> s.roleId().filter(roleId::equals).isPresent()).toList();
        long cleared = subscriptions.clearRole(guildId, roleId);
        if (cleared > 0 && !affected.isEmpty())
            notices.enqueueChange(guildId, FreebieNoticeStore.ROLE_DELETED, roleId, name,
                    affected.stream().map(s -> s.store().id()).distinct().toList(),
                    affected.stream().map(FreebieSubscriptions.Subscription::channelId).distinct().toList(), now);
        if (cleared > 0) BunnyLog.info("[Freebies] Ping role " + roleId + " in server " + guildId + " was deleted; "
                + cleared + " setting(s) now post without a ping.");
        return cleared;
    }

    /** Reconciles deletions missed while offline. Bounded; does nothing unless the gateway is connected. */
    Sweep sweep(JDA client, Instant now) {
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return new Sweep(0, 0, 0);
        Set<List<String>> channels = new LinkedHashSet<>(), roles = new LinkedHashSet<>();
        for (var s : subscriptions.all(SWEEP_SUBSCRIPTIONS)) {
            channels.add(List.of(s.guildId(), s.channelId()));
            s.roleId().ifPresent(role -> roles.add(List.of(s.guildId(), role)));
        }
        long removedChannels = 0, clearedRoles = 0, cancelled = 0;
        for (var c : channels)
            if (channel(client, c.get(0), c.get(1)) == Presence.DELETED) { removedChannels++; cancelled += channelDeleted(c.get(0), c.get(1), Optional.empty(), now); }
        for (var r : roles)
            if (role(client, r.get(0), r.get(1)) == Presence.DELETED && roleDeleted(r.get(0), r.get(1), Optional.empty(), now) > 0) clearedRoles++;
        // Open alerts whose subscription is already gone (removed by hand, or by an earlier sweep).
        for (var d : deliveries.openDestinations(SWEEP_DESTINATIONS))
            if (!channels.contains(d) && channel(client, d.get(0), d.get(1)) == Presence.DELETED)
                cancelled += channelDeleted(d.get(0), d.get(1), Optional.empty(), now);
        return new Sweep(removedChannels, clearedRoles, cancelled);
    }
}
