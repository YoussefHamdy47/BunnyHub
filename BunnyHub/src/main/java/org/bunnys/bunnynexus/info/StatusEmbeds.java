package org.bunnys.bunnynexus.info;

import org.bunnys.handler.cards.Card;
import org.bunnys.utils.Embeds;


/** {@code /ping} and {@code /uptime}: a small card with the readings and the bot's name as the footer. */
public final class StatusEmbeds {
    private StatusEmbeds() {}

    /** Latencies in milliseconds; a negative value (not measured yet, or the request failed) shows as n/a. */
    public static Card ping(long gatewayMillis, long apiMillis) {
        return simple("**Gateway:** " + latency(gatewayMillis) + "\n**API:** " + latency(apiMillis));
    }

    /** Discord renders the UNIX timestamps in each reader's own time zone and keeps the relative one ticking. */
    public static Card uptime(long startedAtMillis) {
        long seconds = startedAtMillis / 1000;
        return simple("**Online since:** <t:" + seconds + ":F>\n**Uptime:** started <t:" + seconds + ":R>");
    }

    static String latency(long millis) {
        return millis < 0 ? "`n/a`" : "`" + millis + "ms`";
    }

    private static Card simple(String description) {
        return Card.builder().text(description).footer(Embeds.FOOTER).build();
    }
}
