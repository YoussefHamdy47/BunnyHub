# Follow-up source review — 2026-09-13

Latest follow-up (2026-09-19): [AUDIT_REPORT.md](AUDIT_REPORT.md) records AUD-04. Application exception logging now uses bounded type/location diagnostics instead of arbitrary messages and raw throwables; synthetic-secret tests cover all three logging entry points. Caller-authored context and third-party logs are not universally scrubbed. Complete dependency/runtime verification remains open. Earlier findings and counts below describe their original pass.

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

This pass reviewed interaction routing and permission checks, executor admission, timer confirmation handling, component ownership checks, and the database write paths. It is a source review with mocked regression tests, not a penetration test or a dependency vulnerability scan.

## Findings fixed

1. **Autocomplete bypassed command authorization.** A provider could read and return suggestions before developer, administrator, guild, channel permission, or age-restriction checks ran. Autocomplete now uses the same access gate as command execution, without claiming an execution cooldown. Unauthorized requests receive empty choices before the provider runs. Existing subject suggestions are scoped to the caller; this fix closes the framework-level gap for restricted providers.
2. **A single user could exhaust the client work budget.** Fair scheduling prevented worker lock contention but did not prevent one user from reserving all queue slots. The client now caps unfinished work per user at eight command actions and two autocomplete actions. Limits include running and waiting work, are configurable with setPerUserCapacity, and retain immediate overload rejection. Other users still share the global capacity; this is not a distributed abuse-prevention system. Standalone executor constructors preserve their previous behavior unless supplied a per-user capacity.
3. **Opening the archive form depended on MongoDB latency.** The form builder read account and semester records before it could acknowledge a button by opening the modal. It now builds locally, using the input bounds allowed by semester names. Submission still checks the current semester, confirmation phrase, expiry and owner, after acknowledgement; archive writes still use the existing transaction/revision guards.
4. **Autocomplete retained irrelevant distinct values while filtering.** Matching now precedes deduplication, reducing temporary set entries for large provider results. This does not bound the provider's source list itself.

## Limits and remaining work

- The synchronous executor does not bound Discord REST callbacks or pending HTTP requests once command bodies return. Future mass alerts still need the separate durable delivery architecture.
- GPA menus and pending-session registries still rely on expiry rather than a global size cap. They should be bounded before expanding their use substantially.
- Providers must continue to scope their database queries to the authorized user/server. The access gate cannot establish ownership of arbitrary provider data.
- No production Discord/Mongo load test or dependency advisory scan was performed in this pass. The 1,000-server planning target is not a measured capacity guarantee.
- No free-game discovery, subscription or delivery feature was implemented, and the live bot was not restarted.

All 75 tests passed and Maven verify succeeded. Build log: .tools/security-review-verify.log. Packaged-artifact check: .tools/security-review-smoke.log.


