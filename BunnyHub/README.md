# BunnyNexus

A Discord bot for study tracking, free-game alerts and server moderation, built on the **BunnyHub** command
framework. Java 21, JDA 6 and MongoDB.

## Commands

Run `/help` in Discord for the full, always-current list with options and examples. `/help command:<name>`
jumps straight to one command.

### Study

| Command | What it does |
| --- | --- |
| `/timer register` | Start a semester and begin tracking study time |
| `/timer start` | Start a study session for a course, with pause, resume and stop controls |
| `/timer switch` | Switch course without stopping the timer |
| `/timer end-session` | End a session even if its menu is gone |
| `/timer stats` | Study statistics, streaks and progress |
| `/timer gpa` | Academic record and cumulative GPA |
| `/timer add-subject`, `remove-subject`, `update-subject` | Manage courses and grades (retakes keep study history) |
| `/timer end_semester` | Archive the current semester |

### Free games

| Command | Who | What it does |
| --- | --- | --- |
| `/free-games list` | Everyone | Games that are free right now, optionally for one launcher |
| `/free-games dms` | Everyone | Get free games by DM for chosen sections, or unsubscribe |
| `/free-games setup` | Manage Server | The server dashboard (see below) |
| `/free-games help` | Everyone | How the free-game system works |

**Server setup** (`/free-games setup`):

- **Quick setup:** pick a channel, what to post and an optional ping role in one form.
- **Create channels for me:** creates a channel per section (or one shared channel), with a ping role each.
- **Sections:** Major PC, Indie & other PC, Console and Mobile. Each has its own channel, launchers, ping role
  and optional ending-soon reminder.
- **Launcher pings:** give each launcher its own role, for example @Epic for Epic Games Store only.
- **Post role picker:** buttons that let members opt in to the ping roles themselves.

**Where games come from:**

- Epic Games Store is read from Epic's own promotions feed, and everything else from GamerPower.
- GamerPower claim links are resolved to the store page where possible, so the claim button opens Steam, GOG
  and so on directly. If a link can't be resolved, the GamerPower link is kept and the button still works.
- Launchers on the auto-send list (Epic, Steam and GOG by default) are posted as soon as they are found.
  Everything else waits for an owner to approve it in the private review channel.

### Moderation

`/moderation ban | unban | kick | mute | unmute`. Mute uses Discord's built-in timeout, from 1 minute to 28 days.

- Shown to members with **Timeout Members**. Each action checks its own permission (Ban, Kick or Timeout
  Members).
- Refusals are private and explain why, for example a role equal to or above yours, the server owner, or an
  administrator (who can't be muted).
- Successful actions are confirmed in the channel, and the audit log records which moderator used them.

### Information

| Command | What it does |
| --- | --- |
| `/info user`, `/info server` | Account, membership and server details |
| `/avatar` | Global and server avatars, up to 4096px, with download links. Also under **Apps → Avatar** when you right-click a user |
| `/ping` | Latency to Discord (gateway and API) |
| `/uptime` | When the bot came online and how long it has been up |
| `/help` | Every command, grouped by category |

Most commands also work by mentioning the bot, for example `@BunnyNexus avatar` or `@BunnyNexus info server`.
Timer, moderation and free-game setup commands are slash-only.

### Owner only

`/freebie-admin status | pause | resume | check | sources | history | retry`: health, pausing sends, forcing a
source check, resolving duplicate offers between sources, and delivery history and retries. Only the IDs in
`FREEBIE_OWNER_IDS` can use it.

## Running it

You need **JDK 21** and a **MongoDB replica set or sharded cluster** (transactions are required; for example
MongoDB Atlas). A standalone MongoDB is refused at startup.

```bash
cp .env.example .env      # then fill in TOKEN and MongoURI
./mvnw clean verify       # Windows: .\mvnw.cmd clean verify
java -jar target/BunnyHub-4.00.jar
```

On Windows, `start_server.bat` does the same. Type `stop` or `exit` in the console to shut down cleanly.

The bot uses only the standard guild and direct-message intents. No privileged intent (Message Content, Server
Members or Presence) has to be enabled.

### Configuration

Settings come from environment variables or a `.env` file in the working directory. Credentials are never
packaged into the jar.

| Variable | Required | Meaning |
| --- | --- | --- |
| `TOKEN` | Yes | Discord bot token |
| `MongoURI` | Yes | MongoDB connection string |
| `MONGO_DB_NAME` | No | Database name (default `GBF`) |
| `FREEBIE_REVIEW_CHANNEL_ID` | For free games | Private channel where new giveaways are reviewed |
| `FREEBIE_OWNER_IDS` | For free games | Comma-separated user IDs allowed to approve, reject and stop alerts |
| `FREEBIE_OTHER_REVIEW_CHANNEL_ID` | No | Separate review channel for non-major sections (defaults to the main one) |
| `FREEBIE_AUTO_SEND` | No | Launchers posted without review: comma-separated ids, default `epic,steam,gog`, or `none` |
| `FREEBIE_POLL_MINUTES` | No | GamerPower poll interval, 5–120 (default 10) |
| `FREEBIE_RETENTION_DAYS` | No | How long finished alerts are kept, 30–3650 (default 90) |
| `FREEBIE_DM_MAX_SUBSCRIBERS` | No | Cap on personal DM subscribers, 0–100000 (default 100; 0 turns DMs off) |
| `FREEBIE_ENABLED` | No | `false` turns free-game alerts off entirely |

Launcher ids for `FREEBIE_AUTO_SEND`: `epic`, `steam`, `gog`, `ubisoft`, `ea`, `battlenet`, `itchio`,
`drm-free`, `playstation`, `xbox`, `switch`, `android`, `ios`, `other`.

Developer and test-server IDs are set in `Main.java`.

### Test instance

`start_test_server.bat` starts a second, separate bot for live testing. It reads `.env.test` instead of `.env`
and must use its own bot token and its own database (`MONGO_DB_NAME`). It refuses to start against the
production `GBF` database or with the production token.

## Deployment

Production runs on Railway from the `Dockerfile`, deploying automatically when `main` changes. The image builds
the jar with Maven and runs it on a slim Java 21 runtime.

Memory is capped in `JAVA_OPTS`: a 384 MB heap with the Serial garbage collector, which hands freed memory back.
If the bot ever runs out of memory it exits, and Railway's restart policy brings it back. In normal use it sits
around 300 MB.

## Project layout

| Path (under `src/main/java/org/bunnys`) | Contents |
| --- | --- |
| `handler/` | The BunnyHub framework: lifecycle, command discovery, slash and mention routing, cooldowns, bounded worker pools, MongoDB access |
| `commands/` | Thin registration classes: names, options and permissions for each command |
| `bunnynexus/commands/` | Command implementations (timer, free games, info, help) |
| `bunnynexus/freebies/` | Free-game discovery, review, delivery, lifecycle and DMs |
| `bunnynexus/moderation/` | Ban, kick and mute logic and checks |
| `bunnynexus/timers/` | Study sessions, semesters, GPA and their Discord views |
| `bunnynexus/help/` | The `/help` menu |
| `buttons/`, `selects/`, `modals/` | Component handlers, routed by the prefix of each component id |
| `events/`, `services/` | Gateway listeners and long-running background services |
| `database/models/` | Stored MongoDB documents |

Commands, events, components and services are discovered automatically from these packages, so a new command
only needs a class in `commands/`.

### Data safety

- Account and timer writes are revision-checked, so a concurrent edit fails instead of overwriting.
- Finishing a session and archiving a semester update everything in one transaction.
- Free-game deliveries are durable and retried. Each message carries a reference that stops a retry from
  posting twice.

## Testing

```bash
./mvnw clean verify
```

The test suite covers the command definitions, the help menu, Discord payload limits, free-game parsing
(including captured GamerPower and Epic responses), delivery and review logic, timers, and moderation checks. It
doesn't log in to Discord.

The MongoDB integration tests (`*IT`) need a local `mongod` and run only when given one:

```bash
./mvnw verify -Dtest='*IT' -Dbunny.test.mongod=/path/to/mongod
```

Made by .Bunnys
