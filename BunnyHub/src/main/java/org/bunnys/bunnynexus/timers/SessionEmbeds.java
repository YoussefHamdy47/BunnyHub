package org.bunnys.bunnynexus.timers;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.bunnynexus.timers.services.SessionClock;
import org.bunnys.bunnynexus.timers.services.SubjectTopics;
import org.bunnys.bunnynexus.timers.services.TimerSessionService;
import org.bunnys.database.models.timers.Session;
import org.bunnys.utils.AppDesign.Emojis;
import org.bunnys.utils.Durations;
import org.bunnys.utils.Embeds;

/** The small embeds of a study session's life cycle: telemetry, pause/resume notes, and how it ended. */
public final class SessionEmbeds {
    private SessionEmbeds() {}

    public static MessageEmbed telemetry(Session session, long nowMillis) {
        if (session.getSessionStartTime() == null)
            return Embeds.of(Emojis.DIAMOND_SPIN, "Live Telemetry", "No study session is running.").build();

        String topic = session.getSessionTopic();
        var clock = SessionClock.of(session, nowMillis);
        long start = session.getSessionStartTime().getTime() / 1000;
        StringBuilder info = new StringBuilder()
                .append("✦ **Start Time:** <t:").append(start).append(":f>\n")
                .append("✦ **Current Uptime:** <t:").append(start).append(":R>\n\n")
                .append("✦ **Net Study Time:** `").append(Durations.formatSeconds(clock.activeStudy())).append("`\n")
                .append("✦ **Total Break Time:** `").append(Durations.formatSeconds(clock.breaks())).append("`\n")
                .append("✦ **Break Count:** `").append(session.getNumberOfBreaks()).append("`\n");
        if (session.getSessionBreaks().getSessionBreakStart() != null)
            info.append("\n> ⏸ *Currently on a break. Duration: ").append(Durations.formatSeconds(clock.openBreak())).append(".*");
        else
            info.append("\n> ▶ *Telemetry feed active and recording.*");
        return Embeds.of(Emojis.DIAMOND_SPIN, "Live Telemetry | " + (topic != null ? SubjectTopics.code(topic) : "UNKNOWN"),
                info.toString()).build();
    }

    public static MessageEmbed paused(double studySeconds, long nowMillis) {
        return Embeds.of("⏸️", "Session Paused", "✦ Time Logged: `" + Durations.formatSeconds(studySeconds) + "`\n"
                + "✦ Break Started: <t:" + nowMillis / 1000 + ":R>").build();
    }

    public static MessageEmbed resumed(long breakMillis) {
        boolean reset = breakMillis >= TimerSessionService.STRETCH_RESET_BREAK_SECS * 1000;
        return Embeds.of("▶️", "Session Resumed", "✦ Time spent on break: `" + Durations.format(breakMillis) + "`\n"
                + (reset ? "✦ Rested: you're back to the full XP rate.\n" : "")
                + "✦ Telemetry feed is live again.").build();
    }

    public static MessageEmbed concluded(String recap, User user) {
        var embed = Embeds.of(Emojis.WHITE_HEART_SPIN, "Session Concluded", "**Session Recap**\n\n" + recap);
        return Embeds.footer(embed, "Timer offline", user.getEffectiveAvatarUrl()).build();
    }

    public static MessageEmbed cancelled() {
        return Embeds.of(Emojis.STOP, "Session Cancelled", "The session was cancelled before it started.").build();
    }

    public static MessageEmbed timedOut(String topic) {
        return Embeds.of(Emojis.STOP, "Session Timed Out", "The session for **" + SubjectTopics.code(topic)
                + "** was never started, so it closed after 10 minutes.").build();
    }
}
