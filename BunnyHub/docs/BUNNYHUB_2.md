# BunnyHub handler 2.0.0

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

The handler has its own version, `BunnyHub.HANDLER_VERSION` / `getHandlerVersion()`. The bot remains version 4.0.0 and the packaged file remains `BunnyHub-4.00.jar`. Startup prints both versions.

## Changes

- Startup uses an immutable `BunnyHubConfig` snapshot. Later changes to a builder cannot change an existing client's configuration. List getters are read-only.
- Command registration validates Discord definitions and rejects duplicate canonical names or USER context names before publishing anything. Readers use one immutable route snapshot. Canonical names still take precedence over aliases; conflicting convenience aliases are logged and ignored. Configure command objects before registering them; the objects themselves are not frozen.
- Command and event discovery is sorted and fails startup for broken concrete implementations. Buttons, selects and modals share `ComponentLoader`, which validates prefixes, rejects duplicates and publishes a complete immutable table. Abstract and anonymous classes are skipped.
- Command and component cooldowns share `CooldownStore`. Elapsed time uses a monotonic clock. Each accepted invocation owns its cancellation; a delayed acknowledgement failure or overload rejection cannot clear a newer invocation's cooldown. Button and select cooldowns have separate namespaces.
- Cooldowns accept zero through two hours. Unsupported durations are rejected instead of silently shortened. The bounded caches remain best-effort throttles: capacity eviction or process restart can discard a cooldown. They do not replace authorization or database concurrency checks.
- Stale slash subcommands cannot fall through to the parent action. Stale autocomplete branches return no choices. Unknown buttons and selects give an expired-control response.
- `BunnyHub` implements `AutoCloseable`. `shutdown()` is idempotent and no longer calls `System.exit`. Both executors stop accepting work before draining. Named cleanup stages isolate ordinary failures, and interrupted Discord shutdown forces connection cleanup.
- Feature cleanup is registered by `Main` through `onShutdown(name, action)`. The shared handler no longer imports BunnyNexus timer classes. Router tables are cleared; only BunnyHub event listeners are explicitly removed. Manual shutdown removes the JVM hook.

## Migration notes

Use `ComponentCooldowns.reserve(...)` when a component might need to return a cooldown after rejection. Keep the returned reservation and call its `release()` method. The unsafe release-by-prefix/user API was removed. `claim(...)` remains for callers that never cancel.

`getCommands()` now returns a snapshot, not a live view. Fetch it again after registrations change. Do not mutate registered command metadata. Shutdown hooks should be quick and bounded; arbitrary user hooks have no forced timeout. The handler still assumes one bot per JVM because database bindings and component registries are static.

The existing bounded per-user executor, separate autocomplete pool, database operation budgets, timer transactions and command class layout remain in use. This release does not implement provider discovery, subscriptions or alert delivery. The planning target remains about 1,000 servers; local tests do not establish production Discord delivery capacity. See [FREE_GAME_ALERTS.md](FREE_GAME_ALERTS.md) and [BACKEND_REVIEW.md](BACKEND_REVIEW.md).

## Verification

Regression coverage includes stale cooldown cancellation, rejected claims, duplicate command/context registration, unchanged snapshots, cleanup failure isolation, external listener preservation and obsolete subcommands. Build and packaged-artifact checks run without logging into Discord or restarting the live bot.

Verified on 2026-09-13: 71 tests passed, Maven verify succeeded, and the packaged-artifact smoke check passed. Logs: `.tools/handler2-verify.log` and `.tools/handler2-smoke.log`. Artifact: `target-upgrade/BunnyHub-4.00.jar`.


