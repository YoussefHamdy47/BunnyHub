# Owner-controlled publication — NEXT-10 / OWNER-01

2026-09-25. The user requested stronger manual verification by the developer/owner before sending to many people. This decision supersedes the single operator-approval launch model in OPS-01. Automatic discovery remains; publication is owner-controlled. The implementation is inactive and does not register commands, connect to Discord or migrate the live database.

## Chosen flow

1. Discovery finds candidates. The planned private owner inbox shows source evidence and a preview; no subscription is notified just because a source says a game is free.
2. The configured bot owner prepares the exact offer/content preview. It has a 15-minute confirmation window. This step disables any older release for the same canonical offer.
3. The owner explicitly **verifies** it. This records VERIFIED, which still cannot send.
4. The owner separately chooses **release**, supplying the reviewed revision and a positive maximum number of send attempts. There is no default “all servers” or default limit. A release expires after one hour or at the offer end, whichever comes first.
5. The owner can **revoke**. New send authorization then stops. A Discord request authorized before revocation may still arrive; database revocation cannot retract an external request already in flight.

Only one explicitly configured owner user ID may read or mutate this repository. Guild Administrator/Manage Server and the earlier multi-operator allowlist do not confer release rights. No ID was inferred for the real user; future composition must inject the verified deployment owner ID. The existing no-owner MongoDeliveryRepository constructor now fails closed for send authorization, while retaining queue/recovery/receipt operations. There is no production default owner, developer bypass or automatic approval mode.

The release limit counts **send authorizations, including retries**, not distinct servers or guaranteed deliveries. This is intentionally conservative: 100 authorizations permit at most 100 send attempts and may produce fewer successful alerts. Failed preflight consumes no slot. A transaction that rolls back also rolls back its reservation. Unknown commits must be reconciled, not blindly repeated. Once exhausted or expired, a fresh owner prepare → verify → release is needed to authorize more work.

The recipient scope remains current matching, opted-in destinations, subject to this cap. It is not a frozen exact recipient list. The future release UI must show that scope, estimated guild/channel counts, the explicit cap, expiry and configured role behavior before confirmation. A fixed recipient-list or test-guild release needs its own audience-plan implementation; neither is claimed here. No test preview or public Discord message has been sent.

## Implemented contracts

`OwnerReviewRepository` exposes prepare, verify, release, revoke and owner-only get. `MongoOwnerReviewRepository` persists bounded offer/content snapshots, material fingerprint, template version, owner, state/revision, preview/release deadlines and used/maximum attempt counts. Commands are not yet wired; actor IDs must come from verified interactions when they are.

Each operation takes an interaction/action ID and an expected revision. A separate audit record is committed with the state change. Retrying the same action returns its receipt; reusing the action ID with different parameters reports conflict. Late/stale revisions do not overwrite state. No external side effect occurs inside the retryable transaction. An uncertain commit propagates as uncertainty, not success.

The owner-reviewed snapshot must still satisfy the configured FreeGamePolicy at verification and release. Unknown end/price/market/entitlement/link evidence cannot be approved by this path. Stale evidence requires a fresh prepared preview. The repository rechecks the database-time confirmation/evidence deadline at its final write. A stage called “verified” is not provider certification: retained proof flags must still come from a separately certified mapping/evidence process.

`MongoDeliveryRepository.authorize` now requires a current RELEASED owner record after the ordinary configuration/offer/permission checks and before persisting SENDING. It checks:

- configured owner identity, canonical offer and matching material fingerprint;
- the material recomputed from the current offer and stored reviewed display content, independently of the payload's fingerprint;
- renderer template version, release start/end, current eligibility and existing payload identity/revision/role checks;
- an unexhausted explicit attempt budget.

The release document is conditionally updated in the same transaction as the attempt and SENDING job. Revocation and preparing a new preview write that same guard. The attempt deadline is capped by releaseUntil in addition to offer freshness/expiry, permission proof, runtime lease and job lease. The final DB-time job fence prevents committing an authorization after its capped deadline. Missing/legacy/unverified/revoked/expired/changed/exhausted releases return OWNER_RELEASE_REQUIRED; no send attempt is inserted.

PreparedPayload now includes `reviewMaterialHash`, generated by AlertMessageRenderer from the actual offer/display pair. Existing serialized Discord messages and existing persisted DeliveryJob.Attempt fields remain unchanged. The new hash is a review fingerprint, not a new notification identity. The pure AutomaticSendEngine still expresses its narrower rule; only the durable repository result can authorize transport, and that repository now enforces the owner gate.

## Schema and upgrade — OWNER-01

Explicit additive migration: `alert-owner-review-v1`.

| Collection | Purpose / indexes |
| --- | --- |
| AlertOwnerReviews | One current review per canonical offer ID; owner/state/ID listing index; no TTL |
| AlertOwnerReviewAudit | Unique action ID through `_id`; unique reviewId/revision audit index; no TTL |

The installer requires the catalog schema, installs only the new indexes and checksum marker, and verifies on repeat calls without resetting reviews or budgets. Existing catalog/base/runtime/configuration/polling migration checksums are unchanged. Existing offers/events/plans/jobs are not automatically approved. Missing review records never count as approval. Composition must verify the new schema before starting writers; constructors do not silently install or repair it.

Deployment must stop all old send writers before installing and starting the new version. Older binaries do not know about this gate and cannot overlap or be rolled back into active sending. Rollback keeps new records and delivery paused; do not delete audits/counters or activate an old writer to bypass the gate. Changing the configured owner invalidates prior-owner releases; transferring old review ownership requires a separately audited explicit operation, not automatic takeover.

These changes preserve canonical event/channel uniqueness and uncertain-outcome quarantine. An owner release is additional authority, not permission to reset SENT/UNCERTAIN records, broaden role mentions or bypass guild configuration. Only disposable local replica-set databases are used for this increment's migration tests.

## What remains

- Private owner inbox/listing, exact rendered preview and named verify/release/revoke commands, with verified interaction IDs, clear audience scope and a second release confirmation.
- Provider candidate ingestion and source certification; durable display content is currently retained in the review snapshot, not yet atomically integrated with catalog ingestion.
- Owner review gate before event/fan-out creation. This increment enforces the mandatory **send** gate; unapproved jobs could still be planned by existing inactive foundations and would be blocked at authorization. Composition should defer that fan-out to avoid needless backlog.
- An audited global pause control and owner transfer; fixed-audience test sends; real transport/composition and explicit activation.
- Full backup/restore, network-partition and lost-acknowledgement matrix. Local database tests do not establish Discord delivery capacity.

## Verification

JDK 21 clean verify passed **334 default tests** (`.tools/next10-verify.log`); **16 real isolated three-node MongoDB tests** passed (`.tools/next10-mongo-final.log`); packaged smoke passed (`.tools/next10-smoke.log`). Driver-boundary and real isolated Mongo tests cover the owner-only boundary, distinct verify/release, content round-trip, expiry, stale evidence, action replay/conflict, new-preview/revoke behavior, restart via reconstructed repository, atomic cap contention/rollback, and revocation versus authorization. These are correctness checks, not a Discord throughput benchmark. No live provider call, GBF access, bot restart, production migration or Discord send is part of this increment.
