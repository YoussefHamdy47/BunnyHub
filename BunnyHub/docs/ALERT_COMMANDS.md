# Phase 8: server configuration commands

NEXT-10 — 2026-09-25: global publication is reserved for the single configured bot owner, with separate verify and capped release actions. The durable service/send gate now exists; private owner review commands are still pending. Guild administrators retain only their subscription controls. See [owner workflow](ALERT_OWNER_REVIEW.md).

NEXT-09 — 2026-09-25: selected launch command/review/offline behavior is in [ALERT_OPERATIONS.md](ALERT_OPERATIONS.md). The four commands below exist but remain inactive. Operator review/current/catch-up/health commands are designed, not implemented. Launch targets and limits are selected in that addendum; only certified store/market combinations may be exposed at activation.

Implemented 2026-09-21. The command layer is available for explicit composition and remains inactive. Main and automatic command discovery are unchanged. No migration, database connection, provider request, bot restart or notification was performed.

## Commands

- `/freebie status [page]`: private current settings, server revision, channel/store/market, enabled state, exact role IDs and activation cutoff. Large role lists span numbered pages; each response fits Discord's message limit.
- `/freebie setup revision channel store market roles enabled`: add or replace a FREE_GAME subscription. All settings are explicit. `roles` takes comma-separated IDs or `none`; this replaces existing roles. Setup also edits an existing subscription and preserves the service's role-only cutoff rules.
- `/freebie toggle revision scope enabled [channel] [store]`: change server, channel or store state. Server scope forbids target options; channel scope requires only channel; store scope requires both.
- `/freebie remove revision scope [channel] [store]`: remove the reviewed scope through the existing tombstone protocol. Recreating records retains the backend's new-incarnation rules.

First use status to obtain revision 0 for an unconfigured server. Every mutation requires the exact revision from a reviewed status response; the adapter never reloads a revision and silently overwrites another editor. Revision is a string option to preserve the full Java long range. Channel IDs are string options so disabling/removing a deleted channel remains possible. Stores and markets use exact deployment-policy identifiers; no launch catalog or product limits have been chosen. GOOD_DEAL setup and manual publishing remain later work. Server/channel operations apply to their entire scope, including any future topics stored there; status displays each topic.

## Access and outcomes

Commands are guild-only, slash-only, privately deferred and dispatched through the existing bounded command worker. Metadata requires Manage Server; the action boundary independently verifies guild/member/user correspondence and Manage Server or Administrator. ConfigurationService then repeats its current cache-based access check before reads or writes. Channel/role ownership and bot preflight remain in JdaConfigurationAccess. No synchronous network fallback or new executor exists.

The Discord interaction ID is the durable action ID. Actor/server identity comes only from the verified event. Unchanged duplicate requests use the existing receipt, and conflicting action reuse receives a fixed response. Stale revisions require reloading status. Unknown storage failures do not claim success or definite noncommit: the user receives a request ID and instructions to inspect status before another change. No automatic retry with a new ID occurs. This UI does not yet expose an operator receipt lookup.

Replies disable all allowed mentions. Status neutralizes formatting in stored identifiers. Error replies map typed access/policy outcomes to fixed text; arbitrary exception details are logged through FailureDiagnostics only. Disable/remove responses explain that an already-authorized alert may still arrive. Stored enabled state is not presented as proof of operational delivery health.

There are no buttons, modals or autocomplete providers in this increment. Thus there is no retained editor state, component ownership token or autocomplete configuration read to authorize. Any future component flow must add actor/guild binding, expiry and permission rechecks; manual preview/confirm remains separate work.

## Composition and verification

`org.bunnys.commands.alerts.Freebie.create(client, service)` is the thin registration factory. It returns the named FreebieCommand with four named subcommand implementations in `bunnynexus.commands.alerts`. The factory is deliberately not a BunnyCommand subtype, so the existing package scanner cannot accidentally activate it or fail for a missing injection constructor. A future composition root must explicitly construct the service with approved policy/access inputs and a verified, installed repository, register the returned command before publishing definitions, and separately compose delivery. No static service locator or handler feature dependency was introduced.

FreebieCommandsTest exercises command definitions, private/guild-only metadata, access-before-read, identity and revision binding, role deduplication/everyone rejection, replay and conflict outcomes, all toggle/removal scopes, stopping deleted destinations, unknown commit messages and maximum identifier/role pagination. Existing service, JDA adapter, policy and Mongo-boundary tests continue to cover preflight and storage behavior. These are local doubles, not real Mongo/Discord certification. Final build totals and logs are recorded in PROJECT_STATUS.md and PROGRESS_REPORT.md.

Next: AUD-03 review of command/service integration; deferred phase 6 remains mandatory before activation, followed by remaining discovery/transport/operational release work.



AUD-03 completed 2026-09-21: see AUDIT_REPORT.md. Input parsing now classifies only user input failures; repository validation errors preserve unconfirmed outcomes and safe diagnostics. Status formats only the requested page. Three audit regressions passed; combined phase-9 build passed 255 tests and packaged smoke. Next discovery increment is documented in ALERT_DISCOVERY.md.

