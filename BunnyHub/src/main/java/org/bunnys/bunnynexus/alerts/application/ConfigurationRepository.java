package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.*;

/** Atomic configuration, guard projections, identity tombstones and audit/idempotency receipt. */
public interface ConfigurationRepository {
    record Receipt(String actionId, String actorId, String guildId, String fingerprint, long revision, Instant committedAt) {
        public Receipt {
            actionId = AlertIdentity.snowflake(actionId); actorId = AlertIdentity.snowflake(actorId); guildId = AlertIdentity.snowflake(guildId);
            if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}") || revision < 1) throw new IllegalArgumentException("Invalid receipt.");
            Objects.requireNonNull(committedAt);
        }
        public boolean matches(ConfigurationChange change) {
            return actionId.equals(change.actionId()) && actorId.equals(change.actorId()) && guildId.equals(change.guildId()) && fingerprint.equals(change.fingerprint());
        }
    }
    enum Status { APPLIED, REPLAYED, REVISION_CONFLICT, IDEMPOTENCY_CONFLICT }
    record Result(Status status, long revision) {
        public Result { Objects.requireNonNull(status); if (revision < 0) throw new IllegalArgumentException("Invalid revision."); }
        public static Result replay(Receipt receipt, ConfigurationChange change) {
            return new Result(receipt.matches(change) ? Status.REPLAYED : Status.IDEMPOTENCY_CONFLICT, receipt.revision());
        }
    }
    GuildConfiguration load(String guildId);
    Optional<Receipt> receipt(String guildId, String actionId);
    /**
     * Re-evaluate the pure policy under the expected guild revision, and verify evidence against database time
     * before writes and at the final commit fence. Serialize capacity and all configuration writers through
     * the same guild guard touched by send authorization. No permission calls or external side effects here.
     * Unknown commits propagate; retry the identical action and consult its durable receipt, never invent success.
     */
    Result commit(ConfigurationChange change, ConfigurationAccess.Proof proof, ConfigurationPolicy policy);
}
