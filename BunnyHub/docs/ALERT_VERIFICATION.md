# Alert release verification — VERIFY-01

Latest increment — 2026-09-25 (NEXT-10 / OWNER-01): owner-only prepare → verify → explicit bounded release is now persisted, audited and enforced transactionally at send authorization. Verification alone cannot publish; releases expire within one hour, count retries, bind reviewed content/template and can be revoked. **334 default tests, 16 real isolated three-node MongoDB tests and packaged smoke passed** (.tools/next10-verify.log, .tools/next10-mongo-final.log, .tools/next10-smoke.log). New additive owner-review migration tested only in disposable databases; old send writers must stop before upgrade. Private owner commands, certified ingestion, pre-fan-out gate and transport/composition remain pending. No live migration, bot restart or Discord send. See [owner-controlled publication](ALERT_OWNER_REVIEW.md).

Started 2026-09-22; continued 2026-09-23. The user explicitly resumed the previously deferred phase-6 database verification. This report distinguishes real local MongoDB evidence, synthetic scheduling measurements, and provider response evidence. None authorizes live alert activation.

Final evidence: 12 real MongoDB integration tests passed (`.tools/mongo-replica-final.log`); two synthetic delivery-load tests passed (`.tools/delivery-load.log`); the final JDK 21 `clean verify` passed 301 default tests (`.tools/verification-final.log`); packaged smoke and fixture/test-class exclusions passed (`.tools/verification-smoke.log`). All test MongoDB processes stopped. The default suite total includes other existing workspace coverage as well as four provider regressions added here.

## Isolated database tests

`LocalReplicaSet` starts three workspace-owned MongoDB 8.0.32 processes, each bound to 127.0.0.1 on a temporary port with its own data directory, a 256 MiB WiredTiger cache and 64 MiB oplog. Before initiating the set, it verifies the connected server's dbpath and unique replica-set name. The fixture accepts an explicit executable path, never a Mongo URI, bot configuration or environment credentials. Each test uses a fresh `bunny_it_*` database. All schema installation and corruption/fault injection affect only these synthetic databases. Process handles created by the fixture are stopped on teardown; diagnostic data remains under `.tools/replica-tests`.

The opt-in `MongoReplicaSetIT` scenarios cover:

- Repeatable migration installation without resetting capacity; missing-index detection.
- Sixteen competing source owners, begin attempts and finish attempts; exactly one winner at each boundary, with persisted pause and stale-owner rejection.
- Sixteen competing delivery claim/recovery workers; a replacement generation fences stale recovery.
- Simultaneous configuration edits at the same revision; exactly one commits with matching audit/guard revisions.
- Server-injected transient transaction failure and an error labelled `UnknownTransactionCommitResult`; driver retries leave one configuration/audit. The latter injects an error before the commit command runs, so it does not prove recovery from every lost acknowledgement after a successful commit.
- Eight competing send authorizations and receipts; exactly one frozen attempt/receipt commits and the backlog decrements once.
- Expired SENDING recovery to UNCERTAIN; no blind reclaim, stale accepted callback rejected, unresolved work remains counted.
- Disable-versus-authorization contention, followed by a guaranteed post-disable authorization rejection. Already committed authorization may precede disable, as designed.
- Concurrent replay of a fan-out page containing an existing event/channel job; no duplicate insertion/counter increment and correct claim for the next page.
- A 20,000-job mixed READY/LEASED collection and an actual due-page execution plan.
- Forced primary-process termination after a majority-committed configuration; the surviving replica set retains it and accepts a subsequent edit.

The first seven scenarios passed in `.tools/mongo-replica-it.log`. The initial expanded run caught two test-fixture errors (duplicate canonical event construction and missing next-page claim), corrected without weakening production invariants. Final expanded evidence is `.tools/mongo-replica-final.log`.

The MongoDB binary came from the [official release manifest](https://downloads.mongodb.org/current.json) and its official HTTPS ZIP. The archive includes roughly 807 MB including debug symbols; only complete required entries were extracted from the downloaded prefix. Server size/CRC32 were checked against a separately retrieved HTTPS central directory: 77,725,696 bytes / 3507607929. Executable SHA-256 is `38f8e6dfbc496ae4089f15b8235f15287adfbd8c09eafa4f384a2e5361625522`. This executable is unsigned; the complete archive SHA-256 was **not** verified. See `.tools/mongodb-binary-verification.log`. This is a local test tool, not a reviewed production MongoDB installation.

## Synthetic delivery and interactive load

`DeliveryLoadIT` uses the production DeliveryScheduler, RuntimeOwnership, ScheduledDelivery, SendAdmission and InteractionExecutor. Queue/storage and authorization results are synthetic in-memory adapters; a four-thread callback pool simulates 2 ms transport completion. There are 1,000 guilds, 2,000 destination channels, 32 in-flight slots and a 128-entry candidate window. Each scenario also submits 10,000 simple interactive command actions. Budgets here are test inputs, not approved deployment settings.

Measured on this workstation in `.tools/delivery-load.log`:

| Jobs | Wall time | Maximum in-flight / queued transport | Maximum candidate hints | JVM heap peak | Command queue p95 / max |
| --- | --- | --- | --- | --- | --- |
| 6,000 | 4.999 s | 32 / 32 | 118 | 158.3 MiB | 0.234 / 1.728 ms |
| 20,000 | 16.869 s | 32 / 32 | 110 | 151.5 MiB | 0.598 / 6.698 ms |

All 26,000 synthetic jobs completed exactly once. One percent ended UNCERTAIN and one percent permanently rejected; neither was blindly retried. Tests assert no simultaneous transport for the same channel, no double submission, both receipt/transport callback orders, complete permit release and no lost interactive actions. Heap peaks include JVM/test overhead and are order-dependent. These are single-run diagnostic measurements, not a Discord or database-backed delivery throughput result, latency SLO, network benchmark or leak certification.

The separate real MongoDB 20,000-job due-page check returned 25 rows after examining 25 documents and 25 index keys (initial expanded run: 1 ms reported server execution). Only that tested query/data shape is covered; complete fan-out, recovery and mixed-workload explain-plan coverage remains open.

## Provider response certification increment

Re-read [GamerPower's official API documentation](https://www.gamerpower.com/api-read) on 2026-09-22. It documents HTTP 201 for no active giveaways and HTTP 404 for not found. Access remains unauthenticated, attribution is required, and requests must remain below the documented limit. Three explicit, paced read-only captures retained raw bodies, UTC timestamps, endpoint URLs and SHA-256 hashes under `src/test/resources/gamerpower`:

| Endpoint scope | Observed response | Evidence |
| --- | --- | --- |
| PS4 full games | HTTP 201; 100-byte no-results object | `ps4-games-2026-09-22.json` and adjacent metadata |
| iOS full games | HTTP 200; one listing, 1,029 bytes | `ios-games-2026-09-22.json` and adjacent metadata |
| Missing giveaway ID 2147483647 | HTTP 404; 62-byte error object | `missing-id-2026-09-22.json` and adjacent metadata |

DISC-03: intake accepts only HTTP 201 with the captured exact two-field no-results envelope (`status` integer zero and exact `status_message`). Duplicate/missing/extra fields, changed message/status/type, trailing input or malformed/oversized bodies fail closed. The fetcher subjects 201 to the same media, encoding, size and deadline guards as 200. This fixes false failure/backoff for a documented empty response; it does not authorize withdrawals, certify global completeness or advance an ingestion cursor. HTTP 404 remains a failure. Tests use saved fixtures, never live provider requests.

This extends structural response certification only. The mobile fixture itself lists multiple platforms; platform labels do not establish a specific market, canonical edition or claim entitlement. Timezone, region, claim conditions, recurrence/source ordering, canonical campaign mapping, genuine rate-limit/server-error captures and storefront eligibility evidence remain unresolved. No attempt was made to induce rate limiting or a server error.

## Reproduction and remaining gates

Use JDK 21 and the local Maven cache. Normal `clean verify` retains fast offline unit tests; IT classes are deliberately opt-in and are not silently counted as default unit coverage.

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.10'
& ./.tools/apache-maven-3.9.16/bin/mvn.cmd '-Dmaven.repo.local=C:/BunnyHub/.tools/repository' '-Dbunny.build.directory=target-upgrade' '-Dtest=MongoReplicaSetIT' '-Dbunny.test.mongod=C:/BunnyHub/.tools/mongodb-8.0.32/bin/mongod.exe' -o -B -ntp test
& ./.tools/apache-maven-3.9.16/bin/mvn.cmd '-Dmaven.repo.local=C:/BunnyHub/.tools/repository' '-Dbunny.build.directory=target-upgrade' '-Dtest=DeliveryLoadIT' -o -B -ntp test
```

Still required: observation/catalog conflict and rollback scenarios, missing-job fan-out atomicity and capacity races, delete/recreate races, interrupted migrations, post-commit lost-acknowledgement and network-partition testing, recovery/restore drills and broader index plans. The integration suite is substantial phase-6 progress, not completion of every database release gate.

Real Discord validation also remains open: a production transport/executor composition, exact rendered payload/mention checks, permission changes, 429 handling, proven cancellation and remote-acceptance/receipt uncertainty need a controlled opt-in destination and explicit send authorization. No live bot restart, GBF access, production provisioning, recurring polling or Discord send occurred in this increment.
