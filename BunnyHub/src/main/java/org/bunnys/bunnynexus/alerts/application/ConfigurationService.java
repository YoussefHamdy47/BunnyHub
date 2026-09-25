package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import static org.bunnys.bunnynexus.alerts.application.ConfigurationRepository.*;

/** Administrator use cases; all access checks precede reads and all Discord checks precede transactions. */
public final class ConfigurationService {
    private final ConfigurationRepository repository;
    private final ConfigurationAccess access;
    private final ConfigurationPolicy policy;
    private final Clock clock;
    public ConfigurationService(ConfigurationRepository repository, ConfigurationAccess access, ConfigurationPolicy policy, Clock clock) {
        this.repository = Objects.requireNonNull(repository); this.access = Objects.requireNonNull(access);
        this.policy = Objects.requireNonNull(policy); this.clock = Objects.requireNonNull(clock);
    }
    public GuildConfiguration view(String actorId, String guildId) {
        AlertIdentity.snowflake(actorId); AlertIdentity.snowflake(guildId);
        access.requireAdministrator(actorId, guildId);
        return repository.load(guildId);
    }
    public Result change(ConfigurationChange change) {
        Objects.requireNonNull(change);
        access.requireAdministrator(change.actorId(), change.guildId());
        var receipt = repository.receipt(change.guildId(), change.actionId());
        if (receipt.isPresent()) return Result.replay(receipt.get(), change);
        var before = repository.load(change.guildId());
        if (before.revision() != change.expectedRevision()) {
            // A concurrent copy of this same interaction may have committed between the two reads.
            return repository.receipt(change.guildId(), change.actionId()).map(r -> Result.replay(r, change))
                    .orElseGet(() -> new Result(Status.REVISION_CONFLICT, before.revision()));
        }
        var next = policy.apply(before, change, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        var requirements = ConfigurationAccess.requirements(change, next);
        var proof = Objects.requireNonNull(access.verify(requirements));
        proof.requireCurrent(requirements, clock.instant());
        return repository.commit(change, proof, policy);
    }
}
