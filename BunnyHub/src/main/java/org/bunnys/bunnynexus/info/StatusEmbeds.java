package org.bunnys.bunnynexus.info;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;

import java.time.Instant;

/** {@code /ping} and {@code /uptime}: a description and a timestamped footer, nothing else. */
public final class StatusEmbeds {
    private StatusEmbeds() {}

    /** Latencies in milliseconds; a negative value (not measured yet, or the request failed) shows as n/a. */
    public static MessageEmbed ping(long gatewayMillis, long apiMillis) {
        return simple("**Gateway:** " + latency(gatewayMillis) + "\n**API:** " + latency(apiMillis));
    }

    /** Discord renders the UNIX timestamps in each reader's own time zone and keeps the relative one ticking. */
    public static MessageEmbed uptime(long startedAtMillis) {
        long seconds = startedAtMillis / 1000;
        return simple("**Online since:** <t:" + seconds + ":F>\n**Uptime:** started <t:" + seconds + ":R>");
    }

    static String latency(long millis) {
        return millis < 0 ? "`n/a`" : "`" + millis + "ms`";
    }

    private static MessageEmbed simple(String description) {
        return new EmbedBuilder().setColor(AppDesign.ColorCodes.DEFAULT).setDescription(description)
                .setFooter(Embeds.FOOTER).setTimestamp(Instant.now()).build();
    }
}
