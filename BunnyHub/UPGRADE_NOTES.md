# Upgrade validation — 2026-09-10

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](docs/PROJECT_STATUS.md), [progress report](docs/PROGRESS_REPORT.md) and [audit report](docs/AUDIT_REPORT.md). Phase 8 commands remain next; phase 6 remains deferred and required before activation. Earlier measurements below are historical.

## BunnyHub handler 2.0.0

Follow-up hardening: autocomplete now authorizes provider reads, per-user admission limits protect shared queue capacity, and opening the semester archive form no longer waits on MongoDB. That follow-up passed 75 tests; this is historical verification. See [the source review](docs/SECURITY_REVIEW.md) for findings and remaining limits.

The handler now has immutable startup settings and route snapshots, duplicate-route validation, ownership-safe monotonic cooldowns, shared component discovery, explicit stale-control replies, and configurable shutdown hooks. Bot version and artifact naming remain unchanged. See [the 2.0 migration notes](docs/BUNNYHUB_2.md) for API changes and limitations.


JDA was upgraded from 5.1.2 to 6.6.0 and all direct dependencies were checked against current Maven Central metadata. Stable Maven plugins and CI actions were also updated. See `README.md` for versions, configuration, and migration requirements.

## Verified

- Java 21 compilation and release packaging succeed.
- 64 regression tests cover the current release, including user/server information, USER context-menu routing, command structure, fair scheduling, and database timeout checks.
- The release JAR loads JDA command payloads, discovers all three commands using Reflections, initializes Logback through its service provider, and reads legacy MongoDB BSON values.
- The release manifest specifies the correct main class and multi-release support.
- The release contains no `.env` entries, JUnit classes, or Mockito classes.

Validated artifact: `target-upgrade/BunnyHub-4.00.jar`.

```powershell
.\mvnw.cmd -Dbunny.build.directory=target-upgrade clean verify
```

The normal `target/BunnyHub-4.00.jar` was locked by another process, so the updated release was built separately. The running process was not stopped or restarted. Earlier JARs are not sanitized by the new credential exclusions.

## Main corrections

Backend preparation for the planned 1,000-server free-game alert product: replaced hashed worker locks with bounded exact-key FIFO queues and fair ready-key scheduling, isolated autocomplete workers, added workload snapshots and forced-shutdown accounting, and set finite Mongo operation/connection/selection/checkout budgets. A synthetic 10,000-task / 1,000-key scheduler run passed. See `docs/BACKEND_REVIEW.md` for measurements and limits. Alert discovery, subscriptions, and delivery remain unimplemented by request; `AGENTS.md` and `docs/FREE_GAME_ALERTS.md` record the product direction and proposed architecture.

Avatar now also registers a USER context menu, **Apps → Avatar**, with private replies and selected-user targeting. Slash and menu invocations share permissions, cooldowns, bounded execution, and rendering. All command implementations are named classes under `org.bunnys.bunnynexus.commands`, called from thin registrations under `org.bunnys.commands`. Timer and info anonymous subcommands were removed; the domain namespace moved from `nexus` to `bunnynexus`.

Added Beastars's `/info user` and `/info server`, including mention aliases, profile/member details, server metadata, and BunnyNexus styling. Missing member details are fetched asynchronously. Long role/feature fields are bounded to Discord's limits, and the server branch handles DMs explicitly.

Handler migration completed on 2026-09-12 using `C:/Users/youss/Desktop/Work/BeastarsBot/src/main/java/org/bunnys` as the read-only reference:

- Imported the shared command contexts, aliases, groups, mention routing, component routers, cooldown caches, cache metrics, and builder configuration.
- Migrated timer and avatar entry points, preserving subject removal/editing and all previous timer data safeguards.
- Added avatar mention aliases `av` and `pfp`, with option validation; timer remains slash-only.
- Preserved early acknowledgement and per-user serialization while adopting the configurable bounded worker pool.
- Kept channel-specific permission checks, made command cooldown claims atomic, and separated same-named actions in different subcommand groups.
- Added nine focused regression tests for the new handler and retained the 34 existing tests.
- Adapted shared errors to BunnyNexus; Beastars domain/admin/emoji services were excluded. No files in BeastarsBot were edited.

Follow-up features completed and artifact rechecked on 2026-09-12:

- Removal autocomplete uses the selected destination and returns course codes, avoiding the previous full-label/field-length mismatch. Removal handles hyphens and stored whitespace; academic-record removal does not require a timer document.
- `/timer update-subject` edits code, name, credits, grade, or marks lost, with an option to clear the grade. Retake grades replace the existing grade for GPA calculations while preserving study counts and time. Duplicate codes and renaming during an active semester session are rejected.
- `/avatar` supports resolution selection up to 4096px, full-size display of both avatars, original/PNG download links, deferred member retrieval, and explicit global-avatar fallback.

The new command definitions are registered when the updated bot starts. This validation did not restart or deploy the bot.

- Migrated JDA buttons, action rows, component queries, command contexts, and modal labels.
- Moved blocking interaction work off the gateway thread; timer replies are acknowledged before database operations.
- Consolidated session mutations and semester archival into services and removed the unused duplicate display service.
- Added optimistic revision checks, atomic account/timer transactions, insert-only registration recovery, unique user indexes, and complete semester history.
- Corrected lifetime-time double counting, course-code splitting, subject-switch accounting, break-count displays, progression thresholds, cumulative semester XP, RP overflow, and UTC daily streaks.
- Bound session buttons to their message, fixed replacement/timeout races, and added scheduler cleanup.
- Fixed account-course autocomplete, input bounds, legacy nullable fields, startup failures, database cleanup, console EOF spinning, and command registry cleanup.
- Added reproducible build tooling, regression tests, a CI workflow, configuration documentation, and credential exclusions.

## Deployment limits

Tests use mocks at the database and Discord boundaries. Real cluster transactions, index creation, permissions, gateway events, modal submission, and Discord message edits need a deployment smoke test. MongoDB must be a replica set or sharded cluster; standalone mode is rejected. Existing duplicate user records block creation of the required unique indexes and are not modified automatically.

No existing history was rewritten, no live commands were deployed, and no Git commit was created because this directory has no Git metadata.
