# Catalog commits and durable send authorization

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Implemented 2026-09-13 under the user's request to continue the base system. This connects the [engine](ALERT_ENGINE.md) and [storage/queue foundation](ALERT_STORAGE.md) at their two missing transaction boundaries. Main remains unchanged: no alert migration, worker, provider polling or live notification was activated.

## Implemented

- `CatalogDocuments` maps canonical offers and immutable free-game event snapshots to schema-v1 BSON. It validates event/payload versions, canonical identity, dates and exact minor-unit prices. These are eligibility snapshots; display content and a Discord renderer remain future work.
- `MongoObservationRepository` atomically stores the canonical offer, source-occurrence ordering record, first eligible event and counted automatic audience plan. It checks current source/scope lease ownership and a fresh final database-time fence. Existing events are reused, including a manually originated event; the event's original observation time remains the automatic audience cutoff.
- `ObservationRepository.Commit` now requires expectedContentRevision and sourceOrder. Concurrent/stale catalog updates fail closed. A new edition cannot silently replace the identity of an existing campaign. The sourceOrder must be a certified monotonic value for that source occurrence, not an arbitrary fetch timestamp; upstream ordering correctness remains a source-adapter obligation.
- `MongoDeliveryRepository` implements database-backed send authorization. It loads the job's event, current offer and current configuration, runs the pure authorization rule, touches shared guard records, inserts the attempt and commits SENDING atomically. It delegates claims, recovery and receipts to the existing queue.
- `PermissionCheck` binds trusted transport preflight evidence to a destination incarnation, exact roles and an exclusive validity deadline. Expired or mismatched evidence blocks sending. The authorized attempt deadline is the earlier of permission validity and offer/freshness validity, and the final database write checks that deadline again. Actual Discord permission checks remain a transport-adapter responsibility.
- `MongoCatalogSchema` provides a second explicit additive migration for AlertOffers, AlertEvents, AlertSourceState and AlertSourceMappings, plus outbox capacity. It leaves the original base-v1 migration checksum intact. Neither installer has been run against the live database.

## Transaction and replay guarantees

The source lease is touched before reading the catalog and fenced again after all writes. Offer content revision uses compare-and-set. Source occurrence records reject repeated or older order values. Event uniqueness remains canonical offer/type, and the automatic plan remains unique per event. Replaying an already committed source version returns STALE_OBSERVATION; it cannot create another announcement. An unknown commit error propagates so the caller can reread durable state before deciding whether to retry.

An ineligible or unknown observation can update the catalog but cannot create an event or plan. If a new eligible plan cannot reserve outbox capacity, the transaction leaves the offer, event, mapping and polling progress unadvanced. Existing events/plans are not replaced by metadata refreshes. No provider or Discord call occurs inside a transaction callback.

Authorization touches guild, destination, subscription and current-offer documents using their expected revisions. This forces conflicts with concurrent writers to those records; snapshot reads alone would allow a disable to race unnoticed. A configuration change committed before authorization is observed or causes a retry/conflict. A change committed afterward cannot revoke an already-authorized external send. The authorization result is returned only after a known transaction commit. A missing attempt during later receipt recording remains an integrity error, not permission to invent one.

The original generic `saveTransition(expected,next)` port was removed before any production use. Its replacement exposes specific authorize, defer, recovery and outcome operations. Callers cannot submit an arbitrary restored SENDING/SENT snapshot to bypass the authorization transaction. Blocked preflight may defer a current LEASED job without consuming an attempt. Uncertain reconciliation and audited redrive are still separate future capabilities.

## Outbox admission and migration

New automatic plans reserve one slot in the AlertBacklog `outbox` record, with pendingPlans/maxPlans/paused/revision. A counted plan preserves `outboxCounted` through every claim/page; its final empty-page transaction releases exactly one slot. Old base-v1 plans decode with `outboxCounted=false` and never decrement a counter they did not reserve. The migration refuses to initialize a missing counter over existing counted plans. Existing uncounted plans remain grandfathered and must be included in operational capacity planning before activation.

This is an additive codec expansion and second explicit migration, not a rewritten base migration. Rollback must retain all catalog, source-mapping, event, attempt and plan data. Older worker binaries do not understand counted-plan accounting: keep alert workers stopped during a rollback, and reconcile counters before resuming any older version. Do not mix old and new alert writers. Real replica-set migration/restart testing remains a release requirement.

Both backlog counters are conservative hard admission limits with explicit pauses. Automatic hysteresis, disk-pressure policy, source-state acquisition/renewal, mapping retention/tombstones and global runtime ownership are not implemented in this increment. No throughput or launch-capacity claim follows from the unit tests.

## Validation and next work

Tests exercise BSON/event round trips, source ordering and revision rejection, event reuse, metadata refresh, outbox saturation, failed/lost transaction fences, role/configuration changes, expired/foreign permission evidence, atomic attempt ordering, counted-plan completion and compatibility between authorization and the queue receipt writer. They run with driver-boundary doubles. They do not prove actual MongoDB transaction isolation, rollback, failover or durable recovery.

Verified locally: **157 tests passed**, including **19 added in this increment**. JDK 21/local Maven `clean verify` and the packaged-artifact smoke check passed.

Logs: `.tools/alert-pipeline-tests.log`, `.tools/alert-pipeline-verify.log`, `.tools/alert-pipeline-smoke.log`. Artifact: `target-upgrade/BunnyHub-4.00.jar`. No live bot restart or database credential use was needed.

Configuration services, runtime/source ownership and bounded scheduling foundations were subsequently implemented. Phase-8 commands are next; full shutdown/transport composition remains later. Before activation, add an isolated replica-set integration suite and explain plans, then certified source adapters, exact payload rendering and controlled Discord transport tests. Permission evidence and source evidence in the current tests are fixtures, not verified live capabilities.


