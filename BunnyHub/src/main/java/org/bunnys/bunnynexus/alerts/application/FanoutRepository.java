package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Duration;
import java.util.Optional;

public interface FanoutRepository {
    Optional<FanoutPlan> claim(String planId, String token, Duration leaseDuration);
    enum Result { PAGE_COMMITTED, COMPLETE, CONFLICT, BACKPRESSURE }
    /** Atomically insert missing jobs, reserve backlog capacity and advance only the current leased plan. */
    Result processPage(String planId, long expectedRevision, DeliveryJob.Lease owner, int pageSize);
}
