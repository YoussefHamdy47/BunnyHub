# Alert storage and fan-out base

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Implemented 2026-09-13 in response to the request to keep building the base system. This follows the [initial engine foundation](ALERT_ENGINE.md) and the [system blueprint](SYSTEM_ARCHITECTURE.md). The classes are compiled and tested locally but are not wired into Main, connected to the live database, or running as workers. No migration was applied.

Later increment: [ALERT_PIPELINE.md](ALERT_PIPELINE.md) implements the catalog/outbox producer and database-backed authorization that were still missing when this storage increment was written. Its scope and migration notes supersede the corresponding remaining-work items below.

## What this increment implements

| Component | Responsibility |
| --- | --- |
| `FanoutPlan` | Separate automatic audience-plan identity, persistent cursor, fenced leases, page counters and completion only after an empty page |
| `FanoutEngine` | Pure, bounded page planning with current subscription matching and deterministic event/channel delivery IDs |
| `FanoutRepository` / `MongoFanoutRepository` | Claim a plan with database time; read a bounded subscription page and batched current guild/destination guards; insert only missing jobs and advance the cursor atomically |
| `DeliveryQueue` / `MongoDeliveryQueue` | Bounded guild/state due queries, atomic due-job claims, expired-lease recovery and transactional attempt/outcome recording |
| `AlertDocuments` | Explicit schema-v1 BSON conversion for jobs, attempts, automatic plans and subscriptions; strict field validation, bounded role lists and millisecond timestamps |
| `MongoAlertDatabase` | Injected client, same-client sessions, finite operation and whole-transaction timeouts, primary reads, snapshot transactions and majority writes |
| `MongoAlertSchema` | Explicit additive index installation, checksum marker, bounded index verification and initial backlog guard creation; never automatic startup repair |

The tested durable path starts with an **already committed announcement and automatic plan**. This increment does not implement the offer/event producer, source polling, or atomic event-plus-plan creation. It also does not implement `DeliveryRepository.authorize`: creating a SENDING job and its AlertAttempts row remains a required transaction in the next storage increment. Queue outcome recording deliberately rejects a missing authorized attempt instead of creating one after the fact.

## Storage semantics

All collections are isolated from timer/account data: AlertFanout, AlertDeliveries, AlertAttempts, AlertSubscriptions, AlertGuilds, AlertDestinations, AlertBacklog and AlertSchema. Every encoded record has schemaVersion; mutable business records have revision. `checkedAt` is a server-generated operational clock sample written under the existing lease, not a new business revision.

AlertFanout's partial unique index allows one automatic free-game plan per event while reserving space for future manual-plan kinds. AlertDeliveries has unique eventId/channelId identity, independent of plan and configuration revision. AlertSubscriptions has destinationId/storeId/topic uniqueness. Existing jobs of any state count as duplicates; page replay never resets them.

Fan-out reads at most the requested 1–500 subscription rows, projects only required fields, validates each row before retaining the next, and loads guild/destination guards in bounded batches. Existing delivery identities are also queried in one bounded batch. Simple collation and UTF-8 byte ordering agree for cursors, including non-BMP IDs. Invalid unpaired surrogates are rejected by the shared identifier validator to avoid lossy UTF-8 identity conversion.

Each page transaction touches the current plan, computes its jobs, reserves capacity for missing jobs, inserts those jobs, then replaces the plan using a fresh database-time lease fence. A lost final fence throws through the transaction callback, aborting jobs and capacity changes. Cursor advancement cannot be returned successfully before the driver reports a known commit. Exceptions such as duplicate-key transaction aborts and unresolved commit outcomes propagate; they are not silently converted into success or an ordinary ownership conflict. A caller must resolve an unknown commit by rereading durable state before retrying.

Guild/destination enabled flags are joined from their current records, not trusted copies inside a subscription. A missing, foreign or replaced destination disables the projection. This is eligibility during page processing, as in the blueprint; the final send still needs its own current guard-touch authorization transaction. A concurrent later disable can invalidate an already-created job.

Queue claims select only due READY/RETRY_WAIT jobs. Due enumeration takes one guild and one state per bounded query; fair rotation and pacing remain scheduler responsibilities. Direct expired-job recovery requires the expected revision. Expired LEASED returns to READY; expired SENDING becomes UNCERTAIN and updates the corresponding attempt row in the same transaction. SENT/FAILED outcome persistence also decrements the pending-job counter atomically. UNCERTAIN remains outstanding and never automatically frees durable backlog capacity. This counter is separate from local SendAdmission permits: recording uncertainty does not prove an external request has stopped.

The driver supports whole-transaction timeout budgets through TransactionOptions, and withTransaction handles documented transaction retries. Every callback operation here uses the same ClientSession and contains no Discord/HTTP work. [MongoDB transaction documentation](https://www.mongodb.com/docs/drivers/java/sync/current/crud/transactions/), [timeout documentation](https://www.mongodb.com/docs/drivers/java/sync/current/connection/specify-connection-options/csot/).

## ADR 008: deterministic page identities and transactional backlog admission

**Problem and reasoning:** a count-then-insert capacity check permits two page transactions to consume the same remaining slots. Random IDs generated during a retrying callback also make retries harder to compare. Both problems can be demonstrated without assuming delivery throughput.

**Decision:** derive each job ID from a versioned SHA-256 of length-prefixed eventId/channelId components, while retaining the independent unique eventId/channelId index. Hash collisions fail closed through unique IDs rather than being treated as duplicates of another logical key. Derivation can happen inside the callback because its inputs are immutable; unlike random allocation, repeated derivation produces the same identity. No title, source, role, plan or attempt metadata is hashed into delivery identity.

Add one AlertBacklog record with pendingJobs, maxJobs, paused and revision. A page atomically increments pendingJobs only if its new jobs fit maxJobs and admission is not paused. Terminal outcomes decrement in their receipt transaction. For example, if two pages each need four slots and only five remain, both update the same guard: at most one can commit its reservation, and the other must retry its snapshot and refuse admission. The configured ceiling is explicit, not the proposed launch capacity.

**Alternatives:** count-then-insert does not serialize reservations; loading a whole audience before a transaction defeats bounded paging; random IDs allocated outside each retry need an additional stable per-page allocation mechanism. The selected approach keeps memory bounded and keys replayable.

**Tradeoffs:** the backlog guard serializes page admission and terminal-count updates. This preserves correctness but may create contention; no throughput improvement is claimed without a real database benchmark. Automatic high/low-watermark hysteresis, disk pressure, plan-count pressure and receipt-reserve policy are not yet implemented. The static hard ceiling and explicit pause flag are the implemented subset.

**Compatibility/migration:** no existing alert schema was deployed. The explicit additive v1 installer creates required indexes and a checksum marker. It refuses to initialize a zero count over an existing uncounted delivery collection. Re-running an installed migration verifies it without resetting counts or changing configured capacity. Existing migrations with another checksum or missing/hidden/incompatible indexes fail rather than being silently repaired. Run installation only with alert writers stopped, then call verifyInstalled before future workers start. Main does neither automatically. No TTL or destructive operation is provided.

**Tests/rollback:** unit tests cover deterministic retry identities, duplicate replay, capacity refusal, missing final lease fence, callback exceptions, schema validation and migration refusal over existing uncounted work. Real replica-set concurrent reservations, transaction rollback, failover and index explain plans remain required. Rollback means keep alert workers disabled and retain all alert collections, attempts and identity evidence; never drop data to roll back code.

## Verification and remaining work

New tests exercise codec round trips, schema/index contracts, explicit transaction budgets, fan-out ordering/replay/failures, due-page bounds and queue recovery/outcome recording. A synthetic 1,000-guild case verifies 100-row pages and 1,000 unique job identities. Driver-boundary doubles test the actual repository code's queries, sessions and callback behavior; they do not emulate MongoDB isolation, commit durability or index performance.

Verified locally: **138 tests passed**, including **30 added in this storage increment** (63 alert tests total), using JDK 21 and the documented local Maven runtime with `clean verify`. Packaged-artifact smoke verification also passed. The Maven wrapper remains unavailable as recorded in the foundation document.

No local mongod or Docker was found on PATH. No live database credentials were used, no replica set was provisioned, no provider was polled and no Discord message was sent. Real crash/restore tests and explain plans are still release gates. Build/test logs: `.tools/alert-storage-tests.log`, `.tools/alert-storage-verify.log`, `.tools/alert-storage-smoke.log`. Artifact: `target-upgrade/BunnyHub-4.00.jar`.

Historical next steps at this increment (items 1–2 and the backend portion of item 3 are now implemented; consult PROJECT_STATUS.md):

1. Atomic canonical offer/event/automatic-plan commit, source ownership/order checks and outbox pressure accounting.
2. Database-backed send authorization that touches current guild/destination/subscription guards and commits the frozen attempt before transport.
3. Revision/audit configuration services, runtime ownership, bounded fair scheduling, recovery enumeration and shutdown composition.
4. A real isolated Mongo replica-set integration suite before certifying storage durability; certified sources and sandbox Discord transport follow.


