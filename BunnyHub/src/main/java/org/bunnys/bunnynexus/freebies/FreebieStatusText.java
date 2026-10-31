package org.bunnys.bunnynexus.freebies;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;

/** Blocks of the owner-only {@code /freebie-admin status} text. Counts only; never user or subscriber IDs. */
final class FreebieStatusText {
    private FreebieStatusText() {}

    /** The whole owner panel, at most about 4000 characters. */
    static String render(FreebieRepository repository, FreebieConfig config, String discovery, int inFlight,
                         net.dv8tion.jda.api.JDA client, Instant now) {
        var overview = repository.overview();
        String text = "**Free-game alerts**\n"
                + "Sending: " + (repository.sendingPaused() ? "⏸️ **paused**" : "▶️ running") + "\n"
                + discovery + "\n"
                + "Waiting for review: " + overview.pendingReviews() + "\n"
                + "Offers sending: " + overview.sending() + " • queued messages: " + overview.queuedDeliveries()
                + " (" + overview.retrying() + " retrying)\n"
                + "In flight now: " + inFlight + " • checked <t:" + now.getEpochSecond() + ":T>"
                + "\nMemory: " + org.bunnys.utils.MemoryStats.read().line()
                + blocked(repository.deliveries().blocked())
                + lifecycle(repository.lifecycle().stats(FreebieLifecycle.Kind.EDIT, now),
                        repository.lifecycle().stats(FreebieLifecycle.Kind.REMIND, now), now)
                + dms(repository.dmSubscribers().active(), config.dmMaxSubscribers(), repository.dmJobs().stats(), now)
                + retention(config.retentionDays(), repository.retention().last())
                + FreebieHealth.render(repository.health().snapshot(now), client, config, now);
        return text.length() <= 4000 ? text : text.substring(0, 3900) + "\nDetails shortened; use delivery history for complete destination pages.";
    }

    static String blocked(List<Document> blocked) {
        StringBuilder result = new StringBuilder("\n**Blocked alerts** (up to 3; use history for all)\n");
        for (var d : blocked) result.append('`').append(d.getString("offerId"))
                .append("` / `").append(d.getString("channelId")).append("`: ")
                .append(FreebieDeliveryStore.reason(d)).append(" Next check ")
                .append(FreebieDeliveryAdmin.time(d, "nextAttemptAt")).append('\n');
        return result.toString();
    }

    static String lifecycle(FreebieLifecycleStore.Stats edits, FreebieLifecycleStore.Stats reminders, Instant now) {
        return "\n**Card updates**\n" + line("Ended-card edits", edits.pending(), edits.oldest(), now) + " • **" + edits.failed() + "** failed\n"
                + line("Ending-soon reminders", reminders.pending(), reminders.oldest(), now) + " • **" + reminders.failed() + "** failed\n";
    }

    /** Subscriber count against the cap, and DM job counts; no subscriber is ever named. */
    static String dms(long subscribers, int cap, FreebieDmJobs.Stats jobs, Instant now) {
        return "\n**Personal DM alerts** " + (cap == 0 ? "(off: FREEBIE_DM_MAX_SUBSCRIBERS=0)" : "") + "\n"
                + "Subscribers: **" + subscribers + "** / " + cap + "\n"
                + line("Game DMs", jobs.pending(), jobs.oldest(), now) + " • **" + jobs.failed() + "** failed • **"
                + jobs.skipped() + "** skipped (daily cap)\n";
    }

    /**
     * Automatic publishing state. "Published" means Discord accepted the crosspost (or the message was already
     * published); it does not confirm delivery to any following server.
     */
    static String publishing(FreebieFeatures features, java.util.Map<String, Long> counts) {
        String state = features.publishEnabled() ? "on for " + features.publishChannels().size() + " announcement channel(s)" : "off";
        long queued = counts.getOrDefault("QUEUED", 0L) + counts.getOrDefault("PUBLISHING", 0L);
        return "Automatic publishing: " + state + " • queued " + queued + " • published " + counts.getOrDefault("PUBLISHED", 0L)
                + " • delayed " + counts.getOrDefault("DELAYED", 0L) + " • uncertain " + counts.getOrDefault("UNCERTAIN", 0L)
                + " • blocked " + counts.getOrDefault("BLOCKED", 0L) + " • failed " + counts.getOrDefault("FAILED", 0L)
                + " • skipped " + counts.getOrDefault("SKIPPED", 0L)
                + "\n(published = accepted by Discord for followers; delivery to each following server is not confirmed)";
    }

    static String retention(int days, Optional<FreebieRetention.Last> last) {
        return "\n**Data retention** (" + days + " days)\n" + last.map(l -> "Last purge <t:"
                + l.at().getEpochSecond() + ":R>: " + l.deliveries() + " alert(s), " + l.offers() + " offer(s), "
                + l.notices() + " notice record(s) removed.").orElse("No purge has run yet.") + "\n";
    }

    private static String line(String label, long pending, Instant oldest, Instant now) {
        return label + ": **" + pending + "** pending" + (oldest == null ? ""
                : " • oldest " + Math.max(0, Duration.between(oldest, now).toMinutes()) + "m");
    }
}
