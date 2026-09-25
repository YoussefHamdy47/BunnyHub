# Phase 9: discovery intake — 2026-09-21

NEXT-09 update — 2026-09-25: explicit inactive recurring polling lifecycle and publication-review rules now exist. Two one-shot application probes before/after Epic's weekly rollover matched official product-page free listings; regional/UTC certification remains open. See [selected operations and recovery design](ALERT_OPERATIONS.md). No Main activation or durable candidate/review ingestion yet.

Status: started, inactive. Bounded GamerPower decoding/fetching, a fenced durable shadow polling schedule and explicit tick controller now exist. No recurring polling, production source provisioning, database ingestion, offer publication or notifications are activated. Phase-6 verification resumed at the user's request on 2026-09-22; see [VERIFY-01](ALERT_VERIFICATION.md) for real local replica-set tests and remaining release gates.

Latest response certification — 2026-09-23 (DISC-03): saved genuine 201 no-results, 404 missing-ID and 200 mobile-listing fixtures on 2026-09-22. Intake/fetch now accept only the exact captured two-field 201 no-results envelope as EMPTY, with strict structure and transport limits. Arbitrary 201 bodies and 404 remain failures. Four new regressions cover captured responses and malformed variants. EMPTY never authorizes withdrawals or cursor advancement. Eligibility, market/timezone, recurrence and canonical identity remain uncertified. See [verification evidence](ALERT_VERIFICATION.md).

## Source research and certification status

Rechecked [GamerPower's official API documentation](https://www.gamerpower.com/api-read) on 2026-09-21. It documents unauthenticated giveaway endpoints, an active attribution hyperlink requirement and a ceiling of 10 requests per second. That ceiling is not this application's approved polling cadence. No account or credentials were created.

A research-browser read of [the game feed](https://www.gamerpower.com/api/giveaways?type=game) returned listing JSON. The observed structure includes listing IDs, platform labels, local timestamp strings, source URLs and unknown-end markers. It also includes giveaways with additional claim conditions. This is limited research evidence, not a bounded application HTTP smoke test or a captured raw transport fixture. No returned promotion was published or persisted.

Open certification questions: timestamp timezone; regional availability; base-game/permanent entitlement; direct-store versus third-party key promotions; source listing reuse and canonical campaign identity; upstream freshness/order; empty-response body/status contract; completeness/withdrawals; required URL handling. A successful feed read does not resolve them.

## Implemented boundary

`GamerPowerIntake.decode(httpStatus, body, fetchedAt)` takes a caller-supplied byte array and emits immutable candidate batches. It has no network, repository or delivery dependencies.

- Rejects bodies above 2 MiB before parsing, more than 500 items, excessive strings/nesting/tokens, duplicate JSON fields, duplicate candidate IDs, truncated documents and trailing JSON.
- Invalid individual rows produce PARTIAL, retaining valid candidates for review. Structurally malformed, duplicate-ID or oversized batches discard all candidates. No state implies authority to withdraw offers or advance a durable polling cursor.
- A 200 empty array produces EMPTY. Since DISC-03, the exact captured 201 no-results envelope also produces EMPTY; other statuses and malformed 201 bodies fail closed. Error bodies cannot become successful empty feeds.
- Keeps source item identity, platform labels, local dates, claim instructions, fetch time and attribution separate from canonical offers. Unknown ends remain absent; no timezone, zero price, store identity or market verification is invented.
- Accepts source/claim links only on the exact HTTPS GamerPower host with expected paths and no credentials, port, query or fragment. It never fetches redirects. These restrictions are intake checks, not DNS/egress protection for a future HTTP client.
- Parser failures return fixed reasons rather than exposing raw response content in exception logs. Candidate text remains untrusted and is not ready for direct Discord rendering.

The JSON parser is now an explicit jackson-core 2.22.2 dependency, matching the existing JDA runtime dependency; no version upgrade was performed. Resource budgets are technical intake ceilings, not approved launch defaults or measured capacity.

## Tests and next increment

Eight offline tests use a clearly synthetic fixture shaped after the documented/observed response. They cover unknown dates, attribution, immutable results, partial/empty/error states, duplicate and malformed responses, payload/item/nesting/string limits and unsafe links. These are not captured source-certification fixtures.

Next: capture reviewed provider transport fixtures including no-results/errors, and settle the source semantics above. Then add durable source polling state and ownership/backpressure orchestration, followed by certified canonical mapping/enrichment. Only verified evidence may reach ObservationRepository; intake candidates currently cannot do so.

Main, schema manifests, source/runtime ownership contracts and persisted identities are unchanged. See PROJECT_STATUS.md for final verification evidence.

## NEXT-06: bounded fetch adapter — 2026-09-21

GamerPowerFetcher is explicitly constructed with public address pins, a request timeout and minimum spacing. Construction does not fetch. The caller must use a dedicated discovery worker; no scheduler, gateway callback, interaction worker, Main registration or static singleton is introduced. One instance must belong to the future durable source owner. Per-instance admission does not replace cross-process source leases.

The adapter calls only the fixed HTTPS game-feed URL. It retains default TLS certificate/hostname verification, rejects redirects, disables proxies and connection retries, and adds a per-call network-interceptor guard against a second HTTP exchange. No user-submitted URL, role or server ID enters a provider request. The OkHttp 5.5.0 dependency is now explicit, matching the existing JDA runtime version.

Transport decision DISC-01: use explicitly supplied immutable public IPv4 pins, with an exact-host resolver and no runtime/system DNS fallback. Reject private, shared, loopback, link-local, documentation, benchmark, relay, multicast/reserved ranges and all IPv6 addresses. The conservative exclusions follow the [IANA special-purpose IPv4 registry](https://www.iana.org/assignments/iana-ipv4-special-registry). This avoids an unbounded DNS stage and DNS rebinding between validation and connection. Deployment must obtain and refresh provider addresses through a separately reviewed process; stale pins fail closed. IPv6 and automatic DNS refresh remain unsupported, not silently mapped or accepted. This is an inactive transport implementation decision, not a persisted identity/schema change.

The HTTP call has an explicit whole-call timeout plus finite connect/read/write timeouts (at most 60 seconds). The same deadline is checked before and after the bounded parser, rejecting late results; parsing is bounded but not asynchronously preempted. Only application/json with identity encoding is accepted. The request explicitly asks for identity encoding, so transparent gzip is not enabled; any compressed response is rejected. Content-Length and streamed bytes are independently capped at 2 MiB, reading at most one extra byte to detect overflow. Responses close on all branches.

Admission allows one unfinished call, with no internal queue. A busy caller receives BUSY. Minimum pacing is explicit (technical range one second to one hour); failures increase the interval exponentially up to one hour, and success resets it. HTTP 401/403/429 pauses this instance indefinitely for operator review, so it cannot ignore Retry-After or automatically retry access failures. There is no resume endpoint; a future owner must resolve the reason before replacement. These local counters do not survive process restart and are not a durable poll schedule.

close() stops new admission, cancels an active request and evicts connections. Cancellation does not free the active slot: the running fetch retains it until execute/read/decode returns. A result arriving after shutdown is rejected. Cancellation, timeout and failed/partial parsing never become successful empty feeds or trigger withdrawals/cursor changes.

Ten new tests cover request identity/encoding, success spacing, failure backoff/reset, access/rate-limit pauses, redirect/error responses, partial feeds, media/encoding/byte limits, late results, response closure, concurrent admission, shutdown cancellation, pinned-address policy and second-exchange rejection. A loopback-only real HTTP test stalls a response body and verifies whole-call timeout. All other transport boundaries use local doubles. This does not certify production TLS/egress, provider semantics, real Mongo recovery or delivery capacity. No live provider fetch was performed in this increment.

NEXT-06 verification: JDK 21 offline Maven clean verify passed 271 tests, zero failures/errors/skips; packaged smoke passed. Logs: .tools/phase9-fetch-verify.log and .tools/phase9-fetch-smoke.log. No provider call, migration, bot restart or delivery activation occurred. Other timer changes already present in the workspace were preserved.

## NEXT-07: certification evidence and durable shadow schedule — 2026-09-21

The production GamerPowerFetcher was exercised with one explicitly invoked read-only probe. Restricted-network execution first returned NETWORK/no HTTP response; the authorized unrestricted retry returned HTTP 200, 20 candidates, zero rejected rows and nine unknown ends. A separate bounded capture saved the unmodified 19,878-byte successful feed under src/test/resources/gamerpower/live-games-2026-09-21.json, with timestamp, source attribution and SHA-256 in its adjacent Markdown file. The capture uses validated address pins, TLS, fixed URL, no redirects/proxy, one exchange, identity encoding and a 15-second deadline. Probe/capture tools never access bot credentials or Mongo. Logs: .tools/phase9-provider-probe.log and .tools/phase9-provider-capture.log. A regression decodes the captured fixture. No live feed is used by automated tests.

Certification matrix:

| Contract | Evidence / disposition |
| --- | --- |
| Access and attribution | Official API documentation reviewed; retain active GamerPower attribution; no account created |
| Successful HTTPS transport and response schema | One application probe and one saved HTTP 200 fixture passed; not a service-level guarantee |
| Unknown ends | Nine observed; preserved as absent, never inferred to be permanent or worldwide |
| Empty/error response body | Genuine 201 and 404 fixtures captured 2026-09-22; exact 201 envelope supported by DISC-03. Rate-limit/server-error fixtures remain open |
| Timezone, market, entitlement and claim conditions | Not certified; no eligible Offer is constructed |
| Canonical store/edition/campaign, recurrence and source ordering | Not certified; source listing IDs never become canonical promotion identities |
| Completeness/withdrawal and pagination | Not certified; an absent/empty/partial response cannot withdraw offers |

DISC-02: add poll-format-1 schedule fields to the existing source ownership document. A single majority-written atomic update can then check the same source token, generation and database-time lease expiry while changing due time and attempt state. PollRepository and MongoPollRepository reserve a due attempt before HTTP and finish it only for the same live source lease and attempt. No transaction includes HTTP. Fields are nextPollAt, pollPaused, pollFailures, pollAttempt/pollGeneration, start/finish timestamps, outcome and bounded candidate/rejection counts. These are a shadow health summary, not an ingestion cursor, source ordering certificate, observation archive or publication event.

begin requires an installed format, unpaused state, due database time and either no active attempt or a different generation. It reserves the next minimum interval before fetching. finish atomically records the summary, applies capped exponential backoff (one hour maximum), resets failure count on success and persists known access/rate-limit pauses. A committed pause survives restart and has no automatic resume API. Repeating finish after a known completion cannot rewrite it. Unknown begin prevents fetch; unknown finish never causes an immediate repeated HTTP request or claimed success. A local tick does not release an uncertain lease; it expires naturally. After a crash, a later generation can replace an abandoned attempt only once due. A crash/unknown write before a pause is committed cannot guarantee that pause persisted; durable pause reconciliation and operator tooling remain release work.

GamerPowerPoller binds the single gamerpower/games scope, admits one synchronous tick, checks injected storage-pressure admission before ownership and again before fetch, and budgets acquisition/reservation elapsed time against the database-issued lease lifetime. Its lease must leave explicit fetch and persistence reserves. No new executor or background timer is created. It records only summaries; candidate payloads are not archived or forwarded to ObservationRepository. close cancels the fetcher and forbids new ticks; ownership is not released until synchronous work ends. A lost completion fence rejects the result. Uncertain storage results remain observable as UNCERTAIN, never as successful polling.

Migration: MongoPollingSchema defines explicit additive alert-polling-v1. Stop all source writers before installation/provisioning, verify existing runtime/source schemas, then provision polling fields only on already-provisioned source records. Existing source identity, generation, lease and earlier migration checksums are unchanged. No new collection, index, TTL or unbounded scan is added. Re-provisioning does not reset installed schedules, pauses or failure counts; invalid/missing records fail closed. No migration was run. Rollback keeps the additive fields and pauses source writers until compatibility is reviewed; do not mix older workers that ignore the schedule.

Tests cover reservation/fetch/finish ordering, exact attempt reuse, pressure, insufficient lease time, unknown writes, stale completion, paused outcomes, concurrent ticks/shutdown, Mongo filter/update contracts and schema drift/non-reset behavior. Driver doubles establish requested Mongo operations, not crash isolation or server execution. Real replica-set tests remain mandatory, including begin/finish races, clock/expiry boundaries, uncertain writes, restart and source ownership contention.

Next: genuine no-results/error/recurrence fixtures, certified identity/eligibility enrichment, durable candidate retention and ingestion cursor/backpressure transactions. Add audited pause reconciliation/resume and production owner/executor composition only after those contracts are verified. The source remains uncertified for automatic publication.


NEXT-07 final verification: JDK 21 offline Maven clean verify passed **284 tests**, zero failures/errors/skips. Packaged smoke passed and raw fixture/probe classes were confirmed absent from the JAR. Logs: .tools/phase9-poll-verify.log and .tools/phase9-poll-smoke.log. Thirteen tests added in this increment. No schema installation, recurring poll activation or Discord send occurred.


AUD-05 follow-up — 2026-09-21: poll admission rechecks remaining lease/shutdown after pressure callbacks; malformed rows still participate in duplicate-ID detection; unused provider fields are skipped; provisioning rejects unversioned polling state rather than overwriting it. Five regressions added; 289 tests and packaged smoke passed. DISC-01/DISC-02 and migration checksums remain unchanged; see AUDIT_REPORT.md.

