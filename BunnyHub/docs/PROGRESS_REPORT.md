# Progress report — 2026-09-14

Latest increment — 2026-09-25 (NEXT-10 / OWNER-01): owner-only prepare → verify → explicit bounded release is now persisted, audited and enforced transactionally at send authorization. Verification alone cannot publish; releases expire within one hour, count retries, bind reviewed content/template and can be revoked. **334 default tests, 16 real isolated three-node MongoDB tests and packaged smoke passed** (.tools/next10-verify.log, .tools/next10-mongo-final.log, .tools/next10-smoke.log). New additive owner-review migration tested only in disposable databases; old send writers must stop before upgrade. Private owner commands, certified ingestion, pre-fan-out gate and transport/composition remain pending. No live migration, bot restart or Discord send. See [owner-controlled publication](ALERT_OWNER_REVIEW.md).

Latest increment — 2026-09-25 (NEXT-09): see [selected launch operations and verification](ALERT_OPERATIONS.md). Added pure review policy, explicit inactive recurring polling lifecycle, payload identity fix and 14 regressions. Two bounded live provider checks matched Epic's free product listings before/after rollover. **324 tests passed**, packaged smoke and repaired audit probe passed. Durable review enforcement, certified ingestion and real transport remain unimplemented; no activation or migration.

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Latest audit — 2026-09-22 (AUD-06): reviewed alert input/access, transport, polling, ownership and persistence boundaries, then fixed timer clock-rollback bugs: negative break durations, repeated subject-time credit/inconsistent aggregate totals, and incorrect paused telemetry. Four regressions added; three accounting regressions reproduced failures before the fix. JDK 21 clean verify **293 tests passed**, packaged smoke passed (.tools/audit6-verify.log and .tools/audit6-smoke.log). See docs/AUDIT_REPORT.md (AUD-06) for scope and limits. No schema/checksum changes, live requests, restart or activation. Real database verification, source certification and transport/load validation remain release gates.

Latest audit — 2026-09-21 (AUD-05): fixed fetch admission after slow pressure checks, duplicate identity detection involving malformed source rows, and provisioning that could reset unversioned polling state. Intake now retains only required fields. Five regressions added; JDK 21 clean verify **289 tests passed**, packaged smoke passed (.tools/audit5-verify.log and .tools/audit5-smoke.log). Focused phase-8/9 and ownership/storage audit; detailed scope and open risks in AUDIT_REPORT.md. No schema/checksum changes, live requests, restart or activation. Real database verification and source certification remain release gates.


Latest increment — 2026-09-21 (NEXT-07): inactive durable shadow polling now has source-lease/attempt fences, database-time due scheduling, bounded summaries, backoff and persisted known pauses. One application GamerPower probe returned HTTP 200 (20 candidates, nine unknown ends); an attributed raw success fixture is covered by regression tests. **284 tests passed**, packaged smoke passed; .tools/phase9-poll-verify.log and .tools/phase9-poll-smoke.log. Source eligibility/identity certification, certified ingestion and deferred phase 6 remain open. No migration, recurring polling, bot restart or alert publication was activated. See DISC-02 in ALERT_DISCOVERY.md.


Latest increment — 2026-09-21 (NEXT-06): inactive GamerPower HTTP fetch layer implemented with explicit public address pins, fixed HTTPS endpoint, finite deadlines, streamed byte limits, one-call admission, pacing/backoff, rate-limit pause and shutdown cancellation. Ten new transport tests include real loopback stalled-body timeout. Full JDK 21 clean verify: **271 tests passed**, packaged smoke passed; logs .tools/phase9-fetch-verify.log and .tools/phase9-fetch-smoke.log. See [discovery notes](ALERT_DISCOVERY.md). Provider certification, durable polling and phase-6 real database verification remain outstanding; nothing activated.


Latest handoff — 2026-09-21: AUD-03 phase-8 audit completed with fixes to input/storage error classification, required boolean parsing and selective status rendering. Phase 9 has started with inactive, bounded GamerPower candidate intake; [discovery notes](ALERT_DISCOVERY.md). Full JDK 21 clean verify: **255 tests passed**, packaged smoke passed. Logs: .tools/phase8-audit-tests.log, .tools/phase9-intake-verify.log and .tools/phase9-intake-smoke.log. No migration, bot restart or alert activation. Phase 6 remains deferred and mandatory before activation.


## Latest increment — 2026-09-19

AUD-04 added transport checks for recorded outcomes and persistence pauses, centralized bounded exception diagnostics across application error paths, and consolidated subject autocomplete with early termination after 25 matches. Eight regression tests were added to the 224-test baseline. Phase 8 server setup commands remain next; no alert feature was activated.

Verification logs: `.tools/audit-followup-verify.log` and `.tools/audit-followup-smoke.log`; final results appear below. Later sections describing 2026-09-14 remain historical.

## Current position

The alert system has an implemented backend foundation through configuration and ownership/scheduling. It is inactive. The existing timer/info/avatar bot remains the running product. Phase 8 server setup commands are next; the user brought the broader source audit forward before that stage.

| Stage | Status | What exists / remains |
| --- | --- | --- |
| 1–4: handler and alert foundation | Implemented, locally tested | Bounded command/autocomplete execution, safe errors, engine identities/rules, Mongo catalog/outbox/fan-out/delivery transactions |
| 5: configuration backend | Implemented, inactive | Immutable policy/service, cached Discord permission checks, transactional revisions/projections/audit/tombstones |
| 6: real database verification | Deferred by user; release gate | Replica-set races/crashes, unknown commits, migrations, query plans and recovery still required |
| 7: runtime foundation | Implemented, inactive | Runtime/source ownership, send fences, bounded scheduling and recovery, protected asynchronous capacity |
| AUD-02: broader source audit | Performed before phase 8 | Verified fixes, local regression evidence, maintainability assessment and explicit remaining risks in AUDIT_REPORT.md |
| 8: server setup commands | Implemented, inactive (2026-09-21) | Named private guild-only setup/edit/status/toggle/remove over ConfigurationService; explicit registration; see ALERT_COMMANDS.md |
| Later delivery stages | Pending | Certified discovery, source polling, composed bounded executors, rendering/transport, receipt retries, operators, metrics and rollout verification |
| Manual publishing / deals | Later designs | Separate audience/confirmation and opt-in policy work; no activation |

The 1,000-server goal is a planning target, not a measured guarantee. Product limits, hosting budgets and latency objectives remain open. No percentage-complete estimate is useful while integration and operational evidence remain outstanding.

## Changes delivered in this audit

1. Previously reserved sends now respect a receipt-persistence pause before entering authorization.
2. GPA and pending-study menu registries have explicit bounds; failed GPA sends release retained state promptly and busy pending-menu callbacks remove unusable controls.
3. Older Mongo queue, fan-out and observation operations now use explicit case-sensitive identity comparison consistently with existing indexes and newer adapters.
4. Semester archiving is tied to the record revision used for confirmation, closing a check-then-reload race.
5. Added regression coverage, strengthened existing Mongo option assertions, and wrote a separate findings/risk report.

No persisted identity, BSON field name, migration checksum or active configuration changed. The archive service signature and pending-menu admission result changed internally; repository callers are updated. Technical menu caps are 1,024 per registry, two GPA menus per user, and 100 pages per GPA menu.

## Verification evidence

JDK 21 offline Maven **clean verify passed: 224 tests, zero failures, errors or skips** (five tests added since the 219-test handoff). Log: `.tools/full-audit-verify.log`.

Packaged smoke passed for `target-upgrade/BunnyHub-4.00.jar`: JDA payloads, command discovery, logging provider, BSON codecs, manifest and secret/test exclusions. Log: `.tools/full-audit-smoke.log`. No live restart or database connection was used.

Checks cover existing handler/command/timer behavior and alert policy/storage/runtime regressions. The first runs exposed old mock overloads after adding explicit Mongo options; these were updated to assert the new options. The evidence is local and does not certify replica-set recovery, real Discord delivery or production security.

## Next work, in order

1. Phase 8 implemented 2026-09-21; review its explicit registration and command/service integration (AUD-03). See ALERT_COMMANDS.md.
2. Review and test the new command/service integration after phase 8. This audit cannot cover code that has not been written.
3. Complete deferred phase 6 in an isolated replica set before enabling alerts. Add the collation/race cases recorded in the audit.
4. Certify provider behavior and implement bounded discovery and transport composition, including receipt persistence, role mentions, retries and uncertainty.
5. Finish log redaction, full dependency review, operational controls, load tests and restore verification before an authorized canary.

See [IMPLEMENTATION_TODO.md](IMPLEMENTATION_TODO.md) for the complete end-to-end checklist and [AUDIT_REPORT.md](AUDIT_REPORT.md) for severities, exact reviewed boundaries and unresolved risks.

## Final AUD-04 verification — 2026-09-19

JDK 21 offline Maven clean verify passed **232 tests, zero failures/errors/skips**. Packaged smoke passed for target-upgrade/BunnyHub-4.00.jar, including secret/test exclusions, payloads, discovery, logging and codecs. Logs: .tools/audit-followup-verify.log and .tools/audit-followup-smoke.log. No bot restart, migration or live send occurred. New source files: FailureDiagnostics and SubjectAutocomplete; modified callers and runtime permit checks are detailed in AUDIT_REPORT.md.


## Phase 8 handoff — 2026-09-21

Phase 8 server setup commands are implemented and inactive. See [ALERT_COMMANDS.md](ALERT_COMMANDS.md) for command syntax, explicit registration, identity/revision binding, outcomes and limits. JDK 21 offline Maven clean verify passed **244 tests, zero failures/errors/skips**; packaged smoke passed for target-upgrade/BunnyHub-4.00.jar. Logs: .tools/phase8-verify.log and .tools/phase8-smoke.log. Twelve new tests cover the command boundary. No schema, persisted identity, Main composition, bot restart or live send changed. AUD-03 command integration review remains next; phase 6 remains deferred and mandatory before activation.









