# Development notes

A handover for whoever picks this project up next. It covers what the system is, how it's built and deployed,
the house rules, what's in flight and what's planned. User-facing docs and full setup steps are in
[`BunnyHub/README.md`](BunnyHub/README.md); read that too.

## What this is

**BunnyNexus** is a Discord bot (about 85 servers, about 94k users) running on **BunnyHub**, a small Java
command framework that lives in the same codebase. The bot's own Discord server is also called **BunnyHub**.
It used to be called "GBF", and the production database is still named `GBF`.

| Feature | What it does |
| --- | --- |
| Free games | Finds paid games that go free (GamerPower and Epic feeds) and posts them to subscribed channels, by DM and with per-launcher role pings |
| Moderation | `/moderation ban, unban, kick, mute, unmute`. Mute uses Discord timeouts |
| Study timers | `/timer`: study sessions, subjects, GPA, semesters |
| Info | `/info`, `/avatar`, `/ping`, `/uptime`, `/help` |
| Owner tools | `/freebie-admin` (free-game operations) and `/mail-bridge` (Gmail task notifications) |

## Stack

| Area | Details |
| --- | --- |
| Language and build | Java 21, Maven (`BunnyHub/pom.xml`, wrapper `mvnw`) |
| Main libraries | JDA 6.6.0, MongoDB sync driver 5.x, OkHttp 5, Caffeine, logback, dotenv-java |
| Database | MongoDB replica set, Atlas in production. Transactions are required, so a standalone server is refused |
| Tests | JUnit 6 + Mockito. MongoDB integration tests (`*IT`) only run with `-Dbunny.test.mongod=/path/to/mongod` |
| CI | `.github/workflows/build.yml` runs `./mvnw -B -ntp clean verify` on every push and PR |
| Hosting | Railway, project `a6349b6d-bd6a-4f66-b327-0033de7168b6`, service `bunnyhub-bot`, environment production |

Railway builds `BunnyHub/Dockerfile` (root directory `/BunnyHub`, watch path `/BunnyHub/**`) and **auto-deploys
whenever `main` changes**. The JVM is capped by `JAVA_OPTS` in the Dockerfile at a 384 MB heap with Serial GC.
Keep memory in mind: avoid heavy libraries (that's why Gmail is reached with plain HTTP, not Google's client
library).

## Layout

Everything is under `BunnyHub/src/main/java/org/bunnys/`:

| Path | Contents |
| --- | --- |
| `Main.java` | Entry point. Developer IDs (`333644367539470337`) and test server are set here |
| `handler/` | The BunnyHub framework: lifecycle, discovery, slash and mention routing, cooldowns, worker pools, `CommandGate` (permission checks), MongoDB access (`database/DB`) |
| `commands/` | Thin registration classes: one per slash command, with name, options, permissions and install type |
| `bunnynexus/commands/` | Command implementations |
| `bunnynexus/freebies/` | The free-game system: discovery, review, delivery, lifecycle, DMs, cards |
| `bunnynexus/moderation/`, `timers/`, `help/`, `info/` | The other features |
| `bunnynexus/mailbridge/` | Gmail → Discord task notifications |
| `buttons/`, `selects/`, `modals/` | Component handlers, routed by the prefix of the component id |
| `events/` | Gateway listeners |
| `services/` | Long-running background services (`BunnyService`), started after login |

Commands, events, components and services are found automatically by package, so a new command only needs a
class in `commands/`. Tests mirror the same packages under `src/test/java`.

### Framework notes

- **Commands:**
  - A command extends `BunnyCommand`; branches extend `BunnySubcommand`.
  - Use `setDeveloperOnly`, `addUserPermissions`, `setDmEnabled`, `setDeferBeforeDispatch` and
    `defaultEphemeral` for access and reply behaviour.
  - `setInstallTypes(IntegrationType...)` chooses server install, account install, or both. Owner tools are
    account-install only, so they only show up for the developer.
- **Context:** `CommandContext` gives `reply`, `replyMessage`, `replyTransient`, `defer` and option getters. It
  works for both slash commands and `@BunnyNexus` mentions.
- **Components:** component ids look like `prefix:args`, and each handler claims a prefix.
- **Settings:** come from environment variables or `.env`, read via `TokenLoader.getEnvQuietly`. Each feature has
  a `*Config.load(...)` that validates and reports problems without echoing secret values.

### Free games: things that must not break

- **Footer:** the alert footer `Powered by GamerPower • ref <reference>` (or `Powered by Epic Games • ref …`) is
  how deliveries are matched and de-duplicated. Never change its format.
- **Claim heading:** the link heading is `**Claim Game**`. Lifecycle edits strip from that heading down when an
  offer ends, and also recognise the older `**Claim your game**`.
- **Claim row:** the claim button sits between two disabled spacer buttons (custom id prefix `freebie_pad`) so it
  looks centered.
- **Posted without review:** the launchers in `FREEBIE_AUTO_SEND` (default epic, steam, gog) post straight away.
  Everything else waits for owner review in the review channel.
- **Claim links:** GamerPower redirects are turned into direct store links where possible, with a fallback to the
  GamerPower link.

## House rules (from the owner, keep following them)

- **Git identity:** all commits are authored and committed as
  `Youssef Hamdy <78443317+YoussefHamdy47@users.noreply.github.com>`.
- **No attribution:** no `Co-Authored-By`, generator footers or similar trailers in commits, PR titles or PR
  descriptions.
- **No assistant mentions:** don't credit or mention coding assistants anywhere in the code, docs, commits or
  PRs. The owner's users dislike it.
- **Branches:** use plain, descriptive names like `freebie-thumbnail` or `mail-bridge`.
- **Workflow:** work on a branch, open a PR into `main`, and the owner merges it. The merge deploys to production
  automatically.
- **Checks before pushing:** run `cd BunnyHub && ./mvnw -B clean verify` and only push when it's green.
- **Markdown:** the only Markdown files in the repo are the READMEs and this file. Don't add more without asking.
- **Secrets:** never commit or print secrets (bot token, Mongo URI, Gmail OAuth values, webhook URLs). They live
  in Railway variables or a local `.env`, which is git-ignored.

## Current state (October 2026)

**Merged and live:** everything up to PR #29, plus the review-channel change below once its PR is merged.
- Free-game redesign: sections, per-launcher pings, auto-send, store links, prices including Epic, a centered
  claim button with title-case labels (`Claim Epic Offer`, heading `Claim Game`), and full-size game art.
- **Review channel:** every game found pings the owners, including auto-sent ones. Each review message has a
  **Clear finished** button (`FreebieReviewCleanup`). It deletes the bot's own messages that no longer have
  Approve, Reject, Stop or the launcher picker.
- `/moderation`, `/ping` and `/uptime`.
- A code quality pass.
- Account-install support:
  - `/freebie-admin` and `/mail-bridge` are on the developer's account only.
  - `/ping`, `/uptime`, `/avatar`, `/info`, `/help` and `/free-games` work from both server and account installs.
  - `/moderation` and `/timer` are server-only.
- `/freebie-admin connect`, which sets up alerts in any server the bot is in; `role` is optional.
- The mail bridge (below). It's deployed but off until the `GMAIL_*` variables are set on Railway.

### Mail bridge

- **What it does:** reads Gmail (scope `gmail.readonly`, plain HTTPS via OkHttp) every 5 minutes and recognises
  ChatGPT task-completion emails.
- **How it recognises them:** by sender (`MAIL_BRIDGE_SENDERS`) and per-task patterns
  (`MAIL_BRIDGE_PATTERNS_*`, subject first, then body).
- **Where it posts:** as BunnyNexus to channel `1562067773033807912` in server `1043515676960247828`, pinging
  only `333644367539470337`.
- **Records:** kept in MongoDB (`MailBridgeMessages`, `MailBridgeState`), keyed by Gmail message ID.
- **Duplicate handling:**
  - A post only counts once Discord returns the message ID.
  - An unconfirmed post is looked up in the channel by its `Gmail ref <id>` footer before retrying.
- **Not verified against a real task email yet.** The sender address and subject wording are not known for sure,
  which is why they're settings.
- **To finish it:**
  1. Create the Google OAuth client and refresh token (README, "Mail bridge"), and publish the Google app to
     Production so the token doesn't expire in 7 days.
  2. Set the `GMAIL_*` variables on Railway.
  3. Make sure the bot is in that server with View Channel, Send Messages, Embed Links and Read Message History.
  4. Run `/mail-bridge test`, then trigger a task, then run `diagnose` and `dry-run`.
- `MongoMailLedgerIT` has not been run (it needs a local mongod).

## Owner to-dos (not code)

- **Old server:** run
  `/freebie-admin connect server:605082531276259352 channel:750490795504828437`
  (plus `post-current:true` to post current games too).
- **Merged branches:** delete them on GitHub: `code-quality-pass`, `freebie-card-price`, `freebie-connect`,
  `freebie-thumbnail`, `ping-uptime-commands`, `readme-and-agent-rules`, `remove-agent-files`,
  `user-install-commands`, `freebie-image-user-install`, `mail-bridge`, `development-notes` and
  `review-pings-clear`. Turn on
  Settings → General → "Automatically delete head branches".
- **Bot invite permissions:** in Developer Portal → Installation, use View Channels, Send Messages, Embed Links,
  Read Message History, Mention Everyone, Manage Roles, Manage Channels, Ban Members, Kick Members and Timeout
  Members. Manage Messages isn't needed.
- **List the bot on top.gg**, which the promo idea below needs.

## Ideas parked for later

- **Promo line in alerts:**
  - Things to promote: vote on top.gg, add the bot to your account or server, join BunnyHub.
  - Show it at most once a day per channel rather than at random.
  - Make it a small line or link button, never in the footer.
  - Show it less in other servers, with an off switch in `/free-games setup`.
  - Rotate between messages to see which works.
  - Waiting on a top.gg listing and the owner's OK.
- **Welcome, goodbye and join roles for the BunnyHub server only:** a second bot login inside the same process
  (about 20–40 MB extra), joined only to BunnyHub with the Server Members intent. That keeps the main bot free of
  privileged intents as it nears 100 servers. Until then, Discord's Onboarding and system welcome messages cover
  most of it.
- **Help menu for staff:** show `/moderation` and `/free-games setup` in `/help` only to members with the matching
  permission. This was started and dropped, so ask the owner before doing it.
- **Owner stats command:** how many servers have alerts on, DM subscriber counts, and so on.
- **Mail bridge extensions:** an internship database, deadline reminders, weekly job-market summaries, and an
  applications dashboard. The bridge's separate parts (source, classifier, ledger, poster) were kept apart so these
  can be added later.

## Running locally

```bash
cd BunnyHub
cp .env.example .env        # fill in TOKEN and MongoURI (a replica set), plus any FREEBIE_* or GMAIL_* values
./mvnw clean verify         # Windows: .\mvnw.cmd clean verify
java -jar target/BunnyHub-4.00.jar
```

For live testing use `start_test_server.bat`. It reads `.env.test`, needs its own bot token and database, and
refuses the production `GBF` database. The test server ID is in `Main.java`.

Railway logs: Railway → bunnyhub-bot → Deployments → View logs. Each subsystem logs with a prefix such as
`[Freebies]`, `[MailBridge]` or `[CommandRegistry]`. A healthy start ends with
`[Services] Started N service(s).`
