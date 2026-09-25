# Freebie system review — AUD-07

Reviewed 2026-09-23 at the user's request. This is a review, not an implementation or activation increment. Production source, persisted formats, migrations and live processes were not changed. Findings below remain open.

Follow-up — NEXT-09, 2026-09-25: the subsequent implementation fixes the payload identity gap with mandatory identities and regressions, corrects the nonce guidance and refreshes the architecture inventory. The findings below describe the original reviewed state. See [implementation and selected operations](ALERT_OPERATIONS.md); no alert activation or schema change was made. The standalone audit probe has been updated to assert the repaired behavior.

## Confirmed findings

### P1 — Prepared payload loses offer/subscription identity before authorization

Location: `AutomaticSendEngine.PreparedPayload` and `authorize`, with `AlertMessageRenderer.render` as the producer and `MongoDeliveryRepository.authorize` as the durable caller.

The renderer checks that display content belongs to the supplied offer. However, its returned PreparedPayload retains only content revision, subscription revision, roles, template, nonce, attempt ID and payload hash. It does not retain the offer key, subscription ID or destination. Authorization compares revision numbers and roles against the current job's offer/subscription without comparing their identities. Those revision numbers are local to each record, not globally unique.

An offline reproduction rendered game A for subscription A, then supplied its prepared payload to a valid job for game B/subscription B, with both content/subscription revisions set to 1 and empty roles. Authorization returned Sending and froze the hash of game A's message for game B's job. The two subscriptions also used different destination channels. The Mongo adapter has no additional payload identity check; it compares the permission proof's destination to the job and its roles to the payload.

Impact: an accidental payload association or cache-key error in the future delivery composition can authorize the wrong game's title, link and deadline while checking eligibility for another game. The hash freezes the wrong bytes rather than detecting the mismatch. This is a missing integrity check at an authorization boundary, not evidence of an exposed remote exploit in the inactive application.

Recommended fix: carry the offer key, subscription ID and destination/incarnation in PreparedPayload, compare them to the current job/current records, and return REFRESH_PAYLOAD for mismatches. Add cross-offer and cross-subscription regressions with equal revision numbers and equal roles. Review attempt persistence compatibility if these identities are also added to the persisted attempt; do not silently alter schema checksums.

### P2 — Rendering instructions contradict the retry nonce invariant

Location: `docs/ALERT_RENDERING.md`, FrozenMessage contract; `DeliveryJob.authorize`.

The rendering guide instructs callers to supply distinct attempt/nonces. DeliveryJob explicitly requires a new attempt ID and the same per-job nonce on subsequent attempts. Following the guide after a proven retryable rejection throws IllegalArgumentException. A second reproduction confirmed the failure, and a control using a new attempt ID with the original nonce authorized successfully.

Recommended fix: document one stable nonce per delivery job and a distinct ID per attempt; ensure the future transport/composition reloads the nonce from durable attempt state on retry. Clarify uniqueness across different jobs separately. The existing state-machine rule should not be weakened merely to match the contradictory sentence.

### P3 — Architecture implementation inventory is stale

Location: `docs/SYSTEM_ARCHITECTURE.md`, section 17, paragraph beginning “Not implemented”.

The paragraph says configuration commands and discovery adapters are not implemented, although FreebieActions/the named subcommands, GamerPowerIntake, GamerPowerFetcher and GamerPowerPoller now exist. Other handoff sections correctly describe these as implemented but inactive. The same section describes real database verification as deferred, whereas VERIFY-01 resumed a limited real replica-set matrix.

Recommended fix: distinguish existing inactive components from missing certified ingestion, real transport/composition and activation, and link to ALERT_VERIFICATION.md for the remaining database matrix. Do not imply that the limited completed verification finishes every release gate.

## Scope and verification

Source review covered message/URL/mention preparation; domain eligibility, identities and delivery transitions; command parsing/status and administrator access; configuration policy/proofs/transactions; observation and fan-out transactions; delivery authorization/outcomes; ownership, scheduling and local admission; GamerPower intake, pinned HTTP fetching and durable shadow polling. This was risk-focused reading, not exhaustive execution of every branch or a formal security certification.

- JDK 21 offline `clean verify` using the documented Maven runtime/cache and target-upgrade: **310 tests passed, zero failures/errors/skips**. Log: `.tools/freebie-audit-verify.log`.
- Packaged artifact smoke passed, including command discovery, payloads, codecs and secret/test exclusions. Log: `.tools/freebie-audit-smoke.log`.
- `.tools/FreebieAuditProbe.java` reproduces the first two findings against the freshly built artifact. Log: `.tools/freebie-audit-probe.log`. These are standalone audit demonstrations, not additional default-suite regressions or fixes.
- No production database, provider request, bot restart, Discord send or schema installation was performed. Real replica-set/load suites were not rerun; their earlier evidence remains scoped to VERIFY-01.
- No current dependency-advisory scan, provider terms/entitlement certification, live Discord permission test or deployment security review was performed. The source review does not establish their safety.

The existing bounded fetching, explicit role allowlists, administrator checks and uncertain-outcome handling are useful safeguards. They do not resolve the payload identity finding or the previously documented release gates.
