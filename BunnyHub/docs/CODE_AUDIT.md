# Code audit and refactor handoff

Latest increment — 2026-09-25 (NEXT-10 / OWNER-01): owner-only prepare → verify → explicit bounded release is now persisted, audited and enforced transactionally at send authorization. Verification alone cannot publish; releases expire within one hour, count retries, bind reviewed content/template and can be revoked. **334 default tests, 16 real isolated three-node MongoDB tests and packaged smoke passed** (.tools/next10-verify.log, .tools/next10-mongo-final.log, .tools/next10-smoke.log). New additive owner-review migration tested only in disposable databases; old send writers must stop before upgrade. Private owner commands, certified ingestion, pre-fan-out gate and transport/composition remain pending. No live migration, bot restart or Discord send. See [owner-controlled publication](ALERT_OWNER_REVIEW.md).

NEXT-09 follow-up — 2026-09-25: AUD-07's payload identity finding is fixed with mandatory offer/subscription/destination binding and two new regressions. Nonce instructions and the architecture inventory are corrected. The repaired standalone probe passes. New review/polling foundations and scope are in [ALERT_OPERATIONS.md](ALERT_OPERATIONS.md): 324 tests and packaged smoke passed; no schema change or activation.

Latest review — 2026-09-23 (AUD-07): [freebie review](FREEBIE_AUDIT_2026-09-23.md) records an open payload identity authorization gap, reproduced retry nonce documentation conflict and stale architecture inventory. Existing 310 tests and packaged smoke passed; offline reproductions demonstrate gaps not covered by the default suite. This pass changes no production source, schema or activation state.

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Latest audit — 2026-09-22 (AUD-06): reviewed alert input/access, transport, polling, ownership and persistence boundaries, then fixed timer clock-rollback bugs: negative break durations, repeated subject-time credit/inconsistent aggregate totals, and incorrect paused telemetry. Four regressions added; three accounting regressions reproduced failures before the fix. JDK 21 clean verify **293 tests passed**, packaged smoke passed (.tools/audit6-verify.log and .tools/audit6-smoke.log). See docs/AUDIT_REPORT.md (AUD-06) for scope and limits. No schema/checksum changes, live requests, restart or activation. Real database verification, source certification and transport/load validation remain release gates.

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Updated 2026-09-14. Scope: source inventory and duplication searches across application Java files, with detailed review of the alert foundation, schema verification, timer presentation, and command/component error boundaries. Read [IMPLEMENTATION_TODO.md](IMPLEMENTATION_TODO.md) for the complete product sequence and work ownership protocol. This is a source audit with local regression evidence, not a claim that every execution path is bug-free or production-certified.

## Fixed findings

| Finding | Change and reason | Compatibility / evidence |
| --- | --- | --- |
| Compound index verification ignored field order | Both migration verifiers now use `MongoIndexVerifier`. BSON Document equality is map equality, so a reversed compound index previously passed. Ordered entry comparison rejects incompatible indexes without repairing them. | Migration names, index definitions and checksum algorithms are unchanged. Regression covers reversed keys, hidden/sparse/unique drift and TTL rejection; existing migration tests exercise installed schemas. |
| Identity hashing duplicated across catalog and fan-out | `StableIdentity` owns the existing namespace + length-prefixed UTF-8 SHA-256 encoding. Both callers delegate to it. | A fixed digest checked against an independent .NET encoding verifies both callers retain the persisted delivery ID. Ambiguous part boundaries remain distinct. No ID migration. |
| Generic exceptions exposed internal messages in Discord replies | Only explicit `InteractionErrors.InputFailure` and `StateFailure` messages are public. Timer validation and revision-conflict failures use these types; generic failures return a fixed message. Public validation text is bounded and neutralizes mention syntax. | Both types preserve their previous IllegalArgumentException/IllegalStateException superclass. Existing session-menu tests retain the useful expired-menu reply; regression rejects internal exception text. This does not certify logging redaction. |
| Timer facade mixed storage/session orchestration with large reports | Extracted `TimerEmbeds` for active-session, statistics and academic-record rendering from loaded models. `Timers` keeps its existing public entry points and validation. | No BSON/collection changes. GPA regression checks pagination and that presentation sorting does not mutate the stored list. Existing session/persistence/subject tests remain required. |
| Offer replacement decoded the same stored document repeatedly | Observation admission decodes the previous offer once and checks its canonical key as well as revision/edition/freshness constraints. | Existing transactional adapter tests cover observation conflicts; real transaction tests remain a release gate. |
| Persisted job counters and backward clock callbacks admitted impossible values | Snapshot validation rejects generation at/above revision, attempts above generation and due dates before creation. Owned transitions reject a clock before creation or the frozen attempt start. | Valid generated state transitions remain unchanged. Added malformed-counter and backward-clock regressions alongside recovery/ownership tests. |

## Structure and responsibility review

There are no 1,000-line application Java files in the inspected tree. Before extraction, `Timers.java` was 659 lines; after extraction it is 338, with `TimerEmbeds.java` at 361. The largest remaining files are `InfoEmbeds.java` (576), `Utils.java` (555), and `TimerSessionService.java` (503). Line counts include comments and imports and are a navigation signal, not a correctness metric.

Alert domain objects own immutable state and validation; application engines depend on repository ports; Mongo adapters own persistence; runtime admission owns local capacity. Constructors do not activate discovery, run migrations or send notifications. The shared handler does not import alert or timer implementation classes. Its per-user scheduler bounds synchronous command work, not durable delivery or outstanding asynchronous Discord requests.

This pass removed duplicate hashing and index-verification algorithms rather than combining unrelated business transitions into a generic helper. Pure presentation has a named class with loaded dependencies. Existing transaction/revision boundaries and public timer entry points were preserved. `InfoEmbeds` is a presentation module; `Utils` remains a broader legacy cleanup candidate. No mechanical class splitting or invented inheritance hierarchy was used to reduce line counts.

## Outstanding findings and release gates

1. **High, before alert activation:** prove transaction conflicts, unknown commits, index/collation behavior, failover and counter reconciliation against an isolated Mongo replica set. Mock tests cannot establish these guarantees. No local Mongo replica-set runtime was available during this pass; never substitute the live GBF database.
2. **High, before alert activation:** implement and verify one runtime/egress owner, real transport classification, bounded queued requests and receipts, cancellation semantics, allowed mentions and restart recovery. The implemented classes are an inactive foundation.
3. **Medium, existing bot resource hardening:** GPA and pending-session menus need explicit global capacity budgets in addition to expiration; preserve ownership checks when adding admission/rejection and cleanup tests.
4. **Medium, security verification:** perform a dependency/advisory scan and operational log-redaction review. This source audit did not check current external advisories or inspect secrets. Existing logging is not covered by the public-reply guarantee.
5. **Maintenance:** review `Utils` by cohesive responsibilities and callers; extend visual/content boundary coverage for legacy embeds. The extraction is not a full UI rewrite or complete validation of every possible legacy record.
6. **Capacity:** the approximately 1,000-server launch target is planning input. No Discord benchmark, live database load test, or delivery SLO validation was performed.

The checklist assigns these remaining tasks and separates local implementation from integration and release evidence. Do not mark the entire app secure or ready to broadcast based on this audit.

## Verification

Use JDK 21 and the documented offline Maven runtime/cache with `clean verify`, output directory `target-upgrade`. The Maven wrapper is unavailable in this workspace; the documented local runtime is the fallback. Build log: `.tools/audit-verify.log`. Artifact smoke log: `.tools/audit-smoke.log`.

The first audit run caught an expired-session message regression introduced by the stricter error boundary. The fix explicitly classified that business failure; it did not weaken the generic-exception policy. Final clean verify passed: 163 tests, zero failures/errors/skips. Packaged smoke passed command/component discovery, JDA payloads, BSON codecs, logging, manifest and secret/test exclusions. The handoff checklist records the same evidence.

No live bot restart, migration, provider request, subscription command, or notification was performed. The deliverable is the audit/refactor and shared roadmap; the next feature task remains unclaimed.

## Focused continuity review before phase 8 — 2026-09-14

Reviewed recent configuration access/transactions and runtime ownership/scheduling/authorization boundaries. Fixed a mismatch between the persisted attempt deadline and the shorter delivery lease: AutomaticSendEngine now stores the earliest applicable deadline. Pure and Mongo-boundary regressions verify delivery-lease capping and earlier offer/permission cutoffs. No identities, BSON fields or migration checksums changed. All 219 tests and packaged smoke passed; context-review logs and the complete handoff are in PROJECT_STATUS.md.

No additional critical defect was established in this focused source pass. This does not certify the untested real database/transport behavior or complete the broader audit requested after phase 8; those remain explicit tasks in IMPLEMENTATION_TODO.md.

## Subsequent broader audit — AUD-02

The user brought the audit forward before phase 8. [AUDIT_REPORT.md](AUDIT_REPORT.md) records the newer fixes, risk-focused scope, remaining release gates and maintainability assessment. [PROGRESS_REPORT.md](PROGRESS_REPORT.md) records 224 passing tests and packaged smoke. The earlier 163/219-test evidence and open-menu-cap findings above are historical; menu bounds now exist, while callback/expiry stress remains pending.

## Audit continuation — 2026-09-19

AUD-04 fixes post-receipt/persistence-pause transport admission, removes arbitrary exception messages from application error diagnostics, and shares subject autocomplete with early termination after 25 matches. See AUDIT_REPORT.md for scope and remaining limitations; 232 tests and packaged smoke passed. No activation.



## AUD-05 — 2026-09-21

Focused phase-8/9 discovery and ownership/storage audit completed with three confirmed fixes and an intake allocation optimization. Five regressions added; 289 tests and packaged smoke passed. See [audit report](AUDIT_REPORT.md) for exact scope and unresolved release gates. This was not a whole-repository/dependency certification.

