# Runtime ownership and scheduling foundation

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Implemented 2026-09-14 for the user's phase-7 request. Phase 6 (real replica-set integration tests) was explicitly deferred and remains a release gate. These classes are inactive: Main does not construct them, no migration or source provisioning was run, and no provider polling, command registration or Discord delivery was activated.

## Implemented responsibilities

| Class | Responsibility |
| --- | --- |
| OwnershipRepository / MongoOwnershipRepository | Acquire, renew and release a runtime or source/scope lease using database time, token and generation fences |
| MongoRuntimeSchema | Explicit additive runtime indexes and singleton provisioning; separate bounded provisioning of approved source scopes |
| RuntimeOwnership | Local ownership lifecycle, conservative monotonic deadlines, immediate admission stop, close without blocking on database I/O |
| SchedulingRepository / MongoSchedulingRepository | Bounded guild, due-job and expired-job pages with database-time predicates and stable keyset cursors |
| FairCandidateWindow | Disposable candidate hints, one per channel, bounded total size and round-robin guild/channel selection |
| DeliveryScheduler | Non-overlapping bounded admission/recovery ticks, pacing, fair refill and capacity-before-claim |
| ScheduledDelivery | Transfer capacity to the asynchronous operation, require known authorization before submission, retain unresolved attempts |

The existing MongoDeliveryRepository now requires a runtime lease in AuthorizationRequest. It touches the current runtime owner before evaluating the job and fences that owner again after writing SENDING. Attempt validity is capped by the current database runtime lease as well as existing permission/offer validity. Source leases translate directly into the SourceLease already required by observation commits; no alternate catalog writer was added.

## Ownership and external-send boundary

AUD-04 follow-up (2026-09-19): ScheduledDelivery rechecks its permit before transport submission. A recorded receipt, stopped/released permit, shutdown or global persistence-failure pause prevents a new submission. Receipt callbacks serialize with that check. Capacity still requires transport-stop and known receipt proofs; a check cannot retract submitted requests.

Runtime ownership is a single `egress` record in AlertRuntimeOwnership. A source scope uses the existing AlertSourceState record with canonical sourceId/scope identity. Records are provisioned explicitly; acquire never upserts a missing scope or resets generation. Acquisition requires expiry and increments generation. Renewal/release require the current token, generation and an unexpired lease. Unknown write outcomes propagate.

RuntimeOwnership has NEW → ACQUIRING → ACTIVE → LOST/CLOSED states. A failed or uncertain refresh, conservative deadline expiry or backwards local elapsed time stops admission permanently for that controller. It never reacquires automatically. A fresh controller/process must use a fresh token and must not overlap the old deployment. Renewal replies arriving after close or after the old local deadline cannot reactivate it.

The local budget starts **before** the database call, subtracts an explicitly configured safety margin and includes response latency. It uses monotonic elapsed time rather than comparing the application's wall clock to Mongo's wall clock. This is conservative, not a substitute for healthy database clocks or phase-6 failover testing.

Decision RUNTIME-01: require a runtime ownership fence on every durable send authorization in addition to each delivery lease. This implements the existing single-egress invariant without changing event/channel uniqueness or job BSON identities. The AuthorizationRequest Java constructor changes; old writers must remain stopped and cannot be mixed with this version. No database lease can retract a request already accepted by Discord. Operational stop-before-start deployments remain mandatory.

## Bounded and fair scheduling

The scheduler is explicitly tick-driven and creates no threads, timers or waiting queues. Invoke admission/recovery ticks from a dedicated background I/O lane; concurrent calls return without adding work. Drive ownership acquisition/renewal from a separately bounded control lane so dispatch work cannot delay renewal. The final application composition and executor lifecycle are still to be wired with the future transport stage, not through the handler's interaction executor.

Budgets are constructor inputs, not adopted launch defaults: candidate capacity (technical maximum 5,000), page size, guilds visited (maximum 16 per tick), claim attempts, recovery attempts, claim duration and positive claim spacing. Both successful and unsuccessful claim attempts consume the tick budget. There is no pacing catch-up burst after a stall. Spacing limits admission attempts, not actual Discord throughput, and never replaces JDA rate limits.

Guild-specific reads rotate through a bounded keyset page. Only visited guilds advance the cursor, so a full window cannot repeatedly skip the rest of the page. Global due scans advance independent READY/RETRY_WAIT cursors, including past already-buffered channels. Global versus guild refill and READY versus RETRY_WAIT priority alternate. Recovery alternates SENDING/LEASED priority and has a separate inspection budget. These are best-effort fairness controls, not strict FIFO or a proven delivery-latency guarantee.

Candidate state is disposable. An occupied channel's hint is discarded and rediscovered by durable paging rather than blocking a small window. Disabled or removed guild guards are still enumerated so old jobs can be resolved; global pages also find orphaned jobs. No job is leased until SendAdmission reserves capacity. Claim results must match the selected immutable identity and supplied lease token/generation. Lost ownership after a claim prevents handoff; the unattempted lease can later expire and recover.

## Worker handoff and shutdown contract

1. The worker receives ScheduledDelivery with a claimed LEASED job and reserved capacity. Returning from the handler does not release that capacity.
2. Call beginAuthorization before the database transaction; include its current runtime lease in AuthorizationRequest. Unknown authorization commits retain the protected slot.
3. A known SENDING result must be supplied to confirmAuthorized. It validates job/owner identity and derives a conservative local deadline starting before authorization I/O, bounded by both the attempt window and delivery lease.
4. Immediately before submission, tryBeginTransport checks ownership and elapsed validity and permits submission only once. The future transport adapter must still enforce its actual queue/request deadline and the exact frozen payload. A successful local check cannot cancel a later ownership loss.
5. Release only on proven uncommitted authorization, or both confirmed transport termination and known outcome persistence. A Future timeout is not proof of either. Receipt persistence failure pauses admission. If authorization committed but submission was refused, record the actual non-submission outcome; never call authorizationRejected for a committed attempt.

close stops admission promptly and clears disposable hints. It never waits for database work or frees protected attempts. releaseAfterDrain is a separate explicit I/O operation and refuses while capacity remains occupied or a refresh is in progress. A shutdown deadline alone cannot authorize release. Final Main hook integration, bounded transport drain, receipt buffering and operational visibility remain transport/composition work.

## Migration, compatibility and verification

MongoRuntimeSchema defines `alert-runtime-v1` after the base/catalog migrations. Prior manifests and checksums remain unchanged. Installation adds ownership-expiry indexes and inserts the singleton only if absent. Approved source scopes are provisioned separately with insert-only defaults; existing lease tokens, generations and source progress are preserved. An existing source record with a different canonical ID requires a reviewed import/reconciliation instead of overwriting it. No TTL or deletion of ownership history is introduced.

Run installers/provisioning only with alert writers stopped, then verify all required schemas before constructing the active runtime. Rollback retains ownership records and monotonic generations. An older binary lacking the mandatory runtime authorization fence must not be restarted alongside a current owner.

Tests cover conservative expiry, slow and late renewal replies, close during a blocked acquire, protected-work retention, explicit drain/release, round-robin selection, capacity-before-claim, pacing, overlapping ticks, failed-claim budgets, ownership loss during claim, authorization/submission deadlines, recovery delegation, database query bounds, stale token/generation predicates, mandatory runtime authorization fences and insert-only provisioning. These use mocked database/Discord boundaries and controlled clocks, not real replica-set races, failover, crash durability or production throughput.

Final verification: JDK 21 offline Maven clean verify passed all 217 tests (28 added), with zero failures/errors/skips. Packaged smoke passed discovery, JDA payloads, BSON codecs, logging, manifest and secret/test exclusions. Logs: `.tools/runtime-verify.log` and `.tools/runtime-smoke.log`; the shared checklist records the same handoff. Phase 6 remains pending. Source polling cursors/adapters, active executor composition, payload rendering, real Discord transport, stage watermarks, metrics and release testing are not completed by this phase.


