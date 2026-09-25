# Code audit report — 2026-09-14

## Release verification follow-up — 2026-09-23 (VERIFY-01)

The user resumed phase-6 database work. Added an opt-in three-node local MongoDB fixture and 12 passing integration scenarios, plus 6,000/20,000-job synthetic delivery scenarios with concurrent command work. Captured three genuine provider responses and replaced the HTTP-201 certification gap with strict validation of the documented, captured no-results envelope. Four provider regressions added; final clean verify passed 301 tests and packaged smoke passed. Schema installation was limited to disposable test databases; no live activation or Discord send. See [ALERT_VERIFICATION.md](ALERT_VERIFICATION.md) for precise coverage, measurements, tool provenance and unresolved release gates. No persisted identity or migration checksum changes were made.

## Source and timer accounting audit — 2026-09-22 (AUD-06)

Reviewed phase-8 command input/access/error boundaries, configuration service and Mongo commit guards; phase-9 intake, pinned HTTP transport, durable polling and provisioning; runtime ownership, send admission, scheduled delivery and fair candidate scheduling. Expanded review into timer session/pending-menu/archival flows, exception diagnostics and packaging exclusions. This is a scoped source review and local regression run, not an exhaustive security certification or a proof of zero bugs.

Fixed in TimerSessionService:

| Severity | Finding | Fix and regression evidence |
| --- | --- | --- |
| Medium | A backward wall-clock adjustment during a break made normal resume subtract break time. Later study-time calculations could then award time that was not studied. | Clamp newly elapsed break duration to zero, matching recovery-stop behavior. Regression starts with 60 recorded break seconds and a future break start; resume preserves all 60 seconds and persists the transition. |
| Medium | Switching subjects while elapsed time was below already allocated time reset the allocation watermark. Once the clock caught up, later switches could credit those seconds again. Stopping during the rollback also undercounted semester/lifetime totals relative to subject totals. | Shared active-time calculation preserves already allocated seconds for switches, stop and telemetry. Regressions verify no duplicate subject credit after simulated catch-up and consistent aggregate totals on stop. Existing ordinary-session and reward-cap tests remain. |
| Low | Telemetry inferred paused state from a positive elapsed break duration, displaying a running timer when a break had just started or its timestamp was ahead of the clock. | Read paused state from the persisted break-start marker and clamp live break duration. Regression covers a future break timestamp. |

Three accounting regressions failed against the original implementation (.tools/audit6-reproduce.log), then the focused timer suite passed 24 tests (.tools/audit6-focused.log). A fourth regression covers paused telemetry. Full JDK 21 clean verification and packaged smoke evidence are recorded in the current status/progress handoffs (.tools/audit6-verify.log and .tools/audit6-smoke.log).

No alert architecture/schema/checksum changes, provider requests, database migration, restart or alert activation. Timer writes retain the existing optimistic revision and transaction paths. The shared calculation removes drift between three accounting/display paths; no throughput improvement is claimed.

Remaining limits: persisted wall-clock timestamps cannot reconstruct actual elapsed time across arbitrary clock jumps; this patch preserves known allocated time and prevents negative new breaks, not historical repair. Real Mongo replica-set races, commit uncertainty and migration testing (deferred phase 6), source certification/ingestion, Discord transport composition and measured load remain release gates. No live penetration test or current dependency-advisory scan was performed; dependency security is not certified by the passing tests.

## Discovery/polling audit — 2026-09-21 (AUD-05)

Scope: detailed review of GamerPowerPoller, GamerPowerFetcher, GamerPowerIntake, ProviderAddresses, PollRepository, MongoPollRepository and MongoPollingSchema; comparison with RuntimeOwnership and Mongo ownership fences; focused reread of phase-8 command input/error handling and BSON validation. Searched alert/command production sources for exception-message leakage, blocking completion, sleeps and unbounded executor construction. This is a focused source/local regression audit, not a full-repository proof, dependency advisory scan or production certification.

| Priority | Confirmed issue | Fix and regression |
| --- | --- | --- |
| Medium | Poller checked remaining lease time before invoking the second storage-pressure callback. A slow callback could exhaust the lease or close the poller, then return true and allow HTTP to start. | Recheck shutdown and conservative lease budget immediately after that callback, before fetch. Budget is capped by configured lease duration and rejects negative elapsed time. Two regressions consume the budget or close inside the callback and verify no fetch. Database completion fences still protect against later expiry; the local check cannot retract an already-started request. |
| Medium | Duplicate listing IDs were checked only after all row validation. A valid row plus a malformed row with the same ID became a partial feed retaining one side of an ambiguous identity. | Track syntactically valid source IDs before candidate validation. Either ordering now rejects the whole batch as DUPLICATE_ID. No canonical campaign identity is inferred. |
| Medium | Provisioning a source without pollFormat overwrote any unversioned polling fields, potentially clearing an existing pause/backoff. | Read and reject unversioned poll/nextPollAt fields before writes. Regression verifies paused unversioned state is rejected without update. Explicit stop-before-start provisioning remains required. No existing migration checksum was changed. |
| Optimization | Intake allocated map entries and materialized values for every unused provider string field, despite needing only eleven fields. | Retain only the required field allowlist; skip unused values through the constrained parser. Regression preserves decoded candidates with large nested unused metadata and rejects malformed structure. The byte/token/depth bounds remain; no measured throughput claim is made. |

Five new regressions accompany these changes. Final clean verify and packaged-smoke totals are recorded in PROJECT_STATUS.md and PROGRESS_REPORT.md; logs are .tools/audit5-verify.log and .tools/audit5-smoke.log.

Remaining findings/release constraints:

- Real Mongo execution of aggregation updates, conditional completion, migration restart and concurrent source/configuration writers remains unverified (deferred phase 6). Driver-boundary tests are not isolation/recovery evidence.
- Known rate-limit pauses persist, but a crash before pause persistence may lose that knowledge. Audited pause reconciliation/resume and operational visibility remain missing, as already recorded in DISC-02.
- Source eligibility, market/timezone/campaign identity and genuine empty/error/recurrence fixtures remain uncertified. Partial shadow candidates cannot safely be promoted to announcements.
- Poll ticks still require future bounded owner/executor composition; repeatedly calling a not-due tick incurs ownership writes. Measure and pace that composition rather than adding an unverified cache or changing durable ownership semantics here.
- No line-by-line audit of the timer subsystem, full dependency review, real Discord cancellation/permission certification or 1,000-server capacity test was performed in this pass.

No provider request, schema installation, bot restart or alert activation occurred during AUD-05. Source identity, Mongo field names and migration checksums are preserved. Only provisioning of inconsistent, unversioned poll state becomes more restrictive.

## Phase 8 integration audit — 2026-09-21 (AUD-03)

Completed the requested phase-8 source/local regression audit before starting the phase-9 intake increment. Reviewed all nine phase-8 production classes and FreebieCommandsTest, plus the handler's access/defer/worker dispatch path, ConfigurationService, ConfigurationAccess requirements, cached JDA authorization and Mongo configuration transaction/receipt boundary. Existing tests cover revisions, replay, exact roles, access-before-read and deletion. No live database or Discord test was performed.

| Finding | Resolution and evidence |
| --- | --- |
| Medium: generic IllegalArgumentException from stored-record decoding or repository commit was labelled invalid user settings, suppressing diagnostics and obscuring an uncertain outcome | Restrict input-error conversion to parsing the immutable command request. Unexpected service/storage validation errors now use the diagnostic and unconfirmed-outcome path. A regression injects failures into both commit and load. |
| Low: missing required enabled option became an internal null failure rather than an input error | Explicit required-boolean parsing rejects before storage access; regression verifies no repository calls. |
| Low: every status request rendered all pages, although only one was returned | Count bounded pages, then format only the selected setting/role chunk. Regression crosses an empty destination and multiple role pages; existing maximum-size response tests remain. |

Confirmed by source inspection and local tests: actor/guild IDs originate from checked interactions; permissions are checked again in the service; revisions are never silently refreshed; replay uses the same interaction ID; replies explicitly disable allowed mentions; configuration work uses the existing bounded worker after private defer; no provider I/O occurs in transactions. No autocomplete/components were added, and the explicit factory remains outside automatic command discovery. Existing schema checksums, persisted IDs and Main are unchanged.

Focused audit tests passed: 38 tests, zero failures/errors/skips, .tools/phase8-audit-tests.log. Subsequent full build including phase-9 intake passed 255 tests and packaged smoke; .tools/phase9-intake-verify.log and .tools/phase9-intake-smoke.log. The full suite includes three new command audit regressions and eight intake tests.

Remaining limits: explicit composition still requires approved policy inputs and installed/verified storage; there is no user-facing durable receipt lookup after uncertainty. Real Mongo races/commit uncertainty and live Discord permissions/replies remain release gates. This audit is not a dependency security certification or proof of production capacity. Phase 9 is started, not complete; see [discovery notes](ALERT_DISCOVERY.md).

## Follow-up audit — 2026-09-19 (AUD-04)

This is the latest increment. The initial audit below remains historical. Scope: asynchronous send handoff after authorization, application exception logging, and duplicated timer autocomplete. Phase 8 commands remain unimplemented.

| Finding | Change and evidence |
| --- | --- |
| High: authorized work could start transport after a recorded outcome, or during another permit's persistence failure | SendAdmission now exposes a locked transport check covering receipt, stop/release, shutdown and failure pause. ScheduledDelivery calls it at submission and serializes its receipt callback with submission. Tests verify a committed receipt blocks a late send, retains capacity until transport-stop proof, and another failed receipt pauses/resumes eligible work. |
| High: arbitrary exception messages/causes could disclose connection details or payloads in application logs | FailureDiagnostics emits bounded exception types and code locations, omitting messages, file/module metadata, suppressed payloads and Throwable.toString(). BunnyLog, ErrorReporter and InteractionErrors use it; connection, autocomplete and command registration failures no longer append arbitrary exception messages. Logging-backend tests verify no raw throwable is attached and synthetic secrets are absent; cyclic causes and oversized locations remain bounded. |
| Optimization: Start and SwitchSubject duplicated reads and formatted the entire subject record before filtering | Shared SubjectAutocomplete preserves per-user queries and the handler access gate. It filters valid/matching choices, deduplicates, then stops at 25. Tests verify late matches survive filtering, malformed rows do not hide valid choices, and subjects after 25 matches are not formatted. |

FailureDiagnostics retains at most four causes and eight locations per cause, truncating location strings and removing controls. This trades detailed exception text for safer default diagnostics. It is not a universal secret scrubber: caller-authored message/context strings, manually fabricated stack metadata and third-party logging remain outside its guarantee. Restricted log access/retention and wider review remain release work. No credentials were read or rotated.

The transport check is an admission boundary, not cancellation of already submitted work. Real transport must still honor deadlines, uncertainty and stop-before-start deployment. Receipt and transport-stop proofs remain necessary to free capacity. No schema, identity, migration checksum, activation or live restart changed.

Autocomplete now bounds result formatting/retention after matching, but still reads TimerData and may inspect all subjects when few match. No latency percentage or launch-capacity claim is made. Registrations and the separate bounded autocomplete executor remain unchanged.

Verification logs: `.tools/audit-followup-verify.log` and `.tools/audit-followup-smoke.log`; final totals are in [PROGRESS_REPORT.md](PROGRESS_REPORT.md).

## Result and scope

AUD-02 was brought forward by the user's explicit request, before phase 8. This is a source and local regression audit of the existing implementation, not a production certification. Phase 8 server setup commands do not exist yet and cannot be audited as an integrated feature. See [PROGRESS_REPORT.md](PROGRESS_REPORT.md) for delivery status and [IMPLEMENTATION_TODO.md](IMPLEMENTATION_TODO.md) for the work queue.

The pass inventoried all 135 production Java files and searched production sources for blocking calls, exception-message exposure, executor construction, mention settings, database writes and utility duplication. Detailed reading concentrated on these boundaries:

| Area | Inspected responsibilities |
| --- | --- |
| Handler | InteractionListener access/dispatch, ButtonRouter ownership of cooldown reservations, InteractionExecutor admission and worker lifecycle, Main composition |
| Existing bot | Start/Gpa command callbacks, GPA/pending-menu storage and expiry, semester confirmation and TimerAccountService archival, DB optimistic writes and account/timer transactions |
| Alert application | ConfigurationService authorization-before-read, JdaConfigurationAccess cache-only permission checks, configuration transaction/projections, automatic authorization deadlines |
| Alert persistence | Fan-out paging/deduplication/backlog, delivery claim/recovery/receipt transitions, observation source fence, explicit binary identity comparisons |
| Alert runtime | SendAdmission permit lifecycle, ScheduledDelivery authorization/transport handoff, DeliveryScheduler budgets and ownership checks |
| Maintainability/security | Source sizes and utility call sites, safe user errors versus internal logs, Maven resource exclusions, pinned dependencies and selected official advisories |

This was risk-focused reading supported by repository-wide searches and the full local suite. It was not a line-by-line proof of every class, an automated clone analysis, a penetration test or a full dependency vulnerability scan. No live database, token, provider or Discord transport was used.

## Findings fixed

Severity describes potential impact under the stated conditions; it is not a CVSS score.

### AUD-02-01 — High: reserved work bypassed the persistence pause

If worker B reserved a channel before worker A reported a receipt-storage failure, B could still call `protectAttempt()` and start authorization. The guard checked shutdown and permit state but omitted the global persistence-failure count.

`SendAdmission.Permit.protectAttempt()` now checks the same failure pause as reservation. Rejected authorization entry leaves the reservation cancellable; already protected external work remains retained until both completion proofs arrive. `SendAdmissionTest.receiptFailureAlsoStopsPreviouslyReservedAuthorizations` reproduces the ordering, verifies rejection, resumes after a known receipt, and checks retained capacity.

This does not retract requests already submitted to Discord. Transport integration must still honor deadlines, ownership and outcome uncertainty.

### AUD-02-02 — Medium: unbounded retained interactive menus

Expiry limited lifetime but not global entry count. Many users could retain arbitrarily many pending study menus and GPA pages/timer tasks. Failed GPA sends also kept their entries until timeout.

PendingSessionManager now admits at most 1,024 entries, retaining one pending menu per user and allowing that user's replacement at capacity. Admission is atomic under the existing manager lock and rejects shutdown. It returns a result to Start's asynchronous callback, which clears unusable controls and displays a busy response.

GPAPaginator now caps total entries at 1,024, entries per user at two, and retained pages per menu at 100. These are conservative technical safeguards, not measured capacity commitments. A named, idempotent discard operation cancels the timeout and frees pages immediately when sending fails. PendingSessionManager is final with a private constructor, reflecting its static lifecycle ownership.

MenuCapacityTest fills both registries, verifies global/per-user/page rejection, confirms owner replacement remains possible and verifies capacity reclamation/idempotent discard. Existing stale-menu ownership coverage remains. These tests do not simulate ten minutes of expiry under live REST load; timeout/callback stress testing remains open. Per-menu task counts are bounded, but outgoing REST callbacks remain separately unbounded by these registries.

### AUD-02-03 — Medium: inconsistent database identity comparisons

Several older queue and fan-out operations inherited collection collation while newer reads and indexes explicitly used simple collation. On a collection with case-insensitive defaults, a case-distinct event/token/record identity could compare equal. In particular, fan-out deduplication could classify a different event as already delivered. Nonmatching query/index collation can also prevent expected index use.

DeliveryQueue, FanoutRepository, ObservationRepository and the skipped-delivery backlog write now explicitly select simple collation for the affected reads, claims, source/lease fences, receipt updates, counter writes and replacements. Existing configuration/ownership adapters already selected it. Tests now match the actual options on queue/fan-out/catalog/authorization writes and verify claim options. No BSON fields, identity encoding, index definitions or migration checksums changed.

A real Mongo replica-set test with case-distinct identifiers and a non-simple collection default is still required; mocks establish options and transaction boundaries, not server behavior or index plans.

### AUD-02-04 — High: semester confirmation could archive a changed record

Timers validated the archive phrase against its cached TimerData, then TimerAccountService independently loaded TimerData again. A concurrent writer could replace/change the semester between those reads. The later transaction protected the second read's revision, but did not bind it to the record whose confirmation had been checked.

The archive service now requires the confirmed revision. It rejects a changed revision before modifying rewards, semester data or history. Timers passes the revision of the validated snapshot; the existing DB transaction continues to guard subsequent concurrent writes. The regression verifies a mismatched revision leaves the semester intact, performs no archive write and emits no notification.

This is an internal Java API change to `endSemester`; all repository callers were updated. BSON names and transaction semantics are preserved. It does not introduce durable, one-use modal identities; that remains a possible future hardening of destructive UI flows.

## Architecture, duplication and optimization assessment

- No production Java file reaches 1,000 lines. The largest are InfoEmbeds (576), Utils (555) and TimerSessionService (503). Counts include comments and blank lines.
- Alert domain, application ports, Mongo/Discord adapters and runtime ownership remain separate. Final immutable values and injected repositories/services provide clear object boundaries. Existing static timer services remain legacy structure; the project is not uniformly instance-based OOP.
- Existing StableIdentity and MongoIndexVerifier already centralize important duplicated behavior. Repeated Mongo options at call sites express required query behavior; hiding transaction boundaries in a large generic repository would make review harder.
- Utils still combines time formatting, collection/string helpers, reflection and Discord helpers. Its recursive nullification APIs have no external production caller in this repository and are not suitable for arbitrary cyclic objects. Extract or remove them in a dedicated API cleanup, with compatibility coverage. They are not on the current command path.
- InfoEmbeds is large but cohesive presentation code. TimerSessionService mixes transitions with recap/event preparation; a future extraction should preserve the exact transaction and notification ordering. File size alone is not a reason to introduce additional indirection.
- `DB.findMany` remains an unbounded general-purpose API with no production call site found. Future callers should use explicit bounded queries, not this helper for alert queues.
- Menu limits and matching query collation address concrete resource risks. No throughput or memory improvement percentage is claimed without measurement. GPA per-owner admission scans at most 1,024 entries; no additional unsynchronized owner counter was introduced.

## Open risks and release gates

| Priority | Remaining work | Required evidence |
| --- | --- | --- |
| Release blocker | Real Mongo transactions, recovery, failover, migrations and query plans | Isolated replica-set crash/race tests, including collation cases; phase 6 remains deferred |
| Release blocker | No composed alert transport/executor lifecycle yet | Known authorization before send, bounded REST work/receipts, cancellation proof, exact allowed mentions, shutdown and uncertain-outcome recovery |
| High | Secret/log handling is not centrally sanitized | Audit ErrorReporter/InteractionErrors throwable logging and exception-message logging in MongoManager/autocomplete/registry; test redaction and restrict log access/retention |
| High | No full transitive dependency/advisory certification | Inventory resolved runtime artifacts and compare against a current advisory database; include native/transitive components |
| Medium | Menu callback/expiry/shutdown stress and UX | Deterministic expiry/race tests plus Discord callback failures; page cap currently yields a safe size error |
| Medium | Destructive modal identity | Evaluate durable one-use confirmation bound to semester identity; current owner, expiry, phrase and revision checks remain mandatory |
| Medium | Legacy utility/service responsibilities | Focused extraction with caller compatibility and timer transaction tests |
| Release blocker | Capacity, operators, backups and rollout | Agreed SLOs; load/overload, pause/repair, restore drill, restricted credentials and explicit activation authorization |

Arbitrary exceptions remain hidden from ordinary user replies, but full internal exceptions can contain sensitive context. This is an outstanding hardening item, not evidence that credentials were observed in logs. No environment secret was read during this audit.

## Limited dependency check

The build pins JDA 6.6.0, Mongo sync driver 5.11.0, Logback 1.6.3 and other dependencies in pom.xml. The official Mongo advisory GHSA-r7rp-27cm-h3qf affects versions before 5.9.2; the pinned 5.11.0 is outside that range. [Mongo advisory](https://github.com/mongodb/mongo-java-driver/security/advisories/GHSA-r7rp-27cm-h3qf).

The official Logback notes list the MDC path-segment fix in 1.6.3, the pinned version. The local logging configuration uses ConsoleAppender, not SiftingAppender. [Logback release notes](https://logback.qos.ch/news.html).

The JDA advisory page could not be fetched in this pass. These selective checks do not establish that all dependencies are vulnerability-free, and no dependency versions were changed. Environment files remain excluded by Maven resource configuration; the packaged smoke check verifies the built artifact separately.

## Verification

Final build and packaged smoke evidence is recorded in [PROGRESS_REPORT.md](PROGRESS_REPORT.md). Local tests use mocked Mongo/Discord boundaries. No deployment, database migration, alert runtime activation or bot restart was performed.



## Phase 8 availability — 2026-09-21

The earlier statements that phase 8 did not exist describe the historical audit scope. It is now implemented but inactive; see [command notes](ALERT_COMMANDS.md). Local command regressions and full build passed (244 tests), as did packaged smoke. AUD-03 remains a separate follow-up review, and real Mongo/Discord certification is still outstanding.


## Phase 9 polling review — 2026-09-21

NEXT-07 uses a single source record for source ownership and shadow schedule guards, so begin/finish can condition on token, generation, current database-time expiry, format and attempt without HTTP inside a database transaction. Unknown begin/finish results do not grant fetch/release permission; completed summaries are not certified ingestion. Provisioning updates only missing poll-format state, preserving existing schedules/pauses and ownership generations. A follow-up review caught and fixed the classification of malformed batches as partial rather than failed polls. Real Mongo execution/crash/race evidence remains deferred phase 6.

A read-only application probe and attributed successful feed fixture were obtained. Empty/error/recurrence/timezone/market/entitlement certification remains unresolved. No migrations, background polling or alert activation occurred. See ALERT_DISCOVERY.md for DISC-02, migration and rollback requirements and explicit evidence limits.

