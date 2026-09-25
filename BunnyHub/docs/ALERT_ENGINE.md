# Alert engine foundation

Latest increment — 2026-09-23 (NEXT-08): added inactive AlertDisplayContent and AlertMessageRenderer. Preparation binds display content to the current eligible offer revision, bounds/escapes text, preserves attribution, restricts exact role mentions and freezes canonical JSON/nonce/SHA-256 for existing send authorization. Nine serialized-payload/authorization regressions; JDK 21 clean verify **310 tests passed**, packaged smoke passed (.tools/render-verify.log, .tools/render-smoke.log). See [rendering contract and next dependencies](ALERT_RENDERING.md). No content persistence/migration, certified provider mapping, real Discord transport, Main wiring or activation added. VERIFY-01 database/load evidence remains valid within its documented scope.

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Implemented 2026-09-13 following the user's request to start the system classes/engine after understanding the handler. This is the first executable foundation of [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md), including its source/store and audience-plan refinements. It is not an activated alert service.

The next authorized increment adds [storage mappings, explicit schema setup, transactional fan-out and queue operations](ALERT_STORAGE.md). The tables below describe the original foundation; the storage document records the newer adapter scope and remaining integration gates.

## Handler integration findings

The implementation review traced `Main`, `BunnyHub`, `InteractionExecutor`, `CommandLoader`, `CommandGate`, `InteractionListener`, and `MongoManager`, alongside the handler documentation.

- Main is the composition root. Command discovery constructs named entry points with a BunnyHub argument; alert classes are outside the scanned command/event/component packages.
- Slash/context commands pass access and cooldown checks, optionally defer acknowledgement, then enter the bounded per-user command executor. Autocomplete uses its separate executor and access checks without consuming execution cooldowns.
- Per-user serialization ends when the synchronous body returns. Discord callbacks can outlive that body, so the command executor cannot represent alert send capacity.
- Shutdown first stops/drains interactive admission, then runs feature hooks, then closes JDA and Mongo. Feature cleanup belongs in Main via `onShutdown`; no alert imports were added to the handler. A future runtime should signal alert admission to stop at shutdown initiation and drain through its hook; the current hook timing alone does not stop alerts during the interactive drain.
- Mongo requires transactions and finite driver budgets. Existing static DB access remains for existing features. New alert repository ports use explicit dependencies and do not reuse `DB.findMany` or timer collections.

## Implemented components

| Component | Executable responsibility |
| --- | --- |
| `domain.AlertIdentity` | Bounded structured source/store/market/campaign keys; typed free and digest identities; destination incarnation and event/channel delivery identity |
| `domain.Offer` | Immutable normalized eligibility evidence, exact minor-unit prices, dates and content revision |
| `domain.FreeGamePolicy` | Eligible/ineligible/unknown/scheduled/expired/stale decisions; explicit freshness budget and unknown-end policy; exclusive validity deadline |
| `domain.Subscription` | Automatic activation cutoff, current enabled flags, store/market/topic matching, destination ownership/incarnation, immutable role IDs |
| `domain.DeliveryJob` | Immutable lease and attempt transitions, revision/generation fencing, safe recovery, matching receipt reconciliation and validated storage-neutral snapshots |
| `domain.RetryPolicy` | Caller-configured attempt/age/deadline bounds, exponential full jitter and preservation of longer remote backoff |
| `application.AutomaticSendEngine` | Pure current-snapshot authorization; blocks stale rendering/evidence and missing permissions; skips invalid configuration/expired offers; freezes approved attempt metadata |
| `application.DeliveryRepository` | Contract for atomic claims, guard-touch authorization and conditional transition/receipt writes; no production adapter yet |
| `application.ObservationRepository` | Contract for source-lease-guarded offer/event/automatic-plan commits and canonical deduplication; no production adapter yet |
| `runtime.SendAdmission` | Global and per-channel in-flight bounds, no waiting queue; receipt-failure pause; owner-specific release only after transport termination and known receipt commit |

The original foundation components use JDK types and explicit dependencies. The later Mongo adapter package uses the existing MongoDB driver. Construction creates no worker or scheduled task; Main does not construct or activate these components.

## Contracts and reasoning

The foundation preserves the blueprint's invariants; it does not replace its architecture or require a migration. The source companion's refinements are applied from the start. Origin/source metadata cannot change a free notification key; a returning campaign or another market/store does. Automatic and manual audience plans remain separate from event/channel uniqueness. Digest identity is represented, but deal selection and manual publishing workflows are not implemented.

Delivery mutation is a proposal, not a durable write. Every transition increments revision; claims increment generation. A repository must atomically compare the old revision/state/token/generation using database time. Two local copies may both compute a transition; only one conditional database write may commit. Domain tests cannot establish that Mongo implementation guarantee.

Crash reasoning follows the external side-effect boundary: an expired LEASED job has no authorized attempt and can return to READY; an expired SENDING job may already have reached Discord and becomes UNCERTAIN. UNCERTAIN has no ordinary retry/claim path. A matching receipt may reconcile it to SENT through a future privileged, audited repository operation. Safe negative reconciliation, audited redrive and operator closure remain unimplemented. Ordinary completion rejects expired ownership even for a successful late callback; reconciliation handles that evidence.

The automatic authorization engine must run inside a transaction that loads the job's event and current offer/configuration, touches guild/destination/subscription guards, and inserts the attempt with the state change. Discord permission validation happens outside the transaction; the boolean snapshot is trusted adapter input, not independent proof. Permission changes after validation remain a transport outcome. Payload hash/revisions/roles must correspond to the exact serialized payload; a changed role set or content/subscription revision requests a new rendering. The renderer and actual allowed-mentions payload are not implemented yet.

SendAdmission independently bounds local asynchronous work. Reserve a permit before claiming, protect it before entering send authorization, and retain it if commit status becomes unknown. `authorizationRejected` is valid only with proof that no attempt committed and no transport was invoked. After a committed attempt, release requires both known receipt persistence and confirmed transport termination. Future cancellation alone is not termination proof. A late callback owns its original permit and cannot remove a newer channel reservation. `close()` stops admission without clearing unresolved permits or waiting indefinitely. Runtime ownership, pacing, fairness, transport deadlines and bounded receipt retries are still adapter/runtime responsibilities.

No product defaults were silently approved. Policy freshness, retry and concurrency budgets require explicit constructor inputs. Unknown end times can be accepted only through an explicit policy with fresh evidence. The 100-role technical bound caps an individual collection; it does not select the proposed five-role product limit. Domain proofs describe normalized evidence supplied by a certified source; no source is certified by this implementation.

## Verification and remaining release gates

Tests cover identity changes, exact prices, missing/rejected evidence, date/freshness boundaries, source-independent identity, role immutability, tenant/incarnation/topic matching, stale owners, expired leases, retries, unknown outcomes, persisted-state recovery, changed configuration/rendering, and concurrent admission. Tests have no live Discord/Mongo boundary.

Verified: **108 tests passed, including 33 new alert-engine tests**, Maven verify succeeded, and the packaged-artifact smoke check passed. The new packages import no JDA, Mongo, handler or timer classes, and Main/handler contain no alert activation code.

The Maven wrapper failed before starting Maven with `Cannot index into a null array`; use the documented local Maven installation. Build log: `.tools/alert-engine-verify.log`. Packaged artifact check: `.tools/alert-engine-smoke.log`. Output: `target-upgrade/BunnyHub-4.00.jar`. The live bot was not restarted.

Before activation, implement and verify: schema codecs/migrations/indexes and real replica-set crash/race tests; bounded resumable fan-out and source certification; configuration commands/audit; actual rendering and allowed-mentions tests; transport uncertainty/deadline behavior; ownership/pacing/fairness/shutdown composition; monitoring, reconciliation, and load/canary gates. The 1,000-server target has not been capacity-validated by these tests.


