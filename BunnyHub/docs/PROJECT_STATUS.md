# Current project status and handoff

Latest increment — 2026-09-25 (NEXT-10 / OWNER-01): owner-only prepare → verify → explicit bounded release is now persisted, audited and enforced transactionally at send authorization. Verification alone cannot publish; releases expire within one hour, count retries, bind reviewed content/template and can be revoked. **334 default tests, 16 real isolated three-node MongoDB tests and packaged smoke passed** (.tools/next10-verify.log, .tools/next10-mongo-final.log, .tools/next10-smoke.log). New additive owner-review migration tested only in disposable databases; old send writers must stop before upgrade. Private owner commands, certified ingestion, pre-fan-out gate and transport/composition remain pending. No live migration, bot restart or Discord send. See [owner-controlled publication](ALERT_OWNER_REVIEW.md).

Latest increment — 2026-09-25 (NEXT-09): selected launch review/command/offline behavior is documented in [ALERT_OPERATIONS.md](ALERT_OPERATIONS.md). Added inactive publication-review rules and explicit polling lifecycle, and fixed AUD-07 payload identity authorization. Two real source probes matched Epic's free product pages before/after rollover; latest candidates are Mechabellum and Astrea, with market/UTC certification still open. **324 tests passed**, packaged smoke and repaired audit probe passed (.tools/next09-*.log). No Main wiring, migration, durable review gate or live delivery. Next: durable content/evidence/review storage and transactional enforcement.

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Latest audit — 2026-09-22 (AUD-06): reviewed alert input/access, transport, polling, ownership and persistence boundaries, then fixed timer clock-rollback bugs: negative break durations, repeated subject-time credit/inconsistent aggregate totals, and incorrect paused telemetry. Four regressions added; three accounting regressions reproduced failures before the fix. JDK 21 clean verify **293 tests passed**, packaged smoke passed (.tools/audit6-verify.log and .tools/audit6-smoke.log). See docs/AUDIT_REPORT.md (AUD-06) for scope and limits. No schema/checksum changes, live requests, restart or activation. Real database verification, source certification and transport/load validation remain release gates.

Latest audit — 2026-09-21 (AUD-05): fixed fetch admission after slow pressure checks, duplicate identity detection involving malformed source rows, and provisioning that could reset unversioned polling state. Intake now retains only required fields. Five regressions added; JDK 21 clean verify **289 tests passed**, packaged smoke passed (.tools/audit5-verify.log and .tools/audit5-smoke.log). Focused phase-8/9 and ownership/storage audit; detailed scope and open risks in AUDIT_REPORT.md. No schema/checksum changes, live requests, restart or activation. Real database verification and source certification remain release gates.


Latest increment — 2026-09-21 (NEXT-07): inactive durable shadow polling now has source-lease/attempt fences, database-time due scheduling, bounded summaries, backoff and persisted known pauses. One application GamerPower probe returned HTTP 200 (20 candidates, nine unknown ends); an attributed raw success fixture is covered by regression tests. **284 tests passed**, packaged smoke passed; .tools/phase9-poll-verify.log and .tools/phase9-poll-smoke.log. Source eligibility/identity certification, certified ingestion and deferred phase 6 remain open. No migration, recurring polling, bot restart or alert publication was activated. See DISC-02 in ALERT_DISCOVERY.md.


Latest increment — 2026-09-21 (NEXT-06): inactive GamerPower HTTP fetch layer implemented with explicit public address pins, fixed HTTPS endpoint, finite deadlines, streamed byte limits, one-call admission, pacing/backoff, rate-limit pause and shutdown cancellation. Ten new transport tests include real loopback stalled-body timeout. Full JDK 21 clean verify: **271 tests passed**, packaged smoke passed; logs .tools/phase9-fetch-verify.log and .tools/phase9-fetch-smoke.log. See [discovery notes](ALERT_DISCOVERY.md). Provider certification, durable polling and phase-6 real database verification remain outstanding; nothing activated.


Latest handoff — 2026-09-21: AUD-03 phase-8 audit completed with fixes to input/storage error classification, required boolean parsing and selective status rendering. Phase 9 has started with inactive, bounded GamerPower candidate intake; [discovery notes](ALERT_DISCOVERY.md). Full JDK 21 clean verify: **255 tests passed**, packaged smoke passed. Logs: .tools/phase8-audit-tests.log, .tools/phase9-intake-verify.log and .tools/phase9-intake-smoke.log. No migration, bot restart or alert activation. Phase 6 remains deferred and mandatory before activation.


Context refreshed 2026-09-21. Start here for the current state, then read [IMPLEMENTATION_TODO.md](IMPLEMENTATION_TODO.md) and the relevant subsystem document. Historical reports retain their original dates, test counts and measurements.

AUD-04 added final transport admission checks, bounded application exception diagnostics and shared autocomplete that stops formatting after 25 matches. Read the dated follow-up in [AUDIT_REPORT.md](AUDIT_REPORT.md) and [PROGRESS_REPORT.md](PROGRESS_REPORT.md). This is continued audit/optimization work; phase 8 is now implemented but inactive; see [command notes](ALERT_COMMANDS.md). No alert runtime is active.

## Where we are

| Conversation phase | State | Evidence / next action |
| --- | --- | --- |
| 1–4: handler, engine, storage foundation and initial cleanup | Implemented and locally tested | Handler contracts, engine/storage/pipeline reports and initial code audit |
| 5: server configuration backend | Implemented, inactive | Immutable policy, access checks, revisions, audit/idempotency and Mongo projections; [configuration notes](ALERT_CONFIGURATION.md) |
| 6: real MongoDB integration tests | Deferred by the user, still mandatory before activation | Isolated replica set, concurrent writers/claimers, unknown commits, failure/restart, migrations and query plans; never use live GBF |
| 7: ownership and scheduling backend | Implemented, inactive | Runtime/source leases, mandatory send-authorization fence, bounded scheduling/recovery and protected work; [runtime notes](ALERT_RUNTIME.md) |
| 8: server setup commands | Implemented, inactive | Named setup/edit/status/toggle/remove commands, verified interaction identity, private bounded replies, revision/receipt outcomes; [command notes](ALERT_COMMANDS.md). No components or autocomplete introduced. |
| AUD-02 | Broader source audit completed before phase 8 at the user's request | See [audit report](AUDIT_REPORT.md) and [progress report](PROGRESS_REPORT.md); review new command integration again after phase 8 |
| 9: discovery | Started, inactive | Bounded intake/fetch, live success fixture and inactive durable shadow schedule; certification/mapping/ingestion still pending; ALERT_DISCOVERY.md |
| 10–14 | Pending | Transport/executor composition, operations, release verification, manual publishing and optional deals |

“Phase/section 8” in the conversation means **server setup commands**. It maps to checklist section 3 (configuration and command UX), not checklist section 8 (existing bot maintenance). The checklist groups tasks by subsystem; its headings are not the conversation phase numbers.

## Implemented does not mean activated

The existing timer/info/avatar bot remains the live application. No alert command registration, schema migration, source provisioning, provider polling, background alert runtime or live notification was activated. Main still does not compose the alert service. Do not restart the live bot just to verify code.

The approximately 1,000-server target is planning input. Markets, offer policies, destination/role limits, hosting resources and delivery latency commitments are not finalized. Constructor budgets and local tests are not measured launch capacity. Manual publishing and deals remain later opt-in workflows.

## Current contracts for the next contributor

- Keep handler routing/access/cooldown conventions and existing timer transactions/BSON names intact. Alert commands must be thin registrations delegating to named implementation classes.
- Route configuration changes through ConfigurationService and the shared guild-revision transaction. Derive actor/guild IDs from the real interaction; never trust form/custom-ID ownership without validation.
- AuthorizationRequest requires current runtime ownership as well as delivery ownership. A known SENDING commit precedes transport. Attempt validity must end at the earliest offer/freshness, permission, runtime-owner or delivery-lease deadline. Renewal must not silently extend an already-frozen attempt.
- Preserve event/channel deduplication, destination incarnations, generation fences and uncertainty. Retain capacity until both transport termination and outcome persistence are known.
- Keep bounded scheduler/control work separate from gateway and interactive command threads. Stop-before-start deployments remain mandatory; database ownership cannot retract an external Discord request.
- Migrations remain explicit with alert writers stopped. Preserve prior checksums and ownership generations. Never auto-import existing configuration or drop durable records during rollback.

## Latest audit before phase 8

The user brought AUD-02 forward. The broader source audit fixed persistence-pause admission, bounded legacy menus and failed-send cleanup, standardized affected Mongo identity comparisons, and bound semester archival to the confirmed revision. Full details and unresolved risks are in [AUDIT_REPORT.md](AUDIT_REPORT.md).

JDK 21 offline Maven clean verify passed **224 tests**, zero failures/errors/skips; packaged smoke passed. Logs: `.tools/full-audit-verify.log` and `.tools/full-audit-smoke.log`. No alert activation or live restart occurred. Phase 8 remains next; real Mongo/Discord, full dependency and log-redaction verification remain open.

## Historical focused review before AUD-02

The current pass refreshed every maintained root/docs Markdown file and reviewed the recent configuration/runtime/authorization code for high-impact correctness issues. It is not the requested full post-phase-8 audit or a security certification.

One deadline mismatch was fixed: the pure authorization rule could persist an attempt window beyond the delivery lease even though the scheduler used the shorter local deadline. The engine now caps the stored attempt at that lease too. Regression coverage checks the persisted BSON deadline and preserves earlier permission/offer cutoffs. Identity/schema formats are unchanged; old frozen attempts are not rewritten and must not have their deadlines extended by a worker.

Remaining release checks include real Mongo isolation/failover/recovery, real Discord cancellation and allowed-mentions behavior, dependency/advisory and log-redaction review, menu capacity limits, operational controls and load/restore tests. This pass cannot establish that no critical bug exists in untested production conditions.

Verified: JDK 21 offline Maven clean verify passed **219 tests**, zero failures/errors/skips. Packaged smoke passed; all 17 maintained Markdown files carry current-context links and no broken local Markdown links were found. Logs: `.tools/context-review-verify.log` and `.tools/context-review-smoke.log`.

## Reading order

1. [AGENTS.md](../AGENTS.md): repository rules and scope.
2. [Implementation checklist](IMPLEMENTATION_TODO.md): ownership ledger, completed work and remaining tasks.
3. [System architecture](SYSTEM_ARCHITECTURE.md) and [source/workflow design](ALERT_SOURCES_AND_FEATURES.md): invariants and proposed product decisions.
4. [Configuration](ALERT_CONFIGURATION.md) and [runtime](ALERT_RUNTIME.md): current phase-5/7 APIs, migration and lifecycle contracts.
5. [Handler](BUNNYHUB_2.md), [initial audit](CODE_AUDIT.md), and dated engine/storage/pipeline/review reports as needed for history.



## Phase 8 handoff — 2026-09-21

Phase 8 server setup commands are implemented and inactive. See [ALERT_COMMANDS.md](ALERT_COMMANDS.md) for command syntax, explicit registration, identity/revision binding, outcomes and limits. JDK 21 offline Maven clean verify passed **244 tests, zero failures/errors/skips**; packaged smoke passed for target-upgrade/BunnyHub-4.00.jar. Logs: .tools/phase8-verify.log and .tools/phase8-smoke.log. Twelve new tests cover the command boundary. No schema, persisted identity, Main composition, bot restart or live send changed. AUD-03 command integration review remains next; phase 6 remains deferred and mandatory before activation.








