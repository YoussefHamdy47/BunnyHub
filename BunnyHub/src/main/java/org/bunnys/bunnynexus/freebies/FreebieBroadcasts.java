package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.utils.Embeds;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Owner announcements to every server with free-game alerts (/freebie-admin announce). One message per server, in
 * the server's major-PC alert channel when it has one (otherwise its first alert channel by section), never pinging
 * anyone. The first step only saves a draft and shows the exact message and server count; sending needs the same
 * owner's confirmation within 15 minutes. Owners can stop a broadcast; servers already reached keep the message.
 */
public final class FreebieBroadcasts {
    public static final int MAX_TITLE = 200, MAX_BODY = 3500;
    static final String SEND = "broadcast-", STOP = "broadcaststop";
    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;
    private final Clock clock;
    private final Consumer<String> notifyOwners;

    FreebieBroadcasts(FreebieRepository repository, FreebieSubscriptions subscriptions, Clock clock, Consumer<String> notifyOwners) {
        this.repository = repository; this.subscriptions = subscriptions; this.clock = clock; this.notifyOwners = notifyOwners;
    }

    public sealed interface Reply permits Problem, Preview {}
    public record Problem(String text) implements Reply {}
    public record Preview(MessageCreateData message) implements Reply {}

    /** One channel per subscribed server: the major-PC section's channel first, then by section and channel ID. */
    Map<String, String> recipients() {
        Map<String, FreebieSubscriptions.Subscription> best = new TreeMap<>();
        for (var s : subscriptions.all(FreebieSubscriptions.MAX_PER_GUILD * 20_000)) {
            var current = best.get(s.guildId());
            if (current == null || ORDER.compare(s, current) < 0) best.put(s.guildId(), s);
        }
        Map<String, String> channels = new TreeMap<>();
        best.forEach((guild, s) -> channels.put(guild, s.channelId()));
        return channels;
    }

    private static final Comparator<FreebieSubscriptions.Subscription> ORDER =
            Comparator.comparingInt((FreebieSubscriptions.Subscription s) -> s.store().section().ordinal())
                    .thenComparingInt(s -> s.channelId().length()).thenComparing(FreebieSubscriptions.Subscription::channelId);

    /** Typed "\n" becomes a line break; slash-command options cannot contain real line breaks. */
    static String body(String raw) { return raw == null ? "" : raw.replace("\\n", "\n").strip(); }

    Reply preview(String actor, String title, String rawBody) {
        String body = body(rawBody);
        title = title == null ? "" : title.strip();
        if (title.isEmpty() || body.isEmpty()) return new Problem("Give the announcement a title and a message.");
        if (title.length() > MAX_TITLE || body.length() > MAX_BODY)
            return new Problem("Keep the title within " + MAX_TITLE + " and the message within " + MAX_BODY + " characters.");
        int servers = recipients().size();
        if (servers == 0) return new Problem("No server has free-game alerts set up, so there is nobody to announce to.");
        String token = FreebieReleases.token();
        var draft = repository.broadcasts().draft(title, body, actor, token, clock.instant());
        var preview = FreebieBroadcastWorker.message(title, body, "Bot announcement • ref preview").getEmbeds().getFirst();
        var info = Embeds.of("📣", "Send this announcement?")
                .setDescription("It goes to **" + servers + "** server(s): one message each, in the server's free-game alert channel "
                        + "(the major PC one when set). Nobody is pinged. Servers following an Announcement Channel do not receive it.\n"
                        + "Only you can confirm, within 15 minutes. Messages cannot be recalled once sent.")
                .setTimestamp(null).build();
        return new Preview(new MessageCreateBuilder().setEmbeds(preview, info)
                .setComponents(ActionRow.of(Button.danger(FreebieMessages.BUTTON_PREFIX + ":" + SEND + token + ":" + draft.id(), "Send to " + servers + " server(s)"),
                        Button.secondary(FreebieMessages.BUTTON_PREFIX + ":" + STOP + ":" + draft.id(), "Discard")))
                .setAllowedMentions(List.of()).build());
    }

    /** The owner's confirmation: DRAFT → SENDING once, then one job per server (idempotent). */
    String confirm(String actor, String id, String token) {
        Instant now = clock.instant();
        var broadcast = repository.broadcasts().confirm(id, token, actor, now);
        if (broadcast.isEmpty()) return "This draft expired, was discarded, was already sent, or belongs to another owner. Nothing was sent.";
        int servers = plan(id);
        return "Sending to " + servers + " server(s). Progress is in `/freebie-admin status`; use the button below to stop the rest.";
    }

    private int plan(String id) {
        var recipients = recipients();
        repository.broadcasts().plan(id, recipients, clock.instant());
        repository.broadcasts().planned(id, recipients.size());
        return recipients.size();
    }

    String stop(String actor, String id) {
        long cancelled = repository.broadcasts().cancel(id, actor, clock.instant());
        if (cancelled < 0) return "This announcement already finished or was stopped.";
        return "Stopped. " + cancelled + " queued server(s) cancelled; up to " + FreebieBroadcastWorker.MAX_IN_FLIGHT + " in flight may still arrive.";
    }

    static List<ActionRow> stopControls(String id) {
        return List.of(ActionRow.of(Button.danger(FreebieMessages.BUTTON_PREFIX + ":" + STOP + ":" + id, "Stop this announcement")));
    }

    /** Housekeeping: plans broadcasts a crash interrupted and reports finished ones once. */
    void housekeeping() {
        var store = repository.broadcasts();
        if (store == null) return;
        for (var b : store.unplanned(5)) plan(b.id());
        for (var b : store.sending(20))
            if (store.finishIfDone(b.id(), clock.instant())) {
                var c = store.counts(b.id());
                notifyOwners.accept("📢 Announcement **" + FreebieMessages.text(b.title(), 100) + "** finished: " + c.sent() + " sent, "
                        + c.skipped() + " skipped (no access or channel gone), " + c.failed() + " failed.");
            }
    }

    String status() {
        var store = repository.broadcasts();
        if (store == null) return "";
        var sending = store.sending(3);
        if (sending.isEmpty()) return "Announcements: none sending";
        var lines = new StringJoiner("\n");
        for (var b : sending) {
            var c = store.counts(b.id());
            lines.add("Announcement **" + FreebieMessages.text(b.title(), 60) + "**: " + c.sent() + "/" + c.total() + " sent, " + c.open() + " queued");
        }
        return lines.toString();
    }
}
