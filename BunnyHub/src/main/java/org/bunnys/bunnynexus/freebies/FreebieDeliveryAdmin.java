package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bson.Document;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Private owner views and exact, single-destination retry confirmations. */
public final class FreebieDeliveryAdmin {
    public record View(String text, List<ActionRow> rows) {}
    private final FreebieConfig config;
    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;
    private final Supplier<JDA> jda;
    private final Clock clock;
    FreebieDeliveryAdmin(FreebieConfig config, FreebieRepository repository, FreebieSubscriptions subscriptions,
                        Supplier<JDA> jda, Clock clock) {
        this.config = config; this.repository = repository; this.subscriptions = subscriptions;
        this.jda = jda; this.clock = clock;
    }
    private void authorize(String actor) {
        if (!config.isOwner(actor)) throw new SecurityException("Not a freebie owner.");
    }
    public View audit(String actor, String offerId, String channel, int page) {
        authorize(actor);
        if (page < 1 || page > 20) return text("Choose an audit page from 1 to 20.");
        var d = repository.deliveries().get(offerId + "|" + channel).orElse(null);
        if (d == null) return text("No delivery found.");
        var records = d.getList("retryAudit", Document.class, List.of());
        var text = new StringBuilder("Retry audit for `").append(offerId).append("` / `").append(channel)
                .append("`, page ").append(page).append(". Current outcome: ").append(d.getString("state")).append('\n');
        records.stream().skip((long) (page - 1) * 5).limit(5).forEach(a -> text.append("\nActor `")
                .append(a.getString("actor")).append("` at ").append(time(a, "at"))
                .append("; previous outcome ").append(a.getString("previousState"))
                .append("; claims ").append(a.getInteger("attempts", 0))
                .append("; uncertain ").append(a.getBoolean("uncertain", false))
                .append("; previous finish ").append(time(a, "finishedAt"))
                .append(a.getString("blockedReason") == null ? "" : "\n" + FreebieDeliveryStore.reason(a))
                .append(a.getString("failure") == null ? "" : "\n" + failure(a)).append('\n'));
        text.append("\nUse history with channel and audit-page to inspect other pages (five records per page).");
        return text(text.toString());
    }
    public View history(String actor, String offerId, String after) {
        authorize(actor);
        var page = repository.deliveries().page(offerId, after);
        StringBuilder text = new StringBuilder("Delivery history • ").append(FreebieMessages.text(offerId, 60))
                .append("\nCurrent outcomes; posted summaries remain unchanged.\n");
        for (var d : page.stream().limit(FreebieDeliveryStore.PAGE_SIZE).toList()) {
            text.append("\nServer `").append(d.getString("guildId")).append("`, channel `").append(d.getString("channelId"))
                    .append("`: **").append(d.getString("state")).append("**")
                    .append(FreebieDeliveryStore.uncertain(d) ? " (acceptance uncertain)" : "")
                    .append("\nClaims: ").append(d.getInteger("attempts", 0)).append(" • submissions: ")
                    .append(d.getInteger("sendAttempts", 0)).append(" • owner retries: ").append(d.getInteger("manualRetries", 0))
                    .append("\nCreated ").append(time(d, "createdAt")).append("; attempted ").append(time(d, "lastAttemptAt"))
                    .append("; finished ").append(time(d, "finishedAt")).append("; next check ").append(time(d, "nextAttemptAt"));
            if (d.getString("failure") != null) text.append("\n").append(failure(d));
            if (d.getString("blockedReason") != null) text.append("\n").append(FreebieDeliveryStore.reason(d));
            if (d.getString("messageId") != null) text.append("\n[Discord message](https://discord.com/channels/")
                    .append(d.getString("guildId")).append('/').append(d.getString("channelId")).append('/')
                    .append(d.getString("messageId")).append(')');
            var audit = d.getList("retryAudit", Document.class, List.of());
            if (!audit.isEmpty()) {
                var last = audit.getLast();
                text.append("\nLast retry: actor `").append(last.getString("actor")).append("` ").append(time(last, "at"))
                        .append("; previous ").append(last.getString("previousState"));
            }
            text.append('\n');
        }
        if (page.isEmpty()) text.append("No destinations on this page.");
        var rows = page.size() > FreebieDeliveryStore.PAGE_SIZE
                ? List.of(ActionRow.of(Button.secondary("freebie_delivery:page:" + offerId.substring("gamerpower:".length())
                    + ":" + page.get(FreebieDeliveryStore.PAGE_SIZE - 1).getString("channelId"), "Next page"))) : List.<ActionRow>of();
        return new View(text.toString(), rows);
    }
    public View preview(String actor, String offerId, String channel) {
        authorize(actor);
        var d = repository.deliveries().get(offerId + "|" + channel).orElse(null);
        if (d == null || !eligible(d)) return text("This destination is not eligible. Check offer state, subscription and permissions.");
        if (d.getInteger("manualRetries", 0) >= 100) return text("This delivery has reached its owner retry limit (100).");
        var token = repository.deliveries().preview(d, actor, clock.instant());
        if (token.isEmpty()) return text("The delivery changed. Request a new preview.");
        return new View("Retry exactly **one** destination for `" + offerId + "`: server `" + d.getString("guildId")
                + "`, channel `" + channel + "`.\nCurrent outcome: **" + d.getString("state")
                + "**. Saved ping role: " + Optional.ofNullable(d.getString("roleId")).orElse("none") + ". Attempts so far: " + d.getInteger("attempts", 0)
                + ".\nUncertain acceptance requires history verification; the same destination, reference and nonce are retained."
                + "\nConfirmation expires in five minutes. Results appear in delivery history; an existing summary is not reposted.",
                List.of(ActionRow.of(Button.danger("freebie_delivery:retry:" + offerId.substring("gamerpower:".length())
                        + ":" + channel + ":" + token.get(), "Confirm this retry"))));
    }
    public View confirm(String actor, String offerId, String channel, String token) {
        authorize(actor);
        var d = repository.deliveries().get(offerId + "|" + channel).orElse(null);
        if (d == null || !eligible(d)) return text("No retry queued: the offer or destination is no longer eligible.");
        boolean queued = repository.deliveries().retry(d, actor, token, clock.instant());
        // A cross-document stop can race the update; the sender also checks immediately before REST submission.
        var offer = repository.offer(offerId).orElse(null);
        if (queued && !live(offer)) {
            repository.cancelPending(offerId, "offer ended or stopped during retry confirmation", clock.instant());
            return text("The offer ended or stopped; queued work was cancelled.");
        }
        return text(queued ? "Retry queued on the freebie sender. Follow its result in delivery history. Existing summaries stay unchanged."
                : "Confirmation expired, already used, or delivery changed. Nothing queued.");
    }
    private boolean eligible(Document d) {
        if (!Set.of("FAILED", "BLOCKED").contains(d.getString("state"))) return false;
        var offer = repository.offer(d.getString("offerId")).orElse(null);
        return live(offer) && destination(d, offer, subscriptions, jda.get());
    }
    private boolean live(FreebieOffer offer) {
        return offer != null && offer.decidedBy().isPresent() && !offer.ended(clock.instant())
                && (offer.state() == FreebieOffer.State.APPROVED || offer.state() == FreebieOffer.State.COMPLETED);
    }
    static boolean destination(Document d, FreebieOffer offer, FreebieSubscriptions subscriptions, JDA client) {
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return false;
        var guild = client.getGuildById(d.getString("guildId"));
        if (guild == null || client.isUnavailable(guild.getIdLong())) return false;
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, d.getString("channelId"));
        if (channel == null || !guild.getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) return false;
        if (FreebieDeliveryStore.uncertain(d) && !guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY)) return false;
        String roleId = d.getString("roleId");
        if (roleId != null) {
            var role = guild.getRoleById(roleId);
            if (role == null || role.isPublicRole() || (!role.isMentionable()
                    && !guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE))) return false;
        }
        return subscriptions.forGuild(d.getString("guildId")).stream().anyMatch(s -> s.store() == offer.store()
                && s.channelId().equals(d.getString("channelId")) && s.roleId().equals(Optional.ofNullable(d.getString("roleId"))));
    }
    static String time(Document d, String key) {
        return d.getDate(key) == null ? "—" : "<t:" + d.getDate(key).toInstant().getEpochSecond() + ":f>";
    }
    private static String failure(Document d) {
        try { return FreebieFailure.valueOf(d.getString("failure")).explanation(); }
        catch (IllegalArgumentException invalid) { return "Delivery failed."; }
    }
    private static View text(String text) { return new View(text, List.of()); }
}




