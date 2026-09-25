# BunnyHub

Context refresh — 2026-09-19: AUD-04 continued the audit with transport guards, safer exception diagnostics and bounded autocomplete formatting; 232 tests and packaged smoke passed. Read [current status](docs/PROJECT_STATUS.md), [progress report](docs/PROGRESS_REPORT.md) and [audit report](docs/AUDIT_REPORT.md). Phase 8 commands remain next; phase 6 remains deferred and required before activation. Earlier measurements below are historical.

## BunnyHub handler 2.0.0

The handler now has immutable startup settings and route snapshots, duplicate-route validation, ownership-safe monotonic cooldowns, shared component discovery, explicit stale-control replies, and configurable shutdown hooks. Bot version and artifact naming remain unchanged. See [the 2.0 migration notes](docs/BUNNYHUB_2.md) for API changes and limitations.


Java 21 Discord bot for study sessions, semesters, academic records, and GPA tracking.

The planned main product is automatic free-game alerts for approximately **1,000 Discord servers** at launch. Servers will choose stores (Epic Games, Steam, GOG, etc.), destination channels and roles. The engine, Mongo adapters, configuration backend, and ownership/scheduling foundation are implemented and locally tested, but inactive. **Next is phase 8: server setup commands**, with the current source audit recorded in the reports above. Review the new command integration again after that stage. Real database integration tests (phase 6) are deferred, not waived. Start with [current project status](docs/PROJECT_STATUS.md) and [the implementation checklist](docs/IMPLEMENTATION_TODO.md).

## System design

The [system architecture blueprint](docs/SYSTEM_ARCHITECTURE.md) defines module boundaries, identity, durability, security, capacity and recovery. Implemented backend foundations are documented separately; discovery, real transport and operational release work remain pending. No alert feature has been activated.

Source/API research, optional deals digests, and manual-send workflows are in [the discovery and publishing design](docs/ALERT_SOURCES_AND_FEATURES.md).

## Build and run

Install JDK 21 or newer and set `JAVA_HOME`. The Maven wrapper downloads Maven 3.9.16 on first use.

```powershell
.\mvnw.cmd clean verify
java -jar target/BunnyHub-4.00.jar
```

On macOS/Linux use `./mvnw clean verify`. The Windows launcher `start_server.bat` resolves its own directory. Run `stop` or `exit` in the bot console for shutdown.

If the current JAR is running and locked on Windows, build separately with `./mvnw clean verify -Dbunny.build.directory=target-upgrade` (use `mvnw.cmd` on Windows). Stop the old process before launching the new JAR. The upgrade validation uses this separate directory and does not restart the running bot.

Copy `.env.example` to `.env` in the working directory and set `TOKEN` and `MongoURI`, or supply those environment variables directly. Existing development configuration in `src/main/resources/.env` is supported as a fallback when a root `.env` is absent. Neither location is included in new JARs. Logging is configured through `src/main/resources/logback.xml`; the unused `application.yml` was removed.

MongoDB must be a replica set or sharded cluster supporting transactions (for example, an Atlas cluster). Standalone MongoDB is rejected at startup. The database name remains `GBF` for compatibility. The bot creates unique indexes on `BunnyUsers.userID` and `TimerData.account.userID`; if existing duplicate accounts prevent index creation, startup stops without deleting or merging records. Resolve those duplicates before restarting.

The application supports slash commands, user context menus, and leading bot mentions, using ordinary guild/direct-message intents. No privileged Message Content or Guild Members intent is required. Developer and test-guild IDs remain configured in `Main`.

## Handler migration (2026-09-12)

BunnyNexus now uses the newer BunnyHub command framework from the local BeastarsBot project. Commands share `CommandContext`, with `SlashContext` and `MentionContext` entry points, aliases, subcommand groups, default mention subcommands, and a shared permission/cooldown gate. The unused separate prefix-command registry was removed.

Try `@BunnyNexus avatar`, `@BunnyNexus av`, or `@BunnyNexus pfp show_both:true size:4096`. Replace the bot name with a real mention. Mention options support `name:value`, quoted values, and positional arguments. Mention replies are public; use `/avatar ephemeral:true` for a private response. Timer commands remain slash-only because they depend on interaction hooks and interactive study menus; mentioning them points to the slash command.

Buttons, modals, and string selects use the newer routers. Button/select handlers can declare their own cooldowns. Session buttons and semester submissions declare that they need an edit acknowledgement before work is scheduled; buttons opening modals do not defer. Timer and avatar slash commands acknowledge before entering the worker queue. Autocomplete retains the existing null, length, duplicate, and 25-choice safeguards.

The worker pool is configurable through `setCommandPool(workers, queueCapacity)` (24 workers plus 100 additional admission slots in `Main`, at most 124 unfinished tasks). Per-user FIFO queues preserve timer safety without occupying workers waiting on user locks. Ready users take turns, and idle queues are removed. Autocomplete uses a separate bounded pool configured with `setAutocompletePool` (default 4 workers plus 32 slots). Workload snapshots expose activity, queue age, rejection, and completion metrics. See the backend review for the limits of this isolation.

Mongo's pool size includes command and autocomplete workers plus eight connections per server. `setDatabaseTimeout(Duration)` sets a finite driver operation budget (default 10 seconds), with bounded connection, selection, and checkout waits. These explicit budgets override corresponding URI timeouts; deployments with long-running index builds or higher latency should review the settings. `setDatabaseName` and `setMongoUriKey` preserve this deployment's existing `GBF` database and `MongoURI` key.

Beastars's domain services, custom admin-role policies, audit service, and application-emoji rewriting were not imported. The shared admin gate uses Discord's Administrator permission. BunnyNexus keeps its own styling, shutdown cleanup, transactional persistence and revision checks. JDA stays on 6.6.0; no dependencies were downgraded to Beastars's older versions. Caffeine 3.2.4 was added for the new handler's bounded cooldown caches and cache metrics.

## Dependency refresh

Stable releases verified against Maven Central on 2026-09-10:

| Dependency | Version |
| --- | --- |
| JDA | 6.6.0 |
| dotenv-java | 3.2.0 |
| MongoDB sync driver | 5.11.0 |
| Logback | 1.6.3 |
| SLF4J | 2.0.19 |
| Reflections | 0.10.2 (latest published) |
| jfiglet | 0.0.9 (latest published) |
| JUnit Jupiter, tests only | 6.1.3 |
| Mockito, tests only | 5.23.0 |

Caffeine 3.2.4 was added on 2026-09-12 to match the newer Beastars handler.

Maven plugins are pinned to stable versions. Maven 4 and compiler-plugin 4 prereleases were intentionally excluded. JDA's compatible transitive dependencies are resolved through its published POM rather than independently overriding them.

JDA 6 migration covers component packages, explicit action rows, component-tree queries, command interaction contexts, and modal `Label` components. See the official [JDA 6 migration notes](https://github.com/discord-jda/JDA/releases/tag/v6.0.0) and [6.6.0 release](https://github.com/discord-jda/JDA/releases/tag/v6.6.0). This bot has no voice features; no DAVE integration is required for its current functionality.

## Structure and behavior

### User and server information

`/info user` shows your profile; `/info user user:@someone` looks up another account. Cards include account creation, badges, profile/avatar links, and available server membership details such as join date, roles, boosting, display colour, and key permissions. Missing membership data is retrieved asynchronously; nonmembers and DM lookups receive an account-only card.

`/info server` shows the current server's owner, creation date, member count, roles, channels, boosts, verification, age restriction, language, features, and available artwork. It explains that a server is required if used in DMs. Neither lookup needs privileged member or presence intents.

Mention equivalents include `@BunnyNexus info user user:@someone` and `@BunnyNexus info server`. `whois`/`about` alias `info`, `member` aliases `user`, and `guild` aliases `server`, matching Beastars. These commands register automatically when the updated bot starts.

### Course editing and avatars

Right-click a user (or open their user actions on mobile), then choose **Apps → Avatar**. This is a Discord **USER** context-menu command. It shows the selected user's server and global avatars at 2048px, with the server avatar first when available, and replies privately. Global fallback and original/PNG download links work as in `/avatar`. `/avatar` and its mention aliases remain available for choosing resolution, ordering, and visibility. Both entry points share the same implementation and caller cooldown.

`/timer remove-subject` and `/timer update-subject` suggest course codes from the selected destination. Codes containing hyphens and older records with surrounding whitespace are handled correctly. Academic-record removal does not require an active semester or a timer document.

Use `/timer update-subject destination:ACCOUNT code:CS-101 grade:A credits:4` to update a retake grade. Optional fields are `new-code`, `name`, `credits`, `grade`, `marks-lost`, and `clear-grade`. Only supplied details change; study time and study counts remain intact. A replacement grade updates the existing course rather than adding a second attempt, so cumulative GPA uses its current grade and credit hours. Changes affect only the selected destination, not archived semesters or a second copy of the course. Course-code changes are blocked during an active semester session. `clear-grade:true` removes the grade; it cannot be combined with `grade`.

`/avatar` supports `user`, `priority`, `show_both`, `size`, and `ephemeral`. Resolution defaults to 2048 and can be selected up to 4096. Both avatars are displayed at full size when requested, in the selected priority order. Download buttons provide animated originals (when present) and PNG images. Server-avatar requests fall back explicitly to the global avatar in DMs or when no server avatar is available. A missing member in the interaction payload is retrieved asynchronously after acknowledgment.

- `handler`: application lifecycle, bounded background interaction execution, command discovery/routing, and persistence.
- `commands`: thin registration classes for `Avatar`, `Timer`, and `Info`. They declare options and connect named implementation classes; Avatar registers both slash and USER command definitions.
- `bunnynexus/commands/info`: `AvatarCommand`, `UserInfo`, and `ServerInfo` implementations.
- `bunnynexus/commands/timer`: one named class per timer action: `Stats`, `Register`, `Gpa`, `AddSubject`, `RemoveSubject`, `UpdateSubject`, `Start`, `SwitchSubject`, and `EndSemester`.
- `buttons`, `modals`: Discord component entry points. Timer commands defer their responses before database work; session buttons and semester submissions defer edits.
- `bunnynexus/timers/services`: timer, subject, and semester mutations. `Timers` builds the Discord presentation. The former `nexus` package was renamed to `bunnynexus`; database model packages and stored documents are unchanged.
- `database/models`: existing MongoDB documents and their optional revision fields.
- `src/test`: regression tests for progression, legacy BSON decoding, persistence calls, JDA payloads, concurrent work, and menu ownership.

Writes to existing account/timer documents compare a nullable `revision` field and reject stale updates. Existing records acquire a revision on their next successful write. Session completion and semester archival update both documents in one transaction. New accounts use insert-only upserts to recover a partial registration without replacing existing history.

Every newly archived semester is saved in `SemesterHistory`; the longest-semester snapshot is also retained. Existing semesters that were already discarded by the older implementation cannot be reconstructed. Lifetime study time is counted when a session finishes, not again at archival. Account RP now uses BSON int64; older BSON int32 values remain readable.

Course codes containing hyphens, such as `CS-101`, are supported. Autocomplete labels separate code and name with ` - `. New courses validate code/name lengths and credit hours. Streaks use UTC calendar days. Statistics show up to 12 courses to respect Discord embed limits; the GPA menu lists all courses.

Pending sessions and GPA menus remain in memory and expire automatically. Pending menus do not survive a restart; active timers are persisted and their message IDs remain valid. Old menus cannot operate a replacement session. Only one bot process should own the same Discord application; optimistic writes protect data conflicts but do not coordinate in-memory menus across processes.

## Validation limits

Automated tests do not log in to Discord or connect to a real MongoDB database. They exercise actual JDA serialization and BSON codecs, plus mocked database/interaction boundaries. A deployment smoke test should cover registering, starting, pausing, resuming, switching courses, stopping, and archiving on the intended Discord server and MongoDB cluster.

The source directory supplied for this update has no Git metadata. No commit or deployment was made.
