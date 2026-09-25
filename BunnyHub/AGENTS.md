# BunnyNexus project guidance

**Current free-game system — 2026-09-25 (FREEBIES v2):** the user approved replacing the unwired alert framework with a simple, active pipeline in `bunnynexus/freebies`. It works like this: GamerPower discovery → owner review in `FREEBIE_REVIEW_CHANNEL_ID` → one confirmed approval → per-channel durable sends with nonce and history-checked retries → a summary to the owner and a notice to the owner of any server that failed. It is wired into `Main` and `/freebie`, and starts only when the .env keys are set. **Read [docs/FREEBIES.md](docs/FREEBIES.md) first.** The older `bunnynexus/alerts` and `bunnynexus/commands/alerts` code, and the dated increment notes below, describe that inactive framework; they are historical, not the runtime path. Verified: 343 default tests, 12 real-MongoDB `FreebieRepositoryIT` tests, packaged smoke. Owner controls (launcher picker, progress, `/freebie-admin`, health alerts) and late-subscriber catch-up added.

Latest increment — 2026-09-25 (NEXT-10 / OWNER-01): owner-only prepare → verify → explicit bounded release is now persisted, audited and enforced transactionally at send authorization. Verification alone cannot publish; releases expire within one hour, count retries, bind reviewed content/template and can be revoked. **334 default tests, 16 real isolated three-node MongoDB tests and packaged smoke passed** (.tools/next10-verify.log, .tools/next10-mongo-final.log, .tools/next10-smoke.log). New additive owner-review migration tested only in disposable databases; old send writers must stop before upgrade. Private owner commands, certified ingestion, pre-fan-out gate and transport/composition remain pending. No live migration, bot restart or Discord send. See [owner-controlled publication](docs/ALERT_OWNER_REVIEW.md).

Latest increment — 2026-09-25 (NEXT-09): user delegated launch review/command/offline design; [ALERT_OPERATIONS.md](docs/ALERT_OPERATIONS.md) is the authoritative addendum. Two real bounded GamerPower probes before/after rollover matched Epic's official free product listings (latest: Mechabellum/Astrea); timezone/market certification remains open. Added inactive PublicationReviewPolicy and explicit GamerPowerPollingLoop; fixed AUD-07 cross-offer/subscription payload binding and nonce/docs contradictions. **324 tests passed**, packaged smoke and repaired audit probe passed (.tools/next09-*.log). No review persistence/transactional gate, certified ingestion, real transport, Main wiring, migrations or broadcasts activated. Next: durable content/evidence/review storage and mandatory transactional review guards before operator commands/activation.

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](docs/ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Latest verification — 2026-09-23 (VERIFY-01): phase-6 database verification resumed by user. **12 real three-node MongoDB integration tests passed** (claims/polls/configuration, authorization/receipt contention, fan-out replay, uncertain recovery and primary crash); **two synthetic 6,000/20,000-job load scenarios passed**. Genuine GamerPower 201/404/200 fixtures captured; exact documented 201 no-results envelope now accepted with strict validation. Final JDK 21 clean verify **301 tests passed**, packaged smoke passed; test processes stopped. See [verification scope, measurements and remaining gates](docs/ALERT_VERIFICATION.md). Logs: .tools/mongo-replica-final.log, .tools/delivery-load.log, .tools/verification-final.log, .tools/verification-smoke.log. Schemas installed only in disposable local test databases; no live GBF access, bot restart, recurring polling or Discord send. Source eligibility and real transport/load certification remain incomplete.

Latest audit — 2026-09-22 (AUD-06): reviewed alert input/access, transport, polling, ownership and persistence boundaries, then fixed timer clock-rollback bugs: negative break durations, repeated subject-time credit/inconsistent aggregate totals, and incorrect paused telemetry. Four regressions added; three accounting regressions reproduced failures before the fix. JDK 21 clean verify **293 tests passed**, packaged smoke passed (.tools/audit6-verify.log and .tools/audit6-smoke.log). See docs/AUDIT_REPORT.md (AUD-06) for scope and limits. No schema/checksum changes, live requests, restart or activation. Real database verification, source certification and transport/load validation remain release gates.

Latest audit — 2026-09-21 (AUD-05): fixed fetch admission after slow pressure checks, duplicate identity detection involving malformed source rows, and provisioning that could reset unversioned polling state. Intake now retains only required fields. Five regressions added; JDK 21 clean verify **289 tests passed**, packaged smoke passed (.tools/audit5-verify.log and .tools/audit5-smoke.log). Focused phase-8/9 and ownership/storage audit; detailed scope and open risks in AUDIT_REPORT.md. No schema/checksum changes, live requests, restart or activation. Real database verification and source certification remain release gates.


Latest increment — 2026-09-21 (NEXT-07): inactive durable shadow polling now has source-lease/attempt fences, database-time due scheduling, bounded summaries, backoff and persisted known pauses. One application GamerPower probe returned HTTP 200 (20 candidates, nine unknown ends); an attributed raw success fixture is covered by regression tests. **284 tests passed**, packaged smoke passed; .tools/phase9-poll-verify.log and .tools/phase9-poll-smoke.log. Source eligibility/identity certification, certified ingestion and deferred phase 6 remain open. No migration, recurring polling, bot restart or alert publication was activated. See DISC-02 in ALERT_DISCOVERY.md.


Latest increment — 2026-09-21 (NEXT-06): inactive GamerPower HTTP fetch layer implemented with explicit public address pins, fixed HTTPS endpoint, finite deadlines, streamed byte limits, one-call admission, pacing/backoff, rate-limit pause and shutdown cancellation. Ten new transport tests include real loopback stalled-body timeout. Full JDK 21 clean verify: **271 tests passed**, packaged smoke passed; logs .tools/phase9-fetch-verify.log and .tools/phase9-fetch-smoke.log. See [discovery notes](docs/ALERT_DISCOVERY.md). Provider certification, durable polling and phase-6 real database verification remain outstanding; nothing activated.


Latest handoff — 2026-09-21: AUD-03 phase-8 audit completed with fixes to input/storage error classification, required boolean parsing and selective status rendering. Phase 9 has started with inactive, bounded GamerPower candidate intake; [discovery notes](docs/ALERT_DISCOVERY.md). Full JDK 21 clean verify: **255 tests passed**, packaged smoke passed. Logs: .tools/phase8-audit-tests.log, .tools/phase9-intake-verify.log and .tools/phase9-intake-smoke.log. No migration, bot restart or alert activation. Phase 6 remains deferred and mandatory before activation.


Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](docs/PROJECT_STATUS.md), [progress report](docs/PROGRESS_REPORT.md) and [audit report](docs/AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](docs/ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

## Product direction and current scope

The main planned selling point is automatic free-game alerts across Discord servers. A server signs up, chooses one or more destination channels and roles to ping, and chooses providers such as Epic Games, Steam, GOG, and others. Discovery should run centrally and a discovered eligible offer should reach all matching subscribed destinations.

The user specified a launch planning target of **about 1,000 servers** on 2026-09-12. Channel counts, offer bursts, hosting resources, and a delivery-latency target are not yet agreed.

On 2026-09-13 the user authorized starting the system classes/engine, superseding the earlier design-only restriction for that work. The initial domain/application foundation and local send admission guard are now implemented; see [docs/ALERT_ENGINE.md](docs/ALERT_ENGINE.md). Provider polling, subscription commands, alert collections, background workers and live notifications are not activated. Product defaults remain proposals, and this engine request does not authorize live broadcasts or provisioning. Read [docs/FREE_GAME_ALERTS.md](docs/FREE_GAME_ALERTS.md) for the proposed design and open decisions.

The same day, the user requested continued base-system implementation. [docs/ALERT_STORAGE.md](docs/ALERT_STORAGE.md) records the added Mongo codecs, explicit schema installer, transactional fan-out and delivery queue operations. These adapters remain inactive; no schema was applied. Real replica-set crash/race tests are still required. Do not mistake repository-boundary mocks for durable recovery evidence.

The subsequent increment implements those catalog/outbox and send-authorization transactions; see [docs/ALERT_PIPELINE.md](docs/ALERT_PIPELINE.md) for the current scope. Observation commits require expected content revision and certified source ordering. Send authorization uses bounded permission evidence and touches current configuration/offer guards. The additive catalog migration and counted outbox plans require stop-before-start upgrades; do not mix older alert writers or reset counters. Source/runtime ownership, schedulers, provider/transport adapters and real database integration verification remain incomplete; the later configuration increment is recorded below. No alert runtime has been activated.

## Architecture blueprint

The user subsequently deferred phase-6 real database integration tests and authorized phase 7 (runtime ownership/scheduling). [docs/ALERT_RUNTIME.md](docs/ALERT_RUNTIME.md) records the inactive lease repositories/controller, mandatory runtime fence on send authorization, bounded fair scheduler, recovery ticks and explicit runtime migration. AuthorizationRequest now requires the runtime lease; old unfenced writers must not overlap. Stop-before-start deployment remains mandatory because database fencing cannot retract an external Discord request. Phase 6 is deferred, not waived; executor/transport composition and activation remain separate work.

On 2026-09-14 the user authorized the next configuration stage. [docs/ALERT_CONFIGURATION.md](docs/ALERT_CONFIGURATION.md) records the implemented immutable configuration policy/service, cache-only JDA access adapter, atomic Mongo aggregate/projections/audit/tombstones and explicit additive migration. All remain inactive. Configuration edits must use this shared guild-revision protocol; do not write projections independently. Derive actor/guild IDs from verified interactions. Do not mix old configuration writers or auto-import seeded records. Commands, runtime ownership and real replica-set verification remain separate work.

Read [docs/SYSTEM_ARCHITECTURE.md](docs/SYSTEM_ARCHITECTURE.md) before implementing the alert product. It is the primary design blueprint and refines the earlier alert plan. Preserve its identity, ownership, durability, bounded-resource and uncertain-outcome invariants; changes require a documented architecture decision and migration/failure tests. Numerical budgets and launch product defaults are proposals to confirm, not already approved commitments. The blueprint does not authorize implementation.

## Discovery, deals and manual publishing design

Read [docs/ALERT_SOURCES_AND_FEATURES.md](docs/ALERT_SOURCES_AND_FEATURES.md) with the architecture blueprint. It researches GamerPower, ITAD and alternatives, and designs opt-in deals digests and manual preview/confirmation/status flows. Source IDs differ from store IDs; manual and automatic sends share notification identity but use separate audience plans. The engine includes these identity distinctions; deals/manual workflows remain designs. Do not create API accounts or broadcast without authorization. Check source terms and coverage before activation.

## Existing architecture

- `src/main/java/org/bunnys/handler`: BunnyHub handler 2.0.0, evolved from the Beastars reference. Read [docs/BUNNYHUB_2.md](docs/BUNNYHUB_2.md) for lifecycle, routing and cooldown contracts.
- `src/main/java/org/bunnys/commands`: thin registration/delegation classes only.
- `src/main/java/org/bunnys/bunnynexus/commands`: named implementation classes for every command/subcommand. Keep new implementations here; avoid anonymous command bodies in the registration directory.
- `bunnynexus/timers/services`: existing study-session and academic-record logic. Preserve it while preparing the planned alert product.
- Avatar has slash/mention entry points and a USER context menu, `Apps → Avatar`. Info has user/server branches. Timer has ten named subcommands including subject removal, editing and `end-session` (recovery when a session menu is lost).
- Java 21, JDA 6.6.0, MongoDB synchronous driver. Existing database name is `GBF`; environment keys are `TOKEN` and `MongoURI`.

## Handler 2.0 conventions

- Register feature cleanup in Main with onShutdown(name, action); do not import feature packages from the handler. Cleanup hooks must finish promptly.
- Configure command metadata before registration. Duplicate command names/context names fail startup; registry getters return immutable snapshots.
- Keep cooldown reservations with their invocation and release only that reservation when work cannot be admitted. Never invalidate another invocation's cooldown by user/key.
- shutdown()/close() close the client without terminating the JVM. The handler still assumes one bot per JVM.
- Build settings are snapshotted. Changing a builder after building does not reconfigure a client.

## Backend rules

- Autocomplete providers must pass CommandGate.checkAccess before reading data; authorization must not consume the execution cooldown.
- Client defaults allow eight unfinished command actions and two unfinished autocomplete actions per user; setPerUserCapacity configures these limits.
- Never perform blocking I/O on JDA gateway/callback threads. Use the bounded command executor for current synchronous command work and the separate autocomplete executor for autocomplete reads.
- `executeForUser` serializes the synchronous bodies of accepted actions per user. It does **not** serialize asynchronous Discord callbacks, create durable jobs, or bound pending Discord REST requests after a task returns.
- Do not use the interaction executor, static caches, or timer-menu schedulers as the future mass-notification queue. The alert design requires durable jobs, bounded dispatch, retries, explicit deduplication, and restart recovery.
- Respect Discord/JDA rate limits; do not disable them, multiply bot tokens to evade them, or treat larger queues as increased throughput.
- Preserve optimistic revision checks and atomic account/timer transactions. Mongo must support transactions. Do not change BSON field names or collection names during package refactoring.
- The current default database budget is 10 seconds per driver operation, with finite connection/selection/checkout waits. Do not restore unlimited waits as a performance workaround; tune measured budgets explicitly.
- Metrics are accessible through `BunnyHub.getCommandWorkload()` and `getAutocompleteWorkload()`. See [docs/BACKEND_REVIEW.md](docs/BACKEND_REVIEW.md) for their meaning and remaining limitations.
- Keep allowed mentions narrow. Future role pings must whitelist exactly the configured destination roles, not enable all role/everyone mentions globally.

## Verification and continuity

Read [docs/IMPLEMENTATION_TODO.md](docs/IMPLEMENTATION_TODO.md) before picking up implementation work. It records the end-to-end flow, completed foundations, dependencies and multi-agent file ownership protocol. [docs/CODE_AUDIT.md](docs/CODE_AUDIT.md) records the latest audit scope, fixes and unresolved release gates. Update the checklist with files and verification evidence at handoff; do not treat unchecked proposals as already implemented.

Use the shared StableIdentity encoding for catalog/fan-out IDs and order-sensitive MongoIndexVerifier for alert index verification. Do not change persisted identity or migration checksums during cosmetic refactors. Timer reports live in TimerEmbeds; retain transaction/session rules in their services. Only explicitly authored InteractionErrors.InputFailure/StateFailure messages may be shown to users; arbitrary exception messages are internal.

Use JDK 21 and `mvnw.cmd clean verify`. This workspace also has a local Maven runtime and offline cache:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.10'
& ./.tools/apache-maven-3.9.16/bin/mvn.cmd '-Dmaven.repo.local=C:/BunnyHub/.tools/repository' '-Dbunny.build.directory=target-upgrade' -o -B -ntp verify
```

Build `target-upgrade/BunnyHub-4.00.jar` when the normal artifact is locked. Do not restart the live bot just to verify a code change. Tests use mocked Discord/Mongo boundaries; they do not establish real delivery capacity. `.tools/ArtifactSmoke.java` checks the packaged JAR; `.tools/BackendLoadCheck.java` is a synthetic scheduler check, not a Discord benchmark.

Never print or package `.env` contents. Source configuration may exist in the root or `src/main/resources/.env`. BeastarsBot is a read-only reference unless the user separately requests changes there. Update these docs when the product scope, architecture, or measured capacity changes so future agents and chats working in this repository have the same context.







