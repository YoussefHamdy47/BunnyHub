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
    static final String SEND = "broadcast-", SEND_QUIET = "broadcastq-", STOP = "broadcaststop";
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

    /**
     * Who gets an announcement. {@code section} empty: every server, in its main channel (the major-PC one when set,
     * otherwise its first alert channel by section). With a section: servers with an alert channel for it, in that
     * channel; a server without one is skipped, or with {@code fallback} gets it in its main channel.
     */
    record Target(Optional<FreebieSection> section, boolean fallback) {
        static final Target ALL = new Target(Optional.empty(), false);
        String describe() {
            return section.map(s -> "servers with a **" + s.label() + "** channel" + (fallback ? " (others: their main channel)" : " (others are skipped)"))
                    .orElse("every server, in its main free-game channel (the major PC one when set)");
        }
    }

    /** One chosen setting per server, with how it was chosen. */
    record Recipient(FreebieSubscriptions.Subscription setting, boolean viaFallback) {}

    /** One channel per subscribed server for {@link Target#ALL}; kept for callers and tests. */
    Map<String, String> recipients() {
        Map<String, String> channels = new TreeMap<>();
        targets().forEach((guild, s) -> channels.put(guild, s.channelId()));
        return channels;
    }

    Map<String, FreebieSubscriptions.Subscription> targets() { return targets(Target.ALL); }

    Map<String, FreebieSubscriptions.Subscription> targets(Target target) {
        Map<String, FreebieSubscriptions.Subscription> chosen = new TreeMap<>();
        recipients(target).forEach((guild, r) -> chosen.put(guild, r.setting()));
        return chosen;
    }

    /** The chosen setting per server: its channel and the free-game role saved with it. */
    Map<String, Recipient> recipients(Target target) {
        Map<String, FreebieSubscriptions.Subscription> main = new TreeMap<>(), inSection = new TreeMap<>();
        for (var s : subscriptions.all(FreebieSubscriptions.MAX_PER_GUILD * 20_000)) {
            keepBest(main, s);
            if (target.section().map(section -> s.store().section() == section).orElse(false)) keepBest(inSection, s);
        }
        Map<String, Recipient> result = new TreeMap<>();
        if (target.section().isEmpty()) { main.forEach((g, s) -> result.put(g, new Recipient(s, false))); return result; }
        main.forEach((g, s) -> {
            if (inSection.containsKey(g)) result.put(g, new Recipient(inSection.get(g), false));
            else if (target.fallback()) result.put(g, new Recipient(s, true));
        });
        return result;
    }

    private static void keepBest(Map<String, FreebieSubscriptions.Subscription> best, FreebieSubscriptions.Subscription s) {
        var current = best.get(s.guildId());
        if (current == null || ORDER.compare(s, current) < 0) best.put(s.guildId(), s);
    }

    static Target target(String section, boolean fallback) {
        return new Target(Optional.ofNullable(section).flatMap(FreebieSection::byCode), fallback);
    }

    private static final Comparator<FreebieSubscriptions.Subscription> ORDER =
            Comparator.comparingInt((FreebieSubscriptions.Subscription s) -> s.store().section().ordinal())
                    .thenComparingInt(s -> s.channelId().length()).thenComparing(FreebieSubscriptions.Subscription::channelId);

    /** Typed "\n" becomes a line break; slash-command options cannot contain real line breaks. */
    static String body(String raw) { return raw == null ? "" : raw.replace("\\n", "\n").strip(); }

    Reply preview(String actor, String title, String rawBody) { return preview(actor, title, rawBody, Target.ALL); }

    Reply preview(String actor, String title, String rawBody, Target target) {
        String body = body(rawBody);
        title = title == null ? "" : title.strip();
        if (title.isEmpty() || body.isEmpty()) return new Problem("Give the announcement a title and a message.");
        if (title.length() > MAX_TITLE || body.length() > MAX_BODY)
            return new Problem("Keep the title within " + MAX_TITLE + " and the message within " + MAX_BODY + " characters.");
        var recipients = recipients(target);
        int servers = recipients.size();
        long viaFallback = recipients.values().stream().filter(Recipient::viaFallback).count();
        if (servers == 0) return new Problem(target.section().isPresent()
                ? "No server has a " + target.section().get().label() + " channel. Choose \"Use its main channel\" for if-missing to reach the others."
                : "No server has free-game alerts set up, so there is nobody to announce to.");
        String token = FreebieReleases.token();
        var draft = repository.broadcasts().draft(title, body, actor, token, clock.instant(),
                target.section().map(s -> String.valueOf(s.code())).orElse(null), target.fallback());
        var preview = FreebieBroadcastWorker.message(title, body, "Bot announcement • ref preview").getEmbeds().getFirst();
        var info = Embeds.of("📣", "Send this announcement?")
                .setDescription("It goes to **" + servers + "** server(s): " + target.describe() + ", one message each"
                        + (viaFallback > 0 ? " (" + viaFallback + " in their main channel because they have no such section)" : "")
                        + ". Servers following an Announcement Channel do not receive it.\n"
                        + "**Send with ping** mentions each server's free-game role (where one is set); **Send without ping** mentions nobody. "
                        + "@everyone and @here are never pinged.\n"
                        + "Only you can confirm, within 15 minutes. Messages cannot be recalled once sent.")
                .setTimestamp(null).build();
        return new Preview(new MessageCreateBuilder().setEmbeds(preview, info)
                .setComponents(ActionRow.of(Button.danger(FreebieMessages.BUTTON_PREFIX + ":" + SEND + token + ":" + draft.id(), "Send with ping"),
                        Button.primary(FreebieMessages.BUTTON_PREFIX + ":" + SEND_QUIET + token + ":" + draft.id(), "Send without ping"),
                        Button.secondary(FreebieMessages.BUTTON_PREFIX + ":" + STOP + ":" + draft.id(), "Discard")))
                .setAllowedMentions(List.of()).build());
    }

    /** The owner's confirmation: DRAFT → SENDING once, then one job per server (idempotent). */
    String confirm(String actor, String id, String token) { return confirm(actor, id, token, false); }

    /** {@code ping}: "Send with ping" (each server's saved free-game role) or "Send without ping". */
    String confirm(String actor, String id, String token, boolean ping) {
        Instant now = clock.instant();
        var broadcast = repository.broadcasts().confirm(id, token, actor, now, ping);
        if (broadcast.isEmpty()) return "This draft expired, was discarded, was already sent, or belongs to another owner. Nothing was sent.";
        int servers = plan(id);
        return "Sending to " + servers + " server(s)" + (ping ? " with each server's free-game role pinged" : " without pings") + ". Progress is in `/freebie-admin status`; use the button below to stop the rest.";
    }

    private int plan(String id) {
        var saved = repository.broadcasts().get(id).orElseThrow();
        var targets = targets(target(saved.section(), saved.fallback()));
        repository.broadcasts().planTargets(id, targets, clock.instant());
        repository.broadcasts().planned(id, targets.size());
        return targets.size();
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
