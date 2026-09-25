# Free-game alerts (v2, 2026-09-25)

This is the **active** free-game system, wired into `Main`. It replaces the unwired `alerts` framework for runtime use; that older code (`bunnynexus/alerts`, `bunnynexus/commands/alerts`) is left in place but is not started or registered. Only `GamerPowerIntake` (the feed parser) is reused.

## Flow

1. **Discover.** `GamerPowerFeed` polls `https://www.gamerpower.com/api/giveaways?type=game` every `FREEBIE_POLL_MINUTES` (default 10). If a poll fails, the next one backs off, up to 2 h, and honours `Retry-After`. Each new giveaway is stored once in `FreebieOffers` as `PENDING`. Its launcher comes from GamerPower's platform text (`FreebieStore`).
2. **Review.** The bot posts the exact alert embed, plus review details, in `FREEBIE_REVIEW_CHANNEL_ID` and pings the owners. There are two buttons: **Approve** and **Reject**.
3. **Approve.** Approve shows a private confirmation with the current channel and server counts. **Yes, send it** switches the offer from `PENDING` to `APPROVED` in one conditional update, so double clicks and several owners are safe. It then creates one `FreebieDeliveries` row per subscribed channel for that launcher. Creating the rows is idempotent and resumes after a crash.
4. **Send.** `FreebieSender` runs on its own thread with at most 8 sends in flight. Each channel's delivery is leased in the database. The bot checks its permissions first, sends the alert with a fixed nonce (JDA sends `enforce_nonce`), and pings only the configured role.
5. **Retry.** Timeouts, network errors, Discord 5xx and rate limits are retried up to 5 attempts in total, waiting 30 s, 2 m, 10 m and 30 m between them. After an uncertain failure or a crash, the next attempt first searches the last 50 channel messages for the alert's footer `ref`. That search needs Read Message History. Permanent errors (missing permissions, deleted channel, bot removed) are not retried.
6. **Report.** When no deliveries are left, the review message is updated and a summary is posted in the review channel: sent, failed and cancelled counts, plus the failed servers. The owner of each server that failed is DM'd; if their DMs are closed, the notice goes to the server's system channel. A server gets at most one notice per day (`FreebieNotices`).
7. **Stop.** After approval the review message has a **Stop sending** button. The system also stops by itself when the offer's end date passes, or when a complete feed stops listing the offer for two polls in a row. Stopping cancels unsent deliveries; up to 8 already in flight can still arrive.

## Configuration (.env only)

| Key | Required | Meaning |
|---|---|---|
| `FREEBIE_REVIEW_CHANNEL_ID` | yes | Private review channel |
| `FREEBIE_OWNER_IDS` | yes | Comma-separated user IDs allowed to approve, reject or stop |
| `FREEBIE_POLL_MINUTES` | no | 5–120, default 10 |
| `FREEBIE_ENABLED` | no | `false` turns the system off |

If either required key is missing or invalid, the system logs why and stays off. Buttons check both the owner ID and the review channel; the service checks the owner again.

## Server commands (`/freebie`, Manage Server)

- `setup channel launcher [role]`: add or update. The bot checks it can post there and that the role can be pinged; `@everyone` is refused. At most 10 settings per server.
- `remove [channel] [launcher]`: with no channel, removes all of the server's settings, including ones for deleted channels.
- `status`: lists the settings and flags channels the bot can no longer post in.
- `test channel`: sends a test message.
- After `setup`, if already-approved games for that launcher are still free, a button offers to post them in the new channel (each at most once per channel).

Leaving a server deletes that server's settings (`FreebieGuildLeave`).

## Owner controls

- On a pending review: a **launcher picker** corrects auto-detection before approving (the confirmation then counts the new audience).
- While sending, the review message shows progress (sent/failed/left) at most once a minute.
- `/freebie-admin status | pause | resume | check` (developer-only + FREEBIE_OWNER_IDS): discovery health, queue sizes, a persisted global send pause (queued alerts wait, nothing is lost), and an immediate GamerPower check.
- If discovery fails for over an hour the owners are told once in the review channel, and again when it recovers.

## Collections

`FreebieOffers`, `FreebieDeliveries`, `FreebieSubscriptions`, `FreebieNotices`, `FreebieControl` (global pause). They are new and independent of the `Alert*` collections. Indexes are created at start with `createIndex`, which is idempotent. Every state change is a single-document conditional update, and delivery outcomes are fenced by a lease token.

## Known limits

- GamerPower end dates have no timezone. They are treated as UTC + 2 h and used only to stop sending; the feed-disappearance check is the faster stop.
- Late subscribers only get currently live games if an admin presses the catch-up button after setup.

- On the first start every giveaway that is currently active (usually around 20) is queued for review, 5 review posts every 30 s.
- Not load-tested against Discord. The throughput estimate is about 1,000 channels per minute, limited by Discord rate limits.

## Verification

- `mvnw clean verify`: 343 tests passed (`.tools/freebies-v3-verify.log`).
- `FreebieRepositoryIT`: 12 tests passed on a real isolated three-node MongoDB (`-Dtest=FreebieRepositoryIT -Dbunny.test.mongod=.tools/mongodb-8.0.32/bin/mongod.exe`). They cover concurrent approval, launcher correction, catch-up eligibility, pause persistence, exactly-once claims, lease fencing, crash recovery, stopping, summary once, notice rate limit and subscription limits.
- Packaged smoke passed (`.tools/freebies-v3-smoke.log`).
- No live Discord send or bot restart was performed.
