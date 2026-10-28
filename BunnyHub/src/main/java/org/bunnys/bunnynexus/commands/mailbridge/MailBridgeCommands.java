package org.bunnys.bunnynexus.commands.mailbridge;

import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.bunnys.bunnynexus.mailbridge.*;
import org.bunnys.bunnynexus.mailbridge.MailSource.MailSourceException;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code /mail-bridge status | check | diagnose | dry-run | test}. Developer-only, always private. Nothing here
 * changes the mailbox; only {@code test} posts anything.
 */
public final class MailBridgeCommands {
    /** Broad enough to catch task emails before the patterns are right. */
    static final String DIAGNOSE_QUERY = "newer_than:14d (from:openai.com OR from:chatgpt.com OR chatgpt OR \"scheduled task\")";
    static final int DIAGNOSE_LIMIT = 8;

    private MailBridgeCommands() {}

    private abstract static class Branch extends BunnySubcommand {
        @Override public final void execute(BunnyHub client, CommandContext ctx) {
            var bridge = MailBridge.current();
            if (bridge.isEmpty()) {
                reply(ctx, "The mail bridge is off: " + MailBridgeCards.untrusted(MailBridge.disabledReason(), 900)
                        + "\n\nSee the README section \"Mail bridge\" for the settings it needs.");
                return;
            }
            run(bridge.get(), ctx);
        }

        abstract void run(MailBridge bridge, CommandContext ctx);
    }

    public static final class Status extends Branch {
        public Status() { setName("status"); setDescription("Health: last Gmail check, problems, and email counts"); }
        @Override void run(MailBridge bridge, CommandContext ctx) { reply(ctx, bridge.status()); }
    }

    public static final class Check extends Branch {
        public Check() { setName("check"); setDescription("Check Gmail now instead of waiting for the next poll"); }
        @Override void run(MailBridge bridge, CommandContext ctx) {
            bridge.pollNow();
            reply(ctx, "Checking Gmail now. Run `/mail-bridge status` in a few seconds to see the result.");
        }
    }

    public static final class Diagnose extends Branch {
        public Diagnose() {
            setName("diagnose");
            setDescription("List recent emails that look like task notices and how each would be classified");
            addOption(new OptionData(OptionType.STRING, "query", "Gmail search to use instead of the default", false).setMaxLength(300));
        }

        @Override void run(MailBridge bridge, CommandContext ctx) {
            String query = ctx.getString("query", DIAGNOSE_QUERY);
            try {
                List<String> ids = bridge.source().search(query, DIAGNOSE_LIMIT);
                if (ids.isEmpty()) { reply(ctx, "No emails match `" + MailBridgeCards.untrusted(query, 300) + "`."); return; }
                StringBuilder text = new StringBuilder("Newest first. Subjects are cut short and bodies aren't shown.\n\n");
                for (String id : ids) {
                    MailMessage mail = MailParser.parse(bridge.source().fetch(id));
                    var result = bridge.classifier().classify(mail);
                    String state = bridge.ledger().find(id).map(entry -> entry.status().name().toLowerCase()).orElse("not seen yet");
                    text.append("**<t:").append(mail.received().getEpochSecond()).append(":f>** · ")
                            .append(MailBridgeCards.untrusted(mail.fromAddress().isEmpty() ? "unknown sender" : mail.fromAddress(), 80)).append('\n')
                            .append("Subject: ").append(MailBridgeCards.untrusted(mail.subject(), 70)).append('\n')
                            .append("Result: **").append(result.outcome()).append("** ")
                            .append(result.outcome() == MailClassifier.Outcome.MATCHED ? result.category().name() + " " : "")
                            .append("(").append(MailBridgeCards.untrusted(result.reason(), 80)).append(")")
                            .append(" · report link: ").append(mail.reportLink().isPresent() ? "yes" : "no")
                            .append(" · bridge: ").append(state).append(" · id `").append(id).append("`\n\n");
                    if (text.length() > 3500) break;
                }
                reply(ctx, text.toString().strip());
            } catch (MailSourceException failure) {
                reply(ctx, "Gmail failed: " + MailBridgeCards.untrusted(failure.getMessage(), 300));
            }
        }
    }

    public static final class DryRun extends Branch {
        public DryRun() {
            setName("dry-run");
            setDescription("Show the notification an email would produce, without posting it");
            addOption(new OptionData(OptionType.STRING, "message-id", "Gmail message ID from diagnose (default: newest match)", false).setMaxLength(64));
        }

        @Override void run(MailBridge bridge, CommandContext ctx) {
            try {
                Optional<String> chosen = Optional.ofNullable(ctx.getString("message-id", null)).map(String::strip);
                List<String> ids = chosen.map(List::of).orElseGet(() -> {
                    try { return bridge.source().search(bridge.config().query(), DIAGNOSE_LIMIT); }
                    catch (MailSourceException failure) { throw new IllegalStateException(failure.getMessage()); }
                });
                for (String id : ids) {
                    MailMessage mail = MailParser.parse(bridge.source().fetch(id));
                    var result = bridge.classifier().classify(mail);
                    if (!result.forwardable()) {
                        if (chosen.isPresent()) { reply(ctx, "That email would not be posted: **" + result.outcome() + "** ("
                                + MailBridgeCards.untrusted(result.reason(), 200) + ")."); return; }
                        continue;
                    }
                    var card = MailBridgeCards.notification(mail, result.category(), bridge.config().mentionUserId());
                    ctx.replyMessage(new MessageCreateBuilder().setContent("**Dry run, not posted.** Would send to <#"
                                    + bridge.config().channelId() + ">:\n" + card.getContent())
                            .setEmbeds(card.getEmbeds()).setAllowedMentions(EnumSet.noneOf(MentionType.class)).build());
                    return;
                }
                reply(ctx, "None of the newest " + ids.size() + " emails matching the bridge's search would be posted.");
            } catch (MailSourceException | IllegalStateException failure) {
                reply(ctx, "Gmail failed: " + MailBridgeCards.untrusted(String.valueOf(failure.getMessage()), 300));
            } catch (IllegalArgumentException unusable) {
                reply(ctx, "That message couldn't be read.");
            }
        }
    }

    public static final class Test extends Branch {
        public Test() { setName("test"); setDescription("Post a test notification to the channel and ping you"); }
        @Override void run(MailBridge bridge, CommandContext ctx) {
            var config = bridge.config();
            var problem = bridge.poster().problem(config.guildId(), config.channelId());
            if (problem.isPresent()) { reply(ctx, "Can't post: " + problem.get() + "."); return; }
            try {
                bridge.poster().post(config.channelId(), MailBridgeCards.test(config.mentionUserId(), Instant.now()), "mbtest" + System.currentTimeMillis() % 1_000_000)
                        .get(30, TimeUnit.SECONDS);
                reply(ctx, "Posted a test notification in <#" + config.channelId() + ">.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failed) {
                reply(ctx, "The test post failed: " + failed.getClass().getSimpleName() + ".");
            }
        }
    }

    private static void reply(CommandContext ctx, String text) {
        ctx.reply(Embeds.of("📬", "Mail bridge", text).build(), true);
    }
}
