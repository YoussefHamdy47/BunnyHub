# Freebie launch behavior, human review and offline recovery

NEXT-10 override — 2026-09-25: the owner requested stronger manual control. [ALERT_OWNER_REVIEW.md](ALERT_OWNER_REVIEW.md) supersedes the earlier one-step/multi-operator approval below: one configured owner prepares, verifies, then separately releases with an explicit send-attempt cap and at most a one-hour release. The durable Mongo send gate now enforces that release. Owner UI, certified ingestion and activation remain incomplete. Earlier NEXT-09 sections describe the prior increment.

Decision date: 2026-09-23; continued and verified 2026-09-25 (Africa/Cairo). The user explicitly delegated these design choices and requested initial implementation. These decisions supersede conflicting earlier proposed behavior. They authorize development, not production provisioning or broadcasts. The implementation table below separates working foundations from outstanding integration.

## Chosen product behavior

Discovery is automatic. A server administrator configures subscriptions once; nobody runs a weekly discovery command. One central worker polls GamerPower every five minutes, independent of the number of servers. It finds candidates; authoritative storefront evidence and operator review determine whether a candidate may become an alert.

Launch requires one human operator approval per promotion, market and material content version. Individual server administrators do not approve every game. Approval releases the promotion to all currently matching opted-in subscriptions through the existing durable pipeline. If nobody approves, nothing is broadcast. Human delay is visible as awaiting review, not a successful delivery or a source outage.

Initial product scope is Epic Games Store PC base games temporarily free to keep, English messages, ordinary guild text channels, up to five channels per guild and five distinct roles per channel. Launch market targets are US and EG, selected explicitly by each subscription and certified independently before enabling either. These are target markets, not a claim that today's probe certifies regional eligibility. There is no worldwide fallback. Other stores, mobile-only giveaways, paid deals, DLC, demos, free weekends, permanently free games, key lotteries and paid-membership conditions remain disabled for this first launch. The underlying generic model remains extensible.

The supported catalog presented in commands must contain only certified store/market combinations. Deployments must not expose a selected target market merely because it appears in this document. An unavailable combination produces an explicit unsupported message.

Known, authoritative start and end instants are required. Evidence is refreshed centrally every five minutes and becomes stale at ten minutes. Unknown dates, ambiguous editions, missing market evidence, conflicting sources or an unapproved claim URL block publication. Approval cannot turn UNKNOWN into VERIFIED. The operator must obtain and retain supporting evidence through the future certification/review workflow; checking an approval box alone is insufficient.

## Live checks and what they prove

The actual existing GamerPowerFetcher was executed with its fixed HTTPS URL, validated public address pins, 15-second timeout and bounded decoding. No bot token, MongoDB connection or Discord send was used. The first restricted-network attempt failed with NETWORK/no HTTP response; the explicitly authorized network retry succeeded.

| Application fetch UTC | Result | Epic PC candidates | Independent storefront check |
| --- | --- | --- | --- |
| 2026-09-23 20:53:44 | HTTP 200; 19 candidates; zero rejected | Shogun Showdown (3782), Mindcop (3781) | Both official product pages displayed base-game, 100% discount and Free |
| 2026-09-24 21:22:04 (September 25 in Cairo) | HTTP 200; 20 candidates; zero rejected | Mechabellum (3790), Astrea: Six Sided Oracles (3029) | Both official product pages displayed base-game, 100% discount and Free |

Evidence: `.tools/EpicDiscoveryCheck.java`, `.tools/epic-live-check.log` and `.tools/epic-live-check-followup.log`. The follow-up demonstrates that a fresh invocation after the weekly rollover sees the new candidates. It does not demonstrate an already-running automatic service or a durable review queue.

Official product checks: [Shogun Showdown](https://store.epicgames.com/p/shogun-showdown-61832d), [Mindcop](https://store.epicgames.com/p/mindcop-78e6c1), [Mechabellum](https://store.epicgames.com/p/mechabellum-88a843), [Astrea](https://store.epicgames.com/p/astrea-six-sided-oracles-33c949). Candidate data is attributed to [GamerPower](https://www.gamerpower.com/), via its [game feed](https://www.gamerpower.com/api/giveaways?type=game).

The title/free-state comparison matched on both checks. The aggregator supplied timezone-free 23:59 end times. Epic's retrieved pages displayed localized sale-end times and, on the follow-up, different regional currencies. Those displays are not verified UTC instants or proof of US/EG eligibility. Consequently, the check is not sufficient to construct a certified Offer or authorize alerts. The generic free-games page did not expose the weekly cards in the text retrieval, so the comparison used the individual official product pages. No account checkout, claim, age verification or game acquisition was performed.

## Human review contract — OPS-01

The launch mode is REVIEW_REQUIRED; fully automatic approval is not enabled. Only operators on an explicit deployment-controlled user-ID allowlist can approve/reject. Manage Server grants local subscription management, not global publication rights. Approval identities come from verified interactions. A future automatic mode requires a separately reviewed source/certification policy change and evidence, not merely a hidden configuration switch.

The pending preview shows the exact title/description, direct official claim link, source attribution, store, edition, market, confirmed UTC window rendered in the viewer's timezone, proof timestamps, and an approximate matching audience count. Audience counts are estimates; actual recipients are rechecked at authorization. Previewing never pings destination roles or creates delivery jobs.

An operator confirms the current review ID/revision and material fingerprint within 15 minutes. Confirmation is actor-bound and idempotent by interaction ID in the planned durable command service. The current pure policy enforces operator allowlisting, revision/state, preview expiry, unchanged material and eligibility; it is not that durable command service.

Approval lasts until the earlier of the offer's end or seven days after approval. It is invalidated by material changes: campaign/store/market/edition, offer kind, proof statuses/provenance/rule version, price/currency, start/end times, title, description, claim link or attribution. Refreshing only evidence time and content revision preserves approval if the reviewed material remains identical and freshly verified. It does not revive an expired approval. A rejected fingerprint stays rejected until an explicitly audited new review; polling must not silently reopen it.

The material fingerprint is deliberately separate from canonical notification identity and per-attempt payload hashes. It excludes destination roles because the review authorizes the offer; each guild separately authorizes its own exact roles. Source field changes do not manufacture a new campaign. Manual and automatic publications still share event/channel uniqueness.

Approval must eventually be rechecked transactionally both when creating an event/plan and before send authorization, with a shared review guard touched to serialize revocation against new sends. An already-authorized external request may still arrive after a revoke, just as with unsubscribe. A preflight-only in-memory check is insufficient. **That durable guard is not implemented in NEXT-09. Do not activate delivery with only PublicationReviewPolicy.**

## Commands and day-to-day use

The four existing configuration commands are implemented but inactive. Their current exact parameter contract is in [ALERT_COMMANDS.md](ALERT_COMMANDS.md).

| Command | Who | Chosen behavior | Current implementation |
| --- | --- | --- | --- |
| `/freebie status [page]` | Guild Manage Server/Administrator | Show current settings/revision; later add separate discovery/delivery health without confusing enabled with healthy | Settings exist; health extension pending |
| `/freebie setup revision channel store market roles enabled` | Guild administrator | Save one explicit subscription; `roles:none` means no pings; existing settings are replaced only at the reviewed revision | Exists |
| `/freebie toggle revision scope enabled ...` | Guild administrator | Pause/resume server, channel or store; disabling needs no working destination permissions | Exists |
| `/freebie remove revision scope ...` | Guild administrator | Remove chosen scope with tombstones/incarnation handling | Exists |
| `/freebie current` | Guild administrator initially | Private view of current reviewed offers for the server's market; never publishes or pings | Planned |
| `/freebie catch-up` | Guild administrator | Preview an explicitly chosen still-live offer for one configured destination, then confirm within 15 minutes; reuse existing event/channel uniqueness | Planned; separate manual audience plan required |
| `/freebie-review list` and `preview id` | Allowlisted operator | Bounded pending queue and exact evidence/preview, privately | Planned |
| `/freebie-review approve id revision` or `reject id revision` | Allowlisted operator | Confirm reviewed material; record actor, evidence, decision and idempotency receipt atomically | Pure rules exist; command/storage pending |
| `/freebie-review revoke id revision` | Allowlisted operator | Stop new authorization for a reviewed promotion; retain audit and receipts | Planned |
| `/freebie-ops status`, `pause`, `resume`, `check` | Allowlisted operator | Health/kill switch; checked explicit resume; request a central due check without bypassing pacing, source pause or review | Planned |

Example: the administrator runs status, then setup with revision 0, their channel ID, store `epic`, a certified market, roles `none` or chosen role IDs, and enabled true. After that, normal discovery and recovery need no user command. New pending promotions wait for the bot operator's review. Once approved, the dispatcher sends to matching subscriptions automatically. Setup success does not mean delivery is operational; health must explain inactive, awaiting review, stale source, paused, or queued states.

Joining or re-enabling does not replay already-created announcement events. The existing automatic cutoff remains the event's first eligible observation time, not the source's claimed publication date or the game's release date. A still-live promotion first admitted after restart/approval may therefore be new to the bot even if it began earlier. Explicit catch-up covers already-known active promotions and must not create a duplicate job for a channel that already has one. An uncertain prior delivery cannot be bypassed by catch-up.

## Offline and restart behavior — OPS-02

Run one supervised application service on an always-on host for launch. The supervisor may restart a crashed process; it cannot run while the host is powered off. Discovery and Discord delivery use separate bounded worker lanes in that process. A disconnected Discord gateway does not need to stop discovery if the application, source ownership and MongoDB remain healthy; delivery pauses until ownership, connection and fresh permissions are valid.

| Situation | Required behavior |
| --- | --- |
| Application and host stopped | No polling or sending occurs. Mongo retains accepted state. A promotion that starts and ends during the outage may be missed permanently. |
| Process starts again | Verify schemas/configuration, establish ownership, recover expired jobs, start the explicit recurring discovery loop, refresh evidence, then admit sends. No operator command is needed for ordinary restart. |
| Startup discovery | Schedule the first tick immediately. Existing durable nextPollAt, backoff and pauses are respected; a recent successful poll may delay HTTP until the next five-minute slot. Never replay every missed interval. |
| New still-live offer found after outage | Save/review once, then create the ordinary canonical event and bounded fan-out after approval and current evidence. |
| Offer expired before restart | Do not send an expired giveaway. Skip expired queued work; retain appropriate history/metrics. Do not invent unavailable historical discoveries. |
| Approval pending while offline | Restore the pending review. An expired preview needs a fresh preview; it does not auto-approve. |
| Previously approved offer still live | Refresh evidence, retain approval only if material matches and approval is unexpired, and resume unsent work after current permission/configuration checks. |
| Crash after claim, before authorization | Expired LEASED work becomes ready through the existing recovery transition. |
| Crash during send or before acceptance receipt | Expired SENDING becomes UNCERTAIN. Never automatically resend or claim successful delivery; operator reconciliation remains required. |
| Known SENT job | Keep uniqueness/receipt evidence and do not resend on restart, check command or repeated polling. |
| Provider fails or gives partial/empty data | Keep prior records; no mass withdrawals. Stale evidence blocks sends. Use bounded backoff; known access/rate-limit pauses survive restart. |
| Database unavailable | Stop admission. Do not substitute an in-memory durable queue or advance ingestion cursors. Preserve bounded unresolved work. |
| Backup restored | Keep sending paused until the potentially already-sent interval is reconciled; a normal restart and a historical restore are different operations. |

No service can guarantee discovering a short promotion while all its discovery processes are offline. A later separate always-on discovery worker is an extension if the bot must deliberately be stopped often; it still requires independent hosting, durable source ownership and the same review pipeline. It is not necessary for ordinary restart catch-up.

Start with eight active sends, a 200-candidate window, four background database operations and at least 50 ms between delivery claim attempts. These are selected initial operating budgets, not measured Discord throughput. Preserve the existing 100,000/80,000 delivery and 10,000/8,000 outbox pause/resume planning thresholds, pending composed pressure-control implementation and measurement. Do not increase queues to hide outages. The approximately 1,000-server target and delivery latency remain unproven release objectives.

## Persistence and integration sequence — OPS-03

Next implementation order:

1. Durable bounded candidate/evidence retention and validated display-content codec, with atomic offer/display revision consistency. Preserve source attribution and immutable source-to-campaign evidence; distinguish recurring campaigns from reused source IDs.
2. Explicit additive review collection/index migration and transactional repository: pending/current fingerprint, state/revision, operator decision, evidence references, timestamps, and action-id receipts. Stable review IDs are separate from event IDs. No unbounded embedded decision history; audit rows are separate. Active reviews and uncertainty have no automatic TTL.
3. Add mandatory review-guard checks to observation/event creation and send authorization. Changing writers requires stop-before-start upgrade and conflict/revocation/restart tests. Existing evidence flags alone cannot bypass the guard.
4. Named private operator commands and health presentation. Every interaction rechecks access; preview confirmation binds actor, review revision, fingerprint and expiry. Pause/resume is audited; no global retry of uncertain work.
5. Certified Epic market/edition/campaign/UTC enrichment, composed ingestion and bounded scheduler/real transport, then isolated integration tests and an explicitly opted-in test destination. Only after those gates should Main start the runtime and register the commands.

This sequence intentionally does not introduce an in-memory production review queue or infer certified evidence from today's title match. Existing catalog IDs, delivery identities, BSON fields and migration checksums remain unchanged in NEXT-09.

## Implemented in NEXT-09

| Component | Delivered | Limit |
| --- | --- | --- |
| Live comparison | Two actual application fetches, before/after rollover, independently checked against official product pages | Title/free-state evidence, not regional/UTC/recurrence certification |
| PreparedPayload identity | Mandatory offer key, subscription ID and destination/incarnation; authorization rejects equal-revision foreign payloads | Java API change; no persisted schema change |
| PublicationReviewPolicy | Immutable preview/decision rules, explicit operator allowlist, material fingerprint, 15-minute confirmation, seven-day/end cap, current eligibility recheck | Pure inactive rules; no repository, UI, revocation transaction or integrated publication gate |
| GamerPowerPollingLoop | Explicit start, immediate first tick, 15-second fixed-delay checks of durable due state, shutdown cancellation, diagnostic result and exception containment | Not wired into Main; caller injects/owns a dedicated scheduler and a poller configured for five-minute polling |
| Documentation corrections | Retry nonce instructions and stale architecture/discovery inventory corrected | Older dated audit evidence remains historical |

Verification: 42 focused tests passed in `.tools/next09-focused.log`. Final JDK 21 offline Maven `clean verify` passed **324 tests**, zero failures/errors/skips (`.tools/next09-verify.log`), including 14 added regressions. Packaged smoke passed (`.tools/next09-smoke.log`). The repaired audit probe rejects the cross-offer/subscription payload and preserves the same-nonce retry invariant (`.tools/next09-audit-probe.log`). Existing real Mongo and synthetic-load suites were not repeated; this increment changes no persisted format or repository query, and their evidence remains scoped to VERIFY-01. Nothing here means live subscriptions, review decisions or broadcasts have been activated.
