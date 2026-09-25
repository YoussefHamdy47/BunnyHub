package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;
import java.time.*;
import java.util.*;

/** Database-time ownership. An unknown write outcome is an error, never permission to work. */
public interface OwnershipRepository {
    sealed interface Resource permits Runtime, Source {}
    record Runtime() implements Resource {}
    record Source(AlertIdentity.SourceId sourceId, String scope) implements Resource {
        public Source { Objects.requireNonNull(sourceId); scope = AlertIdentity.token(scope); }
    }
    record Lease(Resource resource, String token, long generation, Instant checkedAt, Instant until) {
        public Lease {
            Objects.requireNonNull(resource); token = AlertIdentity.token(token);
            Objects.requireNonNull(checkedAt); Objects.requireNonNull(until);
            if (generation < 1 || !checkedAt.isBefore(until)) throw new IllegalArgumentException("Invalid ownership lease.");
        }
        public ObservationRepository.SourceLease sourceLease() {
            if (!(resource instanceof Source source)) throw new IllegalStateException("Not source ownership.");
            return new ObservationRepository.SourceLease(source.sourceId(), source.scope(), token, generation);
        }
    }
    Optional<Lease> acquire(Resource resource, String uniqueToken, Duration duration);
    Optional<Lease> renew(Lease lease, Duration duration);
    /** Only after local work has stopped/drained; never release just because a shutdown deadline elapsed. */
    boolean release(Lease lease);
    static long durationMillis(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isNegative() || duration.toMillis() < 1 || duration.compareTo(Duration.ofMinutes(15)) > 0
                || duration.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("Ownership duration must be 1 ms to 15 minutes in whole milliseconds.");
        return duration.toMillis();
    }
}
