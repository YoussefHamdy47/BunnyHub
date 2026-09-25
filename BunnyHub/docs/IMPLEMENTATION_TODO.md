# BunnyNexus implementation checklist and agent handoff

Latest increment — 2026-09-25 (NEXT-10 / OWNER-01): owner-only prepare → verify → explicit bounded release is now persisted, audited and enforced transactionally at send authorization. Verification alone cannot publish; releases expire within one hour, count retries, bind reviewed content/template and can be revoked. **334 default tests, 16 real isolated three-node MongoDB tests and packaged smoke passed** (.tools/next10-verify.log, .tools/next10-mongo-final.log, .tools/next10-smoke.log). New additive owner-review migration tested only in disposable databases; old send writers must stop before upgrade. Private owner commands, certified ingestion, pre-fan-out gate and transport/composition remain pending. No live migration, bot restart or Discord send. See [owner-controlled publication](ALERT_OWNER_REVIEW.md).

Latest increment — 2026-09-25 (NEXT-09): the user-delegated launch decisions are now in [ALERT_OPERATIONS.md](ALERT_OPERATIONS.md). Added inactive PublicationReviewPolicy/GamerPowerPollingLoop and fixed AUD-07 payload identity binding. Two live application checks matched Epic product pages before/after rollover; latest Mechabellum/Astrea. **324 tests passed**, packaged smoke and repaired audit probe passed. Durable candidate/content/review storage, mandatory transactional review enforcement, certified enrichment and live transport remain next dependencies. No schema/Main/activation change.

Latest review — 2026-09-23 (AUD-07): freebie source review confirmed a missing offer/subscription identity binding in PreparedPayload authorization and contradictory retry nonce instructions; also found a stale architecture implementation inventory. Findings remain open; see [review and reproductions](FREEBIE_AUDIT_2026-09-23.md). JDK 21 clean verify: **310 tests passed**; packaged smoke passed. Standalone offline reproduction confirmed both functional findings. No production source/schema or activation changes.

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Latest audit — 2026-09-22 (AUD-06): reviewed alert input/access, transport, polling, ownership and persistence boundaries, then fixed timer clock-rollback bugs: negative break durations, repeated subject-time credit/inconsistent aggregate totals, and incorrect paused telemetry. Four regressions added; three accounting regressions reproduced failures before the fix. JDK 21 clean verify **293 tests passed**, packaged smoke passed (.tools/audit6-verify.log and .tools/audit6-smoke.log). See docs/AUDIT_REPORT.md (AUD-06) for scope and limits. No schema/checksum changes, live requests, restart or activation. Real database verification, source certification and transport/load validation remain release gates.

Latest handoff — 2026-09-21: AUD-03 phase-8 audit completed with fixes to input/storage error classification, required boolean parsing and selective status rendering. Phase 9 has started with inactive, bounded GamerPower candidate intake; [discovery notes](ALERT_DISCOVERY.md). Full JDK 21 clean verify: **255 tests passed**, packaged smoke passed. Logs: .tools/phase8-audit-tests.log, .tools/phase9-intake-verify.log and .tools/phase9-intake-smoke.log. No migration, bot restart or alert activation. Phase 6 remains deferred and mandatory before activation.


Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Updated 2026-09-14. This is the shared execution checklist, not an authorization to deploy or broadcast. Read AGENTS.md, SYSTEM_ARCHITECTURE.md, ALERT_SOURCES_AND_FEATURES.md and BUNNYHUB_2.md before changing the relevant subsystem. The architecture governs invariants; this file tracks work. Update this file in the same change that changes completion status.

Status convention: `[x]` means the described code/task exists and has local evidence; it does not mean production-certified. `[ ]` is outstanding. Items explicitly labelled integration, certification or release remain incomplete until their own evidence is recorded. No alert runtime, migration or provider has been activated. Existing timer/info/avatar commands remain the live application.

## How the completed app should work

1. Start with explicit configuration, verify installed schemas and transaction-capable Mongo, obtain runtime ownership, connect the bot, recover durable work, and progressively enable background stages. Do not run migrations automatically as repairs.
2. A guild administrator configures channels, chosen stores, market and exact role IDs. Commands remain thin; application services enforce ownership, permissions, revision conflicts, limits and audit.
3. Poll certified discovery sources centrally by source/scope, independent of guild count. A source is not a store. Normalize observations, resolve canonical store/campaign/market identity and verify eligibility evidence.
4. Retain candidates for owner review. Require owner prepare → verify → explicit bounded release before public fan-out; the pre-event gate remains to be implemented. The existing catalog/event transaction alone is not approval. Metadata changes do not mint another initial event. Unknown evidence remains unannounced. Bound ingestion by outbox/storage pressure.
5. Fan out through bounded subscription pages with activation cutoffs and current destination guards. Commit missing event/channel jobs and cursor advance together. Replay preserves existing jobs and receipts.
6. A fair, rate-aware dispatcher reserves asynchronous capacity before claiming due work. Load current content, render an exact payload and validate destination permissions outside retryable database work.
7. Authorize the send in Mongo: recheck current configuration/offer and the exact current owner release, touch their guards, reserve one release attempt and persist the frozen attempt plus SENDING in one transaction. Only a known commit permits transport. A later unsubscribe or owner revocation may not retract an already-authorized send.
8. Submit through the single Discord egress owner with exact allowed mentions and bounded deadlines. Hold capacity until transport termination and known outcome persistence; a Future timeout is not proof the request stopped.
9. Persist acceptance with message ID, proven rejection with bounded retry policy, or UNCERTAIN when acceptance cannot be determined. Never blindly retry uncertain sends. Recover expired LEASED as ready and expired SENDING as uncertain.
10. Expose operator health, backlog age, failed/skipped/uncertain outcomes and scoped repair actions. Shut down by stopping admission, boundedly draining and preserving unresolved durable work before closing JDA/Mongo.
11. Later manual publishing uses owner-bound preview/confirm/status and fixed approved audiences through the same event/channel identity. Later paid deals use separate opt-in subscriptions and bounded digest capacity.

## Agent coordination protocol

- Claim a bounded task by adding a row to the work ledger below with your agent/task name, exact files, start time, dependencies and intended test evidence. Never infer that a broad subsystem assignment owns every shared file.
- Coordinate before editing shared contracts, migrations, Main, AGENTS.md, this checklist or build configuration. Designate one editor for each shared file; other agents send proposed changes to that editor.
- Read existing diffs/files before editing. This workspace may lack Git metadata; do not rely on `git status` as the only change inventory. Never overwrite another agent's edits or rebuild concurrently into the same output directory.
- Prefer independent domain/tests/docs work in parallel. Keep dependent storage/schema/authorization changes sequential or agree the contract first. Do not start independent implementations of the same helper or port.
- Each handoff records files changed, public API/schema changes, test command and log, unresolved limitations and next dependency. Mark work done only after required tests pass; distinguish mocks from real replica-set/Discord evidence.
- Stable IDs, immutable ownership, transactional boundaries, bounded resources and unknown outcomes are non-negotiable. A changed invariant requires an ADR and compatibility/failure tests. Do not silently select proposed product defaults.
- Never use the interactive executor for mass delivery. No feature imports in the handler. No provider network request or Discord send inside a retryable transaction. No secrets in source/artifacts/replies. No live restart just to test code.

| Work ID | Owner | Files/scope | Status / handoff |
| --- | --- | --- | --- |
| NEXT-10 | Current agent, 2026-09-25 | OwnerReviewRepository; MongoOwnerReviewRepository/Schema/Documents; MongoDeliveryRepository; AutomaticSendEngine/AlertMessageRenderer; MongoOwnerReviewTest, MongoAuthorizationTest, AutomaticSendEngineTest, MongoReplicaSetIT; ALERT_OWNER_REVIEW and continuity docs | Complete inactive backend: owner-only verify then bounded release, additive migration, transactional send cap/revocation/content-template gate. 334 default tests, 16 real replica-set tests and packaged smoke passed; .tools/next10-*.log. Owner UI, certified ingestion and pre-event gate remain next. No Discord activation or live migration. |
| NEXT-09 | Current agent, 2026-09-23–25 | AutomaticSendEngine/renderer identity binding and affected tests; PublicationReviewPolicy/Test; GamerPowerPollingLoop/Test; .tools/EpicDiscoveryCheck.java; audit probe; ALERT_OPERATIONS and continuity docs | Initial implementation complete: 14 new regressions, 324 default tests, packaged smoke and repaired audit probe passed. Two real provider checks matched official Epic free listings; market/UTC certification remains open. No Main wiring, review persistence, transactional review gate, production migration or broadcast. |
| AUD-07 | Current agent, 2026-09-23 | Freebie source review; .tools/FreebieAuditProbe.java; FREEBIE_AUDIT_2026-09-23.md and review handoffs | Review complete, fixes open: cross-offer/subscription payload authorization reproduced; retry nonce documentation contradiction reproduced; stale architecture inventory recorded. 310 tests and packaged smoke passed; .tools/freebie-audit-*.log. No activation. |
| NEXT-08 | Current agent, 2026-09-23 | AlertDisplayContent; inactive Discord AlertMessageRenderer; serialized-payload regressions and rendering/handoff docs | Complete inactive preparation: nine regressions, 310 default tests and packaged smoke passed. See ALERT_RENDERING.md; durable content/provider certification and real transport remain open. |
| VERIFY-01 | Current agent, 2026-09-22–23 | LocalReplicaSet, MongoReplicaSetIT, DeliveryLoadIT; intake/fetch 201 handling and regressions; three provider fixtures/metadata; ALERT_VERIFICATION.md and handoffs | 12 real local replica-set tests and two synthetic load scenarios passed; final default build/package verification recorded in current handoff. Phase 6 resumed, remaining scenarios open. No live GBF/Discord activation. |
| AUD-06 | Current agent, 2026-09-22 | Alert ownership/polling/configuration review; TimerSessionService clock accounting, TimerPersistenceTest and audit/status handoff docs | Complete: four regressions added, three reproduced original failures; 293 tests and packaged smoke passed. See AUDIT_REPORT.md AUD-06 and .tools/audit6-*.log. No activation or schema change |
| AUD-01 | Current audit pass | Shared checklist, duplication/security fixes, timer presentation extraction, regressions | Complete: 163 tests and packaged smoke passed; see CODE_AUDIT.md |
| NEXT-01 | Completed configuration pass | Configuration policy/service, JDA access, Mongo aggregate/projections/schema, 5 test suites; see ALERT_CONFIGURATION.md | Complete 2026-09-14: 189 tests and packaged smoke passed; inactive |
| NEXT-02 | Resumed by user, 2026-09-22 | Isolated Mongo replica-set integration fixture and storage failure tests | VERIFY-01 adds 12 passing real local tests; phase 6 remains incomplete. See ALERT_VERIFICATION.md; never use live GBF |
| NEXT-03 | Completed runtime pass | OwnershipRepository, Mongo ownership/schema/fencing, RuntimeOwnership, FairCandidateWindow, DeliveryScheduler, ScheduledDelivery and tests | Complete 2026-09-14: 217 tests and packaged smoke passed; inactive; see ALERT_RUNTIME.md |
| CTX-01 | Completed continuity pass | All maintained Markdown, AutomaticSendEngine deadline, pure/Mongo regression tests | Complete 2026-09-14: 219 tests and packaged smoke passed; see PROJECT_STATUS.md |
| NEXT-04 | Current agent, 2026-09-21 | commands/alerts/Freebie factory; bunnynexus/commands/alerts classes; FreebieCommandsTest; command/status/progress/checklist docs | Implemented, inactive; see ALERT_COMMANDS.md and final verification below. Main unchanged. |
| AUD-02 | Completed source audit, brought forward by user | SendAdmission, Mongo queue/fan-out/observation options, GPA/pending menus, archive revision and regression tests; AUDIT_REPORT.md / PROGRESS_REPORT.md | 224 tests and packaged smoke passed; real integration/security release gates remain open |
| AUD-03 | Completed 2026-09-21 | Phase-8 commands and command/service/storage integration | Three fixes/regressions; 38 focused tests passed; see AUDIT_REPORT.md |
| AUD-04 | Current agent, started 2026-09-19 | ScheduledDelivery/SendAdmission transport gate; FailureDiagnostics and application error call sites; shared SubjectAutocomplete; regression tests and reports | Complete 2026-09-19: 232 tests and packaged smoke passed; no schema or activation change; see reports |

## 0. Product and release decisions

- [x] NEXT-09: choose central five-minute discovery, operator review per material promotion/market, restart checks, uncertain-send quarantine, Epic PC initial scope, explicit certification-gated US/EG targets, English/text channels and five-channel/five-role caps. See ALERT_OPERATIONS.md; selected behavior is not yet activated.
- [x] NEXT-09: implement pure revision/material-bound review rules and an explicit recurring shadow polling lifecycle; verify before/after-rollover source probes against official free product listings.
- [ ] Persist bounded candidate/display/evidence/review records and action receipts; implement mandatory transactional review guards on publication and send, with migration/race/restart tests.
- [ ] Implement named operator review/health/pause/resume commands and composed certified ingestion/real transport before activation.

- [x] Record approximately 1,000 guilds as a planning target, not measured capacity.
- [x] Preserve existing academic/timer/info/avatar features and named command implementations.
- [ ] Confirm allowed offer types, unknown-end policy, supported markets/languages and provider coverage.
- [ ] Confirm destination/role caps, join/catch-up behavior, unknown-outcome policy and channel types.
- [ ] Agree hosting resources, offer bursts, delivery SLO, recovery/backup objectives and measured budget.
- [ ] Confirm deals thresholds/schedule/repeat policy and manual publishing/operator scope before enabling them.

## 1. Domain and contract base

- [x] Structured store/source/market/campaign, event/channel and destination-incarnation identities.
- [x] Immutable offer evidence, exact minor-unit money and date/freshness eligibility decisions.
- [x] Automatic subscription cutoff, topic/market/store/ownership matching and bounded role sets.
- [x] Immutable job/attempt state transitions, lease generations, recovery and bounded retry policy.
- [x] Bounded fan-out plans/pages with deterministic replay identity and counters.
- [x] Pure current-snapshot send authorization and explicit repository ports.
- [ ] Add validated display content, attribution and URL types without treating evidence flags as source certification.
- [ ] Define explicit audited uncertain reconciliation/redrive/closure commands and outcomes.
- [ ] Add visibility for unsupported future event/schema types; fail-closed exceptions alone are not operator tooling.

## 2. Durable storage and catalog

- [x] Schema-v1 BSON mappings for offers/events/subscriptions/plans/jobs/attempts.
- [x] Explicit base and additive catalog schema/index installers with checksum verification.
- [x] Canonical observation compare-and-set, source occurrence ordering and source lease fencing.
- [x] Atomic offer + initial event + counted automatic-plan commits; reuse existing event identity.
- [x] Transactional page insertion/cursor advance and delivery/outbox admission counters.
- [x] Atomic delivery claims, expired-job recovery, attempt/outcome recording and backlog release.
- [x] Database-backed send authorization with guard touches, frozen attempt and final deadline fence.
- [ ] Test real replica-set transactions: simultaneous claimers, configuration writers, retryable aborts, commit uncertainty and failover.
- [x] VERIFY-01: exercise three-node local replica-set claim/poll/configuration races, injected abort/commit errors, delivery authorization/receipt contention, uncertain recovery, duplicate fan-out and primary crash. Remaining matrix in ALERT_VERIFICATION.md.
- [ ] Verify indexes/explain plans against representative mixed-state data; test actual collation and due/expired query behavior.
- [ ] Test migration install/restart/checksum drift and counted-plan upgrade/rollback on an isolated database.
- [ ] Add counted archival/retention and permanent uniqueness evidence or a proven replay horizon. No TTL on active/uncertain work.
- [ ] Implement source mapping/provenance retention, source-rule upgrades, critical disk pressure and counter reconciliation.
- [x] Add transactional configuration revisions/audit/idempotency, aggregate capacity admission, deletion tombstones and incarnation recreation; real transaction testing remains outstanding.

## 3. Configuration and command UX

- [x] Phase-8 guild-only Manage Server/Administrator command checks and verified event identity. No autocomplete/buttons/modals introduced; future entry points require their own checks.
- [x] Add cache-only JDA configuration preflight resolving channels/roles within the actor's guild; reject everyone and unsupported channel types.
- [x] Check cached bot view/send/embed/mention capabilities and timeout state without requiring broad Administrator; live Discord verification remains outstanding.
- [x] Implement backend expected-revision editing, interaction-ID idempotency, audit and typed conflict results; phase-8 command response mapping implemented.
- [x] Implement backend enable/disable, store/topic/market selections, roles, current settings and removal; phase-8 free-game command UI implemented; deals remain later work.
- [x] Reset enabledSince for material eligibility/re-enable changes, preserving role-only cutoff; test immutable delete/recreate identities.
- [ ] Verify disable-versus-send and delete/recreate races with real Mongo transactions.
- [x] Provide explicit thin command registration factory over named implementations and ConfigurationService; preserve handler cooldown/access rules. Live registration remains inactive.

## 4. Discovery and source certification

- [x] Implement explicit source/scope lease provisioning, acquisition, renewal/release and generation checks, compatible with observation fencing.
- [x] Implement explicit shadow polling schedule/attempt summaries with source-lease fences, durable backoff/known pauses and bounded single-tick orchestration (NEXT-07); no publication.
- [ ] Implement certified ingestion cursor, durable candidate retention and poll-to-observation orchestration; source semantics remain unresolved.
- [x] Verify one bounded live GamerPower fetch and save an attributed unmodified success fixture (20 candidates, nine unknown ends).
- [ ] Complete GamerPower certification: genuine empty/error/recurrence fixtures and market/entitlement/timezone/canonical-identity evidence.
- [ ] Validate store/redemption-platform/edition/campaign mappings, regional entitlement, claim conditions and recurrence.
- [ ] Establish ITAD access/terms fit before accounts/credentials or paid-deal integration; direct storefront sources remain optional.
- [x] Add inactive GamerPower fetch-owner admission, spacing/backoff, finite HTTP deadline, byte cap, compression/redirect rejection and validated address pins (NEXT-06); local tests include stalled loopback HTTP.
- [ ] Compose these limits with durable source ownership/polling across restarts and validate real provider transport.
- [x] Restrict the inactive GamerPower adapter to its fixed HTTPS endpoint and explicit public IPv4 pins, with no redirect/proxy/DNS fallback; preserve TLS hostname validation.
- [ ] Certify production address provisioning/refresh and egress policy; other source adapters remain unimplemented.
- [ ] Distinguish incomplete responses from withdrawals; reject stale source ordering and quarantine ambiguous identities/evidence.
- [ ] Coalesce offer refresh centrally; never fetch once per destination. Run discovery in shadow mode before publishing.

## 5. Dispatch, transport and runtime

- [x] Local SendAdmission bounds global/per-channel asynchronous work and pauses admission on receipt persistence failure.
- [x] Implement inactive runtime ownership controller and mandatory durable send-authorization fence; ownership loss stops local admission.
- [ ] Wire and verify the operational single-egress lifecycle with the real transport. No overlapping bot deployments.
- [x] Implement bounded due/expired enumeration, disposable guild/channel fairness, non-overlapping scheduler ticks and paced claim-attempt budgets.
- [ ] Wire bounded control/dispatch executors and validate composed stage concurrency with real workloads.
- [ ] Wire outbox/fan-out/discovery pauses, configurable high/low watermarks and storage pressure without dropping accepted jobs.
- [ ] Render latest validated offer content and exact roles; escape untrusted text, cap content and preserve attribution.
- [x] NEXT-08: inactive revision-bound display DTO and exact-host renderer with bounded escaped text, attribution, canonical frozen JSON/hash and PreparedPayload. Durable validated-content loading remains open; see ALERT_RENDERING.md.
- [ ] Test serialized allowed_mentions: no broad parse/everyone/user/reply mentions and only exact approved roles.
- [x] NEXT-08: local serialized JDA payload regressions verify empty broad parse/users, replied_user=false and exactly configured roles. Real transport serialization/cancellation remains open.
- [ ] Implement JDA adapter with queued-request deadline and proven completion/cancellation semantics; never disable rate limits.
- [ ] Classify 429, permission/channel/token failures, invalid payload, proven nonacceptance and ambiguous acceptance correctly.
- [ ] Enforce retry attempt/age/offer deadlines in the composed runtime, including preflight deferrals.
- [ ] Persist bounded pending receipts with retry; unknown commits must resolve before releasing capacity.
- [ ] Implement privileged reconciliation with evidence, audit and conflict handling; never redrive SENT.
- [ ] Wire startup stage ordering and early shutdown admission signal, then bounded drain via Main cleanup hooks.

## 6. Manual publishing (later)

- [ ] Private preview bound to actor, scope, content/role/config revisions and expiry.
- [ ] Guild-local authorization or explicit operator allowlist; no global audience inferred from channel permissions.
- [ ] Freeze large target plans in bounded pages; confirmation cannot add newly joined destinations.
- [ ] Idempotent confirm transaction, canonical event reuse, explicit scoped catch-up and audited recovery rules.
- [ ] Status counts and cancellation semantics; uncertain/sent jobs cannot be silently republished.
- [ ] Test stolen controls, stale preview, revoked authority, duplicate confirmation and manual/automatic deduplication.

## 7. Good Deals (later, independent opt-in)

- [ ] Country/currency-aware exact pricing, approved store policy and optional proven historical/review evidence.
- [ ] Versioned deterministic ranking and bounded daily digest; free-only subscriptions never receive paid offers.
- [ ] Destination/incarnation/local-date schedule identity; test timezone and daylight-saving boundaries.
- [ ] Revalidate entries/roles before sending; skip empty digests and ping approved role union once.
- [ ] Transactional repeat suppression retained for uncertain outcomes; same source identity changes cannot reset it.
- [ ] Residual capacity/priority policy and burst smoothing; shared Discord quota and global admission.

## 8. Existing bot quality and compatibility

- [x] Immutable handler configuration/registries, access gates, per-invocation cooldown ownership and bounded per-user pools.
- [x] Existing timer/account optimistic revisions and transactions; preserve BSON/collection names.
- [x] Centralize safe handler/timer error replies; explicitly classify validation failures and hide arbitrary exception details.
- [x] Extract timer session/statistics/GPA reports into TimerEmbeds, preserving existing service and storage boundaries.
- [x] Bound GPA/pending-menu registries; test overload, owner replacement and failed-send cleanup (AUD-02).
- [ ] Stress expiry/callback/shutdown races under overload with deterministic scheduling and real callback failures.
- [x] Review legacy Utils and InfoEmbeds by responsibility; follow-up utility/service extraction is recorded in AUDIT_REPORT.md.
- [x] Bind semester archival to the revision used to validate confirmation; preserve atomic account/history updates.
- [x] Run existing command/component/subject editing/session coverage and add GPA pagination regression for this refactor; future changes must preserve it.

## 9. Operations, security and release verification

- [ ] Export provider age, backlog count/age, fan-out reconciliation, in-flight requests, queue delay, retry/uncertain/terminal outcomes and DB pressure.
- [ ] Scoped pause/resume/repair controls, operator permissions, audit and an incident runbook.
- [ ] Dependency/advisory review, secret/artifact checks, restricted database/runtime credentials and backup encryption.
- [ ] Test restart/crash after every persistence boundary and after remote acceptance; restore older backups with delivery paused.
- [ ] Run 6,000-job launch and 20,000-job overload scenarios with concurrent interactive commands; measure latency/heap/counts, not just throughput.
- [x] VERIFY-01: synthetic 6,000/20,000-job scheduler runs with 10,000 command actions each; bounded admission and exactly-once simulated completion assertions passed. Real Discord/database-backed delivery load remains unverified.
- [ ] Confirm agreed SLOs, index plans, restore drill and real transport behavior before opt-in test guilds/canary.
- [ ] Obtain deployment/notification authorization; ramp gradually with kill switches and outcome monitoring.

## Evidence and handoff index

- Historical increments: ALERT_ENGINE.md, ALERT_STORAGE.md, ALERT_PIPELINE.md. Their local tests are not integration certification.
- Current audit: CODE_AUDIT.md, including fixed findings, exact scope, remaining risks and build evidence.
- Required local build: JDK 21 and documented local Maven fallback if wrapper is unavailable; use target-upgrade when the usual artifact is locked.
- No code should claim a completed release merely because this checklist contains checked implementation items.

Historical handoff (2026-09-14): AUD-01 completed with JDK 21 offline Maven clean verify, 163 tests / zero failures or errors; packaged artifact smoke passed. Logs: .tools/audit-verify.log and .tools/audit-smoke.log. No activation or live restart. This audit handoff was followed by NEXT-01 below.

Historical handoff (2026-09-14): NEXT-01 completed with JDK 21 offline Maven clean verify: 189 tests, zero failures/errors/skips; packaged smoke passed. Logs: .tools/configuration-verify.log and .tools/configuration-smoke.log. Read ALERT_CONFIGURATION.md for interfaces, CONFIG-01 storage decision, explicit migration prerequisites and evidence limits. Next: NEXT-02 isolated replica-set verification, then NEXT-03 ownership/scheduling. No live activation or command registration.

Historical handoff (2026-09-14): phase 7 / NEXT-03 completed as an inactive backend foundation. JDK 21 offline Maven clean verify passed 217 tests (28 added), with zero failures/errors/skips; packaged smoke passed. Logs: .tools/runtime-verify.log and .tools/runtime-smoke.log. Phase 6 / NEXT-02 is deferred by user and remains required before activation. Next product stage: server setup commands (phase 8); transport/executor composition remains later. Read ALERT_RUNTIME.md before implementing a worker or changing ownership.

Latest handoff (2026-09-14): CTX-01 refreshed all 17 maintained Markdown files (including the new PROJECT_STATUS.md), verified local Markdown links, and fixed the durable attempt deadline to respect the delivery lease. JDK 21 offline Maven clean verify passed 219 tests, zero failures/errors/skips; packaged smoke passed. Logs: .tools/context-review-verify.log and .tools/context-review-smoke.log. Next is phase 8 / NEXT-04, followed by full audit AUD-02. Phase 6 remains deferred and required before activation. This focused review is not a production security/correctness certification.

Latest handoff (2026-09-14): AUD-02 was explicitly brought forward by the user and completed as a source/local-regression audit before phase 8. Fixed reserved-authorization pause, menu capacity/cleanup, affected Mongo collation and archive confirmation revision. JDK 21 offline Maven clean verify: 224 tests, zero failures/errors/skips; packaged smoke passed. Logs: .tools/full-audit-verify.log and .tools/full-audit-smoke.log. Read AUDIT_REPORT.md and PROGRESS_REPORT.md. NEXT-04 remains next; AUD-03 reviews that new integration afterward. No live activation.

Latest handoff (2026-09-19): AUD-04 completed. Source changes: SendAdmission, ScheduledDelivery, FailureDiagnostics, BunnyLog, ErrorReporter, InteractionErrors, MongoManager, InteractionListener, CommandRegistry, SubjectAutocomplete, Start and SwitchSubject. Tests: DeliverySchedulerTest plus new FailureDiagnosticsTest and SubjectAutocompleteTest. JDK 21 clean verify: 232 tests, zero failures/errors/skips; packaged smoke passed. No BSON/schema/identity changes. Phase 8 remains next; phase 6 and real transport remain release gates. See dated audit/progress follow-ups.

NEXT-04 completed 2026-09-21: current agent owns commands/alerts registration factory, bunnynexus/commands/alerts named implementations, FreebieCommandsTest and phase-8 documentation. Explicit injection; no Main changes, migration or activation. Verification: 244 tests and packaged smoke passed; see final handoff.



## Phase 8 handoff — 2026-09-21

Phase 8 server setup commands are implemented and inactive. See [ALERT_COMMANDS.md](ALERT_COMMANDS.md) for command syntax, explicit registration, identity/revision binding, outcomes and limits. JDK 21 offline Maven clean verify passed **244 tests, zero failures/errors/skips**; packaged smoke passed for target-upgrade/BunnyHub-4.00.jar. Logs: .tools/phase8-verify.log and .tools/phase8-smoke.log. Twelve new tests cover the command boundary. No schema, persisted identity, Main composition, bot restart or live send changed. AUD-03 command integration review remains next; phase 6 remains deferred and mandatory before activation.




AUD-03 / NEXT-05 started 2026-09-21: current agent owns phase-8 command audit/fixes/tests and phase-9 inactive discovery intake under alerts/adapters/providers, its tests/fixtures and handoff docs. No schema/Main/activation changes; intended evidence is JDK 21 clean verify plus packaged smoke.


NEXT-05 handoff 2026-09-21: implemented GamerPowerIntake.java, GamerPowerIntakeTest.java and synthetic src/test/resources/gamerpower/candidate.json; explicit jackson-core 2.22.2 dependency matches existing runtime. ALERT_DISCOVERY.md records source research and unfinished certification/HTTP/polling work. No discovery checklist certification item is marked complete. Current agent owns all changes; no delegated work. AUD-03 and this initial NEXT-05 increment passed 255 tests and packaged smoke.


NEXT-06 started 2026-09-21: current agent owns inactive GamerPower fetch transport, public-address policy, transport tests and discovery/status handoff docs. Explicit pinned addresses avoid unbounded runtime DNS and rebinding; no Main/schema/live activation. JDK 21 clean verify and packaged smoke required.

NEXT-06 completed 2026-09-21: GamerPowerFetcher.java, ProviderAddresses.java, GamerPowerFetcherTest.java; explicit existing-version okhttp-jvm dependency in pom.xml; ALERT_DISCOVERY.md records DISC-01 address pinning and limitations. Ten new transport tests; full clean verify passed 271 tests, zero failures/errors/skips. This increment added ten transport tests; the current workspace also includes tests added since the earlier 255-test handoff. Packaged smoke passed; logs .tools/phase9-fetch-verify.log and .tools/phase9-fetch-smoke.log. No live request, migration, Main change, restart or alert activation. Next: source fixtures/certification and durable poll orchestration.



NEXT-07 started 2026-09-21: current agent owns PollRepository, MongoPollRepository/MongoPollingSchema, SourcePoller, tests and discovery/status handoff. Explicit additive polling schedule, fenced attempt lifecycle, no candidate publication or activation. Certification review remains distinct from missing eligibility proof.


NEXT-07 completed 2026-09-21: PollRepository, MongoPollRepository, MongoPollingSchema, GamerPowerPoller and maximumFetchTime accessor; three new test suites plus captured-feed regression; attributed live-games-2026-09-21 fixture/metadata; read-only .tools probe/capture utilities. Thirteen new tests, full clean verify 284 passed and packaged smoke passed, with fixture/probe exclusion checked separately. Logs .tools/phase9-poll-verify.log, .tools/phase9-poll-smoke.log, .tools/phase9-provider-probe.log, .tools/phase9-provider-capture.log. New explicit alert-polling-v1 migration was NOT applied; earlier identity/schema checksums unchanged. Real replica-set tests remain deferred. Source certification remains partial; no certified cursor, candidate archive or publication. See ALERT_DISCOVERY.md for contract, DISC-02 and handoff dependencies.


AUD-05 started 2026-09-21: current agent audits phase-8/9 commands, intake/fetch/polling and ownership/storage boundaries; owns confirmed fixes, regression tests and audit/handoff docs. No activation/migration or independent schema changes authorized by this audit.

AUD-05 complete 2026-09-21: GamerPowerPoller, GamerPowerIntake, MongoPollingSchema and their three test suites; five regression tests added. Required clean verify passed 289 tests, zero failures/errors/skips; packaged smoke passed. Logs .tools/audit5-verify.log and .tools/audit5-smoke.log. See AUDIT_REPORT.md for findings, scope, optimization and remaining operational/database/source-certification gates. No persisted identity, migration checksum, Main or activation changes.

