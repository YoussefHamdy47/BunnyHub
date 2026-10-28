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
- Every game found pings the owners in the review channel, including the auto-sent ones. **Clear finished** on any
  review message deletes the bot's messages there that no longer need anyone and keeps those with Approve,
  Reject or Stop. It only touches the bot's own messages, so it needs Read Message History and no extra intent.

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

`/freebie-admin status | pause | resume | check | sources | history | retry | announce`: health, pausing sends,
forcing a source check, resolving duplicate offers between sources, delivery history and retries, and announcements.
Only the IDs in `FREEBIE_OWNER_IDS` can use it.

`/freebie-admin announce title message` sends an owner message to every server with free-game alerts: one message per
server, in its alert channel (the major PC one when set). Run it in a review channel; it first shows a private preview
with the server count, and nothing is sent until you press **Send with ping** (each server's free-game role) or
**Send without ping** within 15 minutes. @everyone and @here are never pinged.
**Stop this announcement** cancels the servers not reached yet. Type `\n` in the message for a new line.

Possible duplicates between GamerPower and Epic are held, not posted twice: the review channel gets one
**Possible duplicate** message with **Same giveaway / Separate giveaway / Ignore** buttons. Games of one Epic release
are posted together, one message and one ping per channel; a review shows **Approve release** when several are
waiting.

Every approval confirmation also has **Send without ping**: the game posts as usual, but without the role mention
(its catch-up posts and ending-soon reminder stay quiet too).

`/mail-bridge status | check | diagnose | dry-run | test`: health and testing for the
[mail bridge](#mail-bridge). Nothing here changes your mailbox, and only `test` posts anything.

Both live on the developers' own Discord accounts (add the bot to your account), so they appear in your `/`
menu in any server or DM and in nobody else's.

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
| `FREEBIE_GROUPED_ANNOUNCEMENTS` | No | `false` posts every Epic game separately instead of one post per release (default `true`) |
| `FREEBIE_EGDATA_ENABLED` | No | Region restrictions and separate mobile offers from EGData (default `true`) |
| `FREEBIE_PUBLISH_ENABLED` | No | `true` publishes new free-game posts to servers following the channels below (default `false`) |
| `FREEBIE_PUBLISH_CHANNEL_IDS` | With publishing | Comma-separated Announcement Channel IDs to publish from |
| `FREEBIE_EPIC_CHECKOUT` | No | Combined "Claim all PC games" checkout button (default `false`) |
| `FREEBIE_RESOLVE_GAMERPOWER_LINKS` | No | Resolve GamerPower links during discovery to merge Epic duplicates automatically (default `false`) |
| `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REFRESH_TOKEN` | For the mail bridge | Google OAuth client and refresh token, see [Mail bridge](#mail-bridge) |
| `MAIL_BRIDGE_CHANNEL_ID` | No | Where task notifications go (default `1562067773033807912`) |
| `MAIL_BRIDGE_GUILD_ID` | No | The server that channel is in (default `1043515676960247828`) |
| `MAIL_BRIDGE_MENTION_USER_ID` | No | The only user the notifications may ping (default `333644367539470337`) |
| `MAIL_BRIDGE_DIAGNOSTIC_CHANNEL_ID` | No | Where "couldn't classify" and "Gmail access broken" notices go, without email content. Unset: unclassified emails are only logged, and access warnings go to the main channel |
| `MAIL_BRIDGE_QUERY` | No | Gmail search for candidate emails (default `from:(openai.com OR chatgpt.com)`) |
| `MAIL_BRIDGE_SENDERS` | No | Comma-separated addresses or domains the From address must match (default `openai.com,chatgpt.com`; subdomains count) |
| `MAIL_BRIDGE_PATTERNS_EEE_TRAINING` | No | How to recognise each task, see below. Default: the task's name |
| `MAIL_BRIDGE_PATTERNS_POWER_ELECTRONICS` | No | |
| `MAIL_BRIDGE_PATTERNS_RTL_VERIFICATION` | No | |
| `MAIL_BRIDGE_POLL_MINUTES` | No | How often Gmail is checked, 1–60 (default 5) |
| `MAIL_BRIDGE_MAX_ATTEMPTS` | No | Post attempts per notification before giving up, 1–20 (default 6) |
| `MAIL_BRIDGE_ENABLED` | No | `false` turns the mail bridge off |

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

## Mail bridge

Forwards the "your scheduled task finished" emails from three ChatGPT career-search tasks to one Discord channel
and pings you, so the results don't get lost among other chats:

| Category | Task | Runs |
| --- | --- | --- |
| `EEE_TRAINING` | EEE Industrial Training Hunter | Daily |
| `POWER_ELECTRONICS` | Power Electronics Career Intelligence | Weekly |
| `RTL_VERIFICATION` | RTL / Digital Verification Career Tracker | Weekly |

```
ChatGPT task finishes → email to Gmail → bridge reads it (Gmail API, read-only) → classify
  → record in MongoDB (dedupe) → post in the channel with a ping → mark as delivered
```

It runs inside the bot, so it needs no extra server, bot token or webhook. It posts as BunnyNexus, which must
be in the server and able to see, post, embed links and read history in the channel.

### What the emails contain

**Confirmed:**
- ChatGPT emails you when a scheduled task finishes, if email notifications are on (ChatGPT → Settings →
  Notifications → Tasks).
- The email is a notice that the task finished. It does **not** include the full report.

**Not confirmed, so not hardcoded:**
- The exact sender address, subject wording, and whether there's a direct link to the conversation. Those can
  only be confirmed against a real email.

Because of that, the sender and the task patterns are settings, and the notification says so plainly when the
email has no direct link. The report must then be opened in ChatGPT under **Tasks**. Only an `https` link to
`chatgpt.com` or `chat.openai.com` counts as a report link. Tracking redirects don't, and no link from an email
is ever opened by the bot. Run `/mail-bridge diagnose` after the first real task email to see what it actually
contains, and adjust the settings if needed.

### Set up Gmail access (once)

1. **Create a Google Cloud project:** go to [console.cloud.google.com](https://console.cloud.google.com) and
   create a project, for example "bunnynexus-mail".
2. **Enable the Gmail API:** APIs & Services → Library → Gmail API → Enable.
3. **Set up the OAuth consent screen:** Google Auth Platform (or APIs & Services → OAuth consent screen).
   - User type: **External**.
   - Fill in the app name and your email.
   - Under Data access, add the scope `https://www.googleapis.com/auth/gmail.readonly`. That's the only scope
     used: it can read mail and can't change or send anything.
   - Under Audience, add your Gmail address as a test user.
4. **Publish the app:** under Audience, click **Publish app**, so its status is **In production**. While it's
   in Testing, Google expires refresh tokens after 7 days and the bridge would stop every week. You don't need
   Google's verification for your own account. During sign-in you'll see "Google hasn't verified this app";
   choose Advanced → continue.
5. **Create the OAuth client:** Clients (or Credentials) → Create OAuth client ID → **Web application**.
   - Add the authorised redirect URI `https://developers.google.com/oauthplayground`.
   - Keep the client ID and secret to yourself.
6. **Get the refresh token:**
   1. Open the [OAuth 2.0 Playground](https://developers.google.com/oauthplayground).
   2. Click the gear icon, tick **Use your own OAuth credentials**, and enter the client ID and secret.
   3. In Step 1, type `https://www.googleapis.com/auth/gmail.readonly`, click Authorize APIs, and sign in with
      the Gmail account that receives the task emails.
   4. In Step 2, click **Exchange authorization code for tokens** and copy the **refresh token**.
7. **Add the variables:** in Railway → bunnyhub-bot → Variables, add `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`
   and `GMAIL_REFRESH_TOKEN`. When running locally, put them in `.env` instead, readable only by you
   (`chmod 600 .env`; it's already ignored by git). Never commit them or paste them into chat. The bot keeps
   the short-lived access token in memory only.

The refresh token stays valid until you revoke it (Google Account → Security → Third-party access), change your
password, or don't use it for six months. If it stops working, the bridge pauses, posts one "Gmail access needs
attention" message pinging you, and logs it. Repeat step 6, update `GMAIL_REFRESH_TOKEN`, and redeploy.

### Discord side

- **Channel and server:** they're set by default to the channel and server above. To change them, turn on
  Developer Mode (User Settings → Advanced), right-click the channel → Copy Channel ID, and the server icon →
  Copy Server ID.
- **Your user ID:** right-click your name → Copy User ID. It goes in `MAIL_BRIDGE_MENTION_USER_ID`.
- **Pings:** each message allows exactly one ping, you. Nothing from an email can mention anyone. Text from the
  email is escaped and its links are removed, so it can't ping anyone or hide a link. `@everyone` and `@here`
  are never allowed.

### How emails are classified

1. **Sender:** the From address must match `MAIL_BRIDGE_SENDERS`. Anything else is ignored.
2. **Subject:** each category's patterns are tried on the subject. If exactly one category matches, that's the
   answer.
3. **Body:** if the subject didn't settle it, the body is tried the same way.
4. **No clear match:** if no category matches, or more than one does, the email is **never** forwarded, let
   alone to all three. It's logged and, if `MAIL_BRIDGE_DIAGNOSTIC_CHANNEL_ID` is set, a notice with no email
   content goes there. Emails from the sender that don't mention a task at all (receipts, security alerts) are
   ignored quietly.

Patterns are separated by `;`. Plain text matches anywhere and ignores case. `/…/` is a regular expression, also
case-insensitive. For example:

```
MAIL_BRIDGE_PATTERNS_RTL_VERIFICATION=RTL / Digital Verification Career Tracker;/\b(RTL|FPGA) career\b/
```

To narrow the search, use Gmail's own syntax in `MAIL_BRIDGE_QUERY`, for example `label:career-tasks` after
setting up a Gmail filter that labels the task emails.

### Validate it with a real email

1. **Add the bot to your account** if you haven't yet, so `/mail-bridge` shows up for you.
2. **Run `/mail-bridge status`.** It should say when it last checked Gmail, with no "Discord:" problem line. If
   the bridge is off, it says which setting is missing.
3. **Run `/mail-bridge test`.** It posts a test message in the channel and pings you.
4. **Create a real task email:** in ChatGPT, run one of the tasks now (or wait for it), with email notifications
   on.
5. **Run `/mail-bridge diagnose`.** It lists recent emails that look like task notices: sender, the start of the
   subject, how each would be classified, and whether there's a report link. Bodies are never shown. If the task
   email doesn't show up, or shows `UNMATCHED`, adjust `MAIL_BRIDGE_SENDERS`, `MAIL_BRIDGE_QUERY` or the
   patterns.
6. **Run `/mail-bridge dry-run`.** It shows, privately, exactly what would be posted, without posting it.
7. **Run `/mail-bridge check`** (or wait for the next poll). The notification should arrive in the channel.

Only emails received **after the bridge first started** are forwarded. That start time is stored once, so older
emails never flood the channel. A task email that arrived earlier can still be checked with `diagnose` and
`dry-run`.

### Reliability and limits

- **Duplicates:** every Gmail message is recorded by its ID in MongoDB (`MailBridgeMessages`) before anything is
  posted, so a message is handled once across polls, restarts and redeploys. Nothing in Gmail is changed: no
  deleting, archiving, labelling or marking as read.
- **Delivery:** a notification only counts as delivered once Discord returns the message ID. Failures are retried
  with growing waits (1, 2, 4… minutes, at most an hour) up to `MAIL_BRIDGE_MAX_ATTEMPTS`. Discord's rate
  limits and Retry-After are handled by the bot's Discord library. Gmail's Retry-After is respected too.
- **Exactly once isn't possible.** If Discord accepts a post but the answer is lost (a timeout, or a restart at
  the wrong moment), the outcome is unknown. Before posting again, the bridge looks for the message in the
  channel's last 50 messages by its `Gmail ref` footer, and only posts if it isn't there. If the channel history
  can't be read, it posts again rather than risk losing the notice, so a rare duplicate is possible; a missed
  notice isn't.
- **If the bot can't post** (not in the server, missing permissions), notifications wait without using up their
  attempts, and `status` says why.
- **Search window:** each poll looks back at most 14 days, and finished records are kept for 90 days, so a long
  outage can't make old emails reappear.

### Logs and troubleshooting

Logs go to Railway → bunnyhub-bot → Deployments → View logs. Filter for `[MailBridge]`. They contain Gmail
message IDs, categories and reasons, never subjects, bodies, tokens or links.

| Symptom | Likely cause |
| --- | --- |
| `[MailBridge] Disabled: GMAIL_... is missing` | Variables not set on Railway |
| `Google rejected the stored credentials (invalid_grant)` | Refresh token expired or revoked, often because the app was still in Testing. Publish the app and get a new token |
| `Gmail refused access (HTTP 403)` | Wrong scope: the token wasn't made with `gmail.readonly` |
| `Can't post yet: the bot is not in server …` | Invite BunnyNexus to that server |
| `Can't post yet: missing Embed Links in #…` | Give the bot View Channel, Send Messages, Embed Links and Read Message History there |
| `… looks like a task email but wasn't forwarded` | Patterns didn't match exactly one task. Run `/mail-bridge diagnose` and adjust them |
| Nothing at all after a task ran | Check ChatGPT's email notifications are on, then run `diagnose` with a wider `query` |

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
| `bunnynexus/mailbridge/` | Gmail → Discord task notifications: Gmail client, parsing, classification, delivery |
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
- Mail bridge notifications work the same way, keyed by Gmail message ID.

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
