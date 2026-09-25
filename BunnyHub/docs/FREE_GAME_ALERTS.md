# Planned free-game alerts

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](PROJECT_STATUS.md), [progress report](PROGRESS_REPORT.md) and [audit report](AUDIT_REPORT.md). Phase 8 commands are implemented but inactive as of 2026-09-21; see [command notes](ALERT_COMMANDS.md). Phase 6 remains deferred and required before activation. Earlier measurements below are historical.

Status: **original product plan; engine foundation started under 2026-09-13 authorization**. See [ALERT_ENGINE.md](ALERT_ENGINE.md) for implemented classes and remaining work. The alert service is not activated.

For the detailed implementation blueprint, read [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md). It refines this initial plan with state machines, transaction boundaries, configuration races, operational budgets and release gates. The user-delegated launch review, command and offline-recovery decisions are now in [ALERT_OPERATIONS.md](ALERT_OPERATIONS.md). This file remains the original product context.

The follow-up [API research and feature design](ALERT_SOURCES_AND_FEATURES.md) adds source recommendations, optional Good Deals digests, and manual publishing through the same durable pipeline.

## Confirmed product idea

A client signs their Discord server up for free-game alerts. They choose channel(s), role(s) to ping, and services to follow: Epic Games, Steam, GOG, and potentially others. The bot automatically discovers free games and notifies every matching subscribed destination. Launch planning target: approximately **1,000 servers**, confirmed by the user.

The existing timer/info/avatar functionality must continue to work. The backend must keep interactive commands responsive while a newly discovered game triggers a large notification burst.

## Proposed architecture

```text
Provider pollers → normalized offers → durable fan-out event
                                           ↓
Subscription pages → idempotent delivery-job creation
                                           ↓
Leased delivery jobs → bounded Discord dispatch → recorded outcome
```

These are future components, not existing services. Start with one deployment and MongoDB-backed durable work if it meets measured targets; do not add Redis, Kafka, or extra services solely because the system may eventually grow. Keep interfaces separable so discovery or delivery can move into separate processes when measurements justify it.

### Discovery

- Poll each provider/region centrally, not once per subscribed server. Prefer documented provider APIs or feeds; validate their availability, permitted use, and response semantics when implementation begins.
- Isolate providers with independent timeouts, concurrency limits, retry backoff with jitter, and circuit-breaking behavior. One provider outage must not occupy every worker or prevent other providers from reporting offers.
- Track last successful check, freshness, conditional-request tokens where supported, and errors. An error or incomplete response is not proof an offer expired.
- Normalize stable provider offer/campaign IDs, game/edition identifiers, region restrictions, canonical claim URL, start/end times, and offer type. Do not identify offers by display title alone. A returning promotion must remain distinguishable from a repeat scrape of the same promotion.
- Durably record the offer change and a fan-out event atomically, using a transaction or an equivalent recoverable single-document design. A crash between discovery and fan-out must not lose the notification.

### Subscriptions and fan-out

- Store subscriptions per server/destination, with selected providers and explicit role IDs. Proposed model: destination records with guild ID, channel ID, role IDs, enabled providers, enabled state, and revision; final role/provider granularity requires product confirmation.
- Enforce appropriate server-management permission on configuration. Validate channel ownership, send/embed permissions, selected roles, and removal/update paths.
- Read matching destinations in bounded indexed pages with a stable cursor. Persist fan-out progress; replaying a page must be safe. Never load all subscribers into one list or schedule one timer per guild.
- Create delivery jobs with a unique key such as `(offerEventId, channelId)`. Collapse duplicate subscription matches for the same event/channel and combine only its configured role IDs.
- Handle subscription changes explicitly: recheck disabled/deleted destinations and current role configuration before sending. Persisted jobs must not keep pinging a role or channel that has been removed from configuration.

### Delivery and recovery

- Proposed job states: pending, leased, retryable, sent, skipped, permanently failed. Track attempt count, due time, lease expiry/token, last error category, Discord message ID when known, and completion time.
- Claim jobs atomically with a lease and ownership token. A worker may only update its own current lease. Expired leases must become recoverable after a crash. Long-running requests need lease renewal or a lease duration covering the entire admitted request lifecycle.
- Claim only a small bounded window of work. Hold an in-flight slot until the Discord request finishes, not merely until `.queue()` returns. Keep the durable backlog outside JDA's in-memory request queue.
- Respect rate-limit headers and retry guidance through JDA. Apply additional application-level admission pacing so alert bursts do not consume every available bot request. Guild/channel fairness, interaction headroom, and deployment-wide coordination matter; separate executors alone do not create independent API quotas. [Discord rate limits](https://docs.discord.com/developers/topics/rate-limits), [JDA RestAction](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html).
- Retry transient failures with capped exponential backoff and jitter. Stop/reclassify invalid-token, missing-channel, and missing-permission failures instead of retrying indefinitely. One bad destination must not stop the fan-out batch. Stop sending expired offers according to an explicit product policy.
- Use approved templates and per-message allowed role mentions. Escape untrusted provider text and never globally enable `@everyone` or every role mention. Check that a configured role can actually be pinged without silently broadening permissions.
- Record outcomes durably and expose retry/dead-letter diagnostics. Shutdown stops new claims, waits a bounded time for in-flight requests, and leaves recoverable leases for unfinished work.

**Delivery guarantee:** target recoverable at-least-once processing with application deduplication. A unique database key prevents duplicate jobs, but cannot alone prevent duplicate Discord messages if Discord accepted a message immediately before the process died without recording its ID. Investigate documented Discord nonce/deduplication behavior and its time window before promising anything stronger. Distinguish definite failures from unknown outcomes; never describe this as proven exactly-once delivery.

### Storage and indexes (proposals)

Use separate alert-domain records, not timer account documents. Index definitions must match the final query shapes and be tested with representative data:

| Record | Access pattern / proposed constraint |
| --- | --- |
| Provider state | Unique provider/region; next eligible poll and lease ownership |
| Offers | Unique provider/campaign/region identity; expiry lookup |
| Subscriptions | Unique guild/channel as appropriate; indexed enabled-provider scan with stable page order |
| Fan-out events | Unique event ID; pending cursor/lease lookup |
| Delivery jobs | Unique event/channel; due-state/next-attempt claim index; lease-expiry recovery index |

Bound batch sizes, payload sizes, history retention, and retry counts. TTL cleanup is for completed historical data, not a substitute for leases or deletion of pending jobs. Verify index use with query plans and prevent unbounded embedded subscriber or delivery arrays. These collections/indexes remain unimplemented in the initial engine foundation.

## Capacity plan for 1,000 servers

Destinations, not guild count alone, determine send volume:

`messages = subscribed servers × matching channels per server × offers in burst × messages per offer`

Example assumption, not an agreed requirement: 1,000 servers × 2 channels × 1 offer = 2,000 messages. At an **illustrative** sustained allocation of 20 successful messages/second, the idealized drain time is 100 seconds, before retries or route limits. Twenty is a scenario input, not a hardcoded Discord limit or a measured capability. Multiple simultaneous offers multiply the burst.

Define acceptable discovery lag and p95/p99 delivery lag before promising a service level. More workers cannot remove provider cadence or Discord rate limits. Reserve resources for existing commands, measure database saturation, and change concurrency based on queue age and completion rates rather than queue length alone. Mongo pools are per server in the topology, with additional monitoring connections; budget across every application process. [MongoDB connection pools](https://www.mongodb.com/docs/drivers/java/sync/current/connection/specify-connection-options/connection-pools/).

## Required tests before feature launch

- Simulate 1,000 subscribed guilds with one and several channels, multiple simultaneous offers, and ordinary commands arriving during fan-out. Include a larger burst to observe overload behavior, without claiming that larger scale is supported.
- Fake provider errors, partial responses, repeated offers, returning campaigns, regional variants, and expired offers. Verify one provider cannot starve others.
- Exercise queue pressure, Discord 429/403/404/5xx, delayed callbacks, unknown send outcomes, and misconfigured destinations using controlled fakes before any approved live test.
- Crash/restart after discovery, during fan-out, after job claim, and after send but before recording success. Verify recoverability and characterize duplicates honestly.
- Validate concurrent workers cannot claim the same live lease, subscription edits are respected, and targeted role pings never broaden.
- Measure p50/p95/p99 discovery/delivery lag, command latency, oldest pending age, in-flight sends, retry/dead-letter counts, DB operation latency/checkout waits, heap/GC, and retained jobs after a soak run.

## Decisions still needed

- Does “free game” mean free-to-keep giveaways, permanently free games, free weekends/trials, DLC, or a configurable subset?
- Are regions/languages selectable? Are providers and role sets configured per channel or per server?
- Should joining a server receive currently active offers or only newly discovered offers? How are edited or returning offers announced?
- What are the target alert delivery time, hosting budget/resources, retention periods, and maximum destination/role counts?

These are open product decisions, not blockers to the completed backend hardening and not permission to begin the feature.


