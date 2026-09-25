# Backend readiness review — 2026-09-12

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Scope: prepare BunnyNexus for its planned 1,000-server free-game alert product. At the time of this review, the alert foundations had not been implemented; later increments are linked in the current status above.

## Findings and changes

| Finding | Action / status |
| --- | --- |
| A worker previously acquired one of 256 hashed user locks after leaving the queue. Several actions from one user could occupy multiple workers waiting for one lock; unrelated IDs could collide. | Replaced with exact-key FIFO lanes. Only a ready action takes a worker. Lanes with more work return to the end of the ready queue, so one backlog does not monopolize execution. Idle lanes are removed. |
| Autocomplete shared workers and user locks with mutations and slow commands. | Added a separate bounded pool: 4 workers and 32 additional admission slots by default. It reads existing data without occupying command workers. |
| Mongo settings had no explicit overall operation budget and could spend excessive time waiting during failures. | Default 10-second driver operation budget, selection/connect waits capped at 5 seconds, checkout wait capped at 2 seconds, finite socket read budget. `setDatabaseTimeout(Duration)` tunes this policy. Explicit application budgets override corresponding URI timeout options. |
| Queue pressure and abandoned shutdown work were not visible. | Added aggregate workload snapshots and forced-shutdown accounting. Queued futures are cancelled on forced shutdown. Graceful shutdown drains accepted work. |
| Future fan-out could enqueue thousands of asynchronous sends after command tasks finish. | Documented as an architectural risk. No alert sender was added; it will require durable jobs and a separate bound held until each REST request completes. |

`setCommandPool(24, 100)` now bounds **total accepted unfinished work** at 124, including actions queued behind the same key. The client also limits each user to eight unfinished command actions and two unfinished autocomplete actions by default. Admission is nonblocking and rejects excess work; it does not run the task on the gateway thread. Per-user quotas are configurable through setPerUserCapacity(commands, autocomplete); generic internal executor work remains subject to the global bound. The standalone executor constructors retain their original global-only behavior unless a per-user capacity is supplied. Future per-guild alert admission needs its own policy.

Workers start lazily and are finite platform threads. `executeForUser` preserves order of synchronous accepted tasks; asynchronous callbacks are outside that guarantee. The general `ExecutorService` accessor is bounded too. Explicit `shutdownNow` interrupts active tasks and returns/cancels queued tasks; interruption is cooperative, not proof all external side effects stopped.

The Mongo pool defaults to command workers + autocomplete workers + 8 (36 with current settings). Timeout values are fail-fast defaults, not measured production tuning. A multi-operation transaction or application-level retry sequence can take longer than a single operation budget. Large startup index builds may require a reviewed larger budget; indexes and duplicate-data protections remain intact.

## Observability available now

`BunnyHub.getCommandWorkload()` and `getAutocompleteWorkload()` return:

- capacity, active tasks, queued tasks, retained keys;
- accepted, completed, rejected, and abandoned task counts;
- uncaught task-failure count (errors already handled by command code are not included);
- average/max time spent waiting before execution and age of the oldest queued task.

These are in-process snapshots, not a hosted dashboard or durable historical metrics. Wire them into monitoring during deployment preparation. Future alert metrics are specified separately in `FREE_GAME_ALERTS.md`.

## Verification

Regression coverage includes same-user exclusion/FIFO, deliberately colliding string hashes, progress by another user while one user is blocked, round-robin service, queue overflow, exception recovery, graceful drain, forced cancellation, autocomplete routing isolation, and finite Mongo settings. Existing timer, subject editing, BSON, command, and context-menu tests remain part of the suite.

Synthetic scheduler check (`.tools/BackendLoadCheck.java`), local Windows/JDK 21 run:

- 10,000 tasks across 1,000 keys; simulated 1ms waits, no network/database.
- 24 workers, total admission capacity 124; producer retries on explicit overload rejection.
- Observed 2.588 seconds total, 207 rejected/retried submissions, 18.23ms average queue wait, 47.45ms maximum queue wait.
- No lost tasks, same-key overlap, ordering failures, queued work, or retained keys after drain.

Those figures describe one synthetic local run. They do **not** establish Discord send throughput, provider polling capacity, Mongo latency, heap behavior under real payloads, or an alert-delivery service level. Backpressure is intentional; “zero throttling” is not a valid goal when downstream services have finite capacity.

## Remaining limits / future work

- The durable engine, storage, configuration and ownership/scheduling foundations were subsequently implemented but remain inactive. Discovery, transport composition, production telemetry and integration certification remain pending.
- Mongo and both pools still share the same process/resources. Pool isolation reduces contention but does not isolate CPU, heap, database capacity, or Discord quotas.
- Autocomplete cannot defer Discord's response deadline. Isolation helps ordinary contention; it does not guarantee timely suggestions during database outages. Deadline-aware read budgets/latest-request handling need measurement if autocomplete is heavily used.
- `DB.findMany` materializes all matches and has no current callers; do not use it to enumerate future subscribers. Use indexed, bounded pages/cursors. Existing versioned saves and cross-document transactions protect current timer writes and must be preserved.
- Timer GPA/pending-menu maps expire but lack a global cardinality cap. Their synchronized sections perform short local work and enqueue REST calls; they were not converted into a general job system. Measure menu memory before increasing interactive scale.
- The current deployment still assumes one bot owner for in-memory menus. Multiple alert workers require explicit ownership, deduplication, and shared quota coordination; running more copies is not itself a scaling plan.

See [the product design](FREE_GAME_ALERTS.md) and root `AGENTS.md` for persistent project context and the current implementation and activation boundaries.


