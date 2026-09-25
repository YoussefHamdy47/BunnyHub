# Server configuration service

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Implemented 2026-09-14 under the user's request to advance to the next stage. This is the backend configuration layer; no slash commands, migrations, subscriptions in the live database, workers or notifications were activated. Main remains unchanged.

## Supported use cases

- View a guild's current settings after an administrator check.
- Add/update a channel/store/topic selection with explicit market and exact role IDs. Sources remain separate from stores. Allowed stores, markets, topics, channel types and limits are constructor inputs, with no assumed production defaults.
- Enable/disable a guild, destination or individual subscription. Dedicated disabling operations need administrator authority but no surviving Discord channel/role, so broken permissions do not prevent stopping alerts.
- Remove a subscription, destination or all guild configuration. Removal retains immutable identity tombstones. Recreation uses new destination/subscription identities and a new destination incarnation.
- Reject stale editors using the expected guild revision. Replaying the same interaction returns its durable receipt; reusing the interaction with another actor, revision or intent returns an idempotency conflict.

`ConfigurationPolicy` owns pure immutable transitions and bounded capacity checks. `ConfigurationService` owns access-before-read, preview and preflight orchestration through injected ports. `JdaConfigurationAccess` performs cache-only administrator, same-guild channel/role, bot view/send/embed, timeout and mentionability checks. `MongoConfigurationRepository` owns atomic persistence, while the codec and projection writer have separate responsibilities.

An entry-point adapter must derive actor/guild IDs from the real Discord interaction, never from user-submitted IDs or unverified component IDs. Missing guild/member data or a disconnected JDA cache denies access; there is no synchronous network fallback. The cache is not a remote permission certificate. Successful evidence is bound to the exact request, channels and roles, expires within a technical maximum of 60 seconds, and is checked again using database time. Delivery still performs its own current preflight. Permission changes after the check cannot be atomically synchronized with Discord.

## Eligibility, identity and concurrent edits

Role-only edits retain enabledSince. Subscription re-enable and market changes reset it. Guild/destination re-enable refreshes enabled subscription cutoffs, preventing implicit catch-up of old offers. Disabled subscriptions retain their state. A deployment that removes a supported selection can still disable/remove old settings, but cannot re-enable that unsupported selection through a toggle.

The guild revision serializes all configuration edits and capacity admission. A transaction touches the same guild guard used by send authorization, evaluates the policy on the current aggregate, writes changed destination/subscription projections, stores the aggregate and audit receipt, then applies a final permission-deadline fence to the guild guard. A failure aborts the transaction; unknown commit results propagate. The caller retries the same interaction ID and consults its receipt rather than inventing success or a new request ID.

This preserves the existing unsubscribe boundary: an edit ordered before send authorization is observed or conflicts; an already-authorized external send may still arrive. Configuration revision never enters delivery uniqueness. Old deliveries cannot bind to recreated subscriptions/destinations, and existing event/channel receipts still prevent duplicate announcements.

## Storage decision: bounded aggregate plus compatible projections

Decision CONFIG-01: add `AlertConfigurations` as the bounded current administrator aggregate and keep the existing AlertGuilds/AlertDestinations/AlertSubscriptions documents as transactionally maintained authorization/fan-out projections. This is an additive implementation refinement, not a change to ownership, durability or notification identity.

Why: a single expected-revision update can enforce exact per-guild capacity without count-then-insert races or a separate admission counter that can drift. Its disadvantage is rewriting the bounded current aggregate on each edit; this is appropriate for infrequent administrator changes and must be measured before raising limits. The technical ceilings are 100 destinations, 1,000 subscriptions and a 4 MiB serialized aggregate budget. Configured product limits must be explicitly lower or equal; roles are also bounded per destination. These are defensive implementation ceilings, not launch capacity claims.

Only changed projections are updated, retaining existing BSON fields and guard-touch behavior. Removed destination/subscription identities move to `AlertConfigTombstones` in the same transaction that removes their active projection. This frees existing channel and destination/store/topic unique slots while preserving removed references. Tombstones contain bounded identity metadata rather than role payloads. Guild removal keeps a disabled, empty, revisioned aggregate and guild guard. No automatic tombstone or audit expiry exists; retention and storage-pressure controls remain release work.

`AlertAudit` stores one immutable action-ID receipt with actor, guild, before/after revision, timestamp, canonical bounded intent and its fingerprint. The primary key prevents action reuse; the guild/revision index prevents multiple audit records for one committed configuration revision. There is no full-history read into memory in the service.

## Migration and rollback

`MongoConfigurationSchema` defines the explicit additive `alert-configuration-v1` migration. It preserves both earlier migration manifests/checksums and creates indexes for the aggregate, audit and tombstones. Run it with all alert writers stopped, after verifying the base schema. It is never called by constructors or Main.

First installation refuses existing guild/destination/subscription/configuration/audit/tombstone data rather than inventing an aggregate over manually seeded configuration. Such data needs a separately reviewed import/reconciliation migration. An already-installed schema is verified without rewriting it. Partial index installation can be rerun while stores remain empty. Never mix independent legacy configuration writers with this aggregate writer. Retain aggregate/audit/tombstone data on rollback; keep alert writers stopped until projection consistency and counters are verified.

## Verification and next handoff

Tests cover role-only versus eligibility edits, enable transitions, removal/recreation identity, stale revisions and capacity, unsupported selections, immutable data, administrator-before-read ordering, exact and expired evidence, replay/conflicting action IDs, compatible BSON projections, audit and deadline fences, deletion tombstones, transaction callback replay, unknown commit propagation, cache permission failures and migration refusal/idempotence.

Mongo tests use driver-boundary doubles; they do not prove real rollback, transaction isolation, durable replay, failover or query plans. Discord tests use JDA doubles; they do not contact Discord or register commands. JDK 21 offline Maven clean verify passed all 189 tests (26 added in this stage), with zero failures/errors/skips. Packaged smoke passed command/component discovery, JDA payloads, BSON codecs, logging, manifest and secret/test exclusions. Build evidence: `.tools/configuration-verify.log`; packaged smoke: `.tools/configuration-smoke.log`.

Phase 6 (deferred by the user, still required before activation): isolated replica-set tests covering concurrent configuration edits versus send authorization, final-slot contention, duplicate initial upserts, unknown commits, delete/recreate and failed migration/restart. Runtime/source ownership and scheduling foundations are now implemented in ALERT_RUNTIME.md. Next is phase-8 command registration, followed by the requested full audit, with interaction ownership, response mapping and autocomplete authorization to be implemented at those entry points.


## Phase 8 handoff — 2026-09-21

Phase 8 server setup commands are implemented and inactive. See [ALERT_COMMANDS.md](ALERT_COMMANDS.md) for command syntax, explicit registration, identity/revision binding, outcomes and limits. JDK 21 offline Maven clean verify passed **244 tests, zero failures/errors/skips**; packaged smoke passed for target-upgrade/BunnyHub-4.00.jar. Logs: .tools/phase8-verify.log and .tools/phase8-smoke.log. Twelve new tests cover the command boundary. No schema, persisted identity, Main composition, bot restart or live send changed. AUD-03 command integration review remains next; phase 6 remains deferred and mandatory before activation.


