package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.*;

/** Bounded disposable candidate reads. A candidate is neither a claim nor send authorization. */
public interface SchedulingRepository {
    record Cursor(Instant dueAt, String jobId) {
        public Cursor { Objects.requireNonNull(dueAt); jobId = AlertIdentity.token(jobId); }
        public static Cursor after(DeliveryJob job) { return new Cursor(job.dueAt(), job.id()); }
    }
    List<String> guilds(Optional<String> afterGuildId, int limit);
    List<DeliveryJob> due(DeliveryJob.State state, Optional<String> guildId, Optional<Cursor> after, int limit);
    List<DeliveryJob> expired(DeliveryJob.State state, int limit);
}
