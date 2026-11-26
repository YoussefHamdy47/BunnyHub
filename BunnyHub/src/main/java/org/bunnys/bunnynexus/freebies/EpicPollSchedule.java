package org.bunnys.bunnynexus.freebies;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Wall-clock release checks, independent of process startup and Cairo's seasonal UTC offset. */
final class EpicPollSchedule {
    private static final ZoneId ZONE = ZoneId.of("Africa/Cairo");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private EpicPollSchedule() {}

    static Duration nextDelay(Instant now, Duration normal) {
        var local = now.atZone(ZONE);
        Instant release = local.toLocalDate().atTime(18, 0).atZone(ZONE).toInstant();
        Instant next;
        if (now.isBefore(release)) next = release;
        else if (now.isBefore(release.plus(WINDOW))) {
            long elapsed = Duration.between(release, now).toSeconds();
            next = release.plusSeconds((elapsed / 30 + 1) * 30);
        } else next = local.toLocalDate().plusDays(1).atTime(18, 0).atZone(ZONE).toInstant();
        Duration boundary = Duration.between(now, next);
        return boundary.compareTo(normal) < 0 ? boundary : normal;
    }
}
