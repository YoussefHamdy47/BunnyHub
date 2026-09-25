package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command invoked as {@code @BotName command args}.
 *
 * <p>Without the Message Content intent Discord only hands us content when the bot is
 * explicitly mentioned, so this is the entire non-slash surface. The bot mention has
 * already been stripped by the router; what arrives here is the argument tail.
 *
 * <h2>Argument grammar</h2>
 * Arguments are matched against the same {@link OptionData} list the slash command
 * declares, so one declaration drives both entry points and the help menu can print
 * genuinely accurate syntax for each.
 *
 * <ul>
 *   <li><b>Named</b> - {@code chapter:5 source:MD}, mirroring how Discord renders slash
 *       options, so the two forms read alike.</li>
 *   <li><b>Quoted</b> - {@code query:"Legoshi and Haru"} for values with spaces.</li>
 *   <li><b>Positional</b> - anything left over fills the remaining options in declared
 *       order, so {@code @Bot wiki Legoshi} works without naming anything.</li>
 *   <li><b>Trailing text</b> - the final string option absorbs all remaining tokens, so
 *       {@code @Bot wiki Legoshi Inari} searches the whole phrase rather than dropping
 *       the second word.</li>
 * </ul>
 */
public final class MentionContext extends CommandContext {

    /** {@code <@123>} and the legacy nickname form {@code <@!123>}. */
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d{17,20})>");

    /** {@code <#123>}. */
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d{17,20})>");

    /**
     * Splits on whitespace while keeping quoted runs together.
     *
     * <p>Three alternatives, order-sensitive: {@code key:"quoted value"} must be tried
     * before a bare quoted run, which must be tried before a plain word - otherwise
     * {@code query:"a b"} would split at the space and lose half the value.
     */
    private static final Pattern TOKEN = Pattern.compile("([^\\s:]+):\"([^\"]*)\"|\"([^\"]*)\"|(\\S+)");

    private final MessageReceivedEvent event;
    private final Map<String, String> values;

    public MentionContext(MessageReceivedEvent event, List<OptionData> options, String argumentTail) {
        this.event = event;
        this.values = parse(options, argumentTail);
    }

    public MessageReceivedEvent event() {
        return event;
    }

    /** Apply the Discord option constraints to the message entry point too. */
    public String validationError(List<OptionData> options) {
        for (OptionData option : options) {
            String name = option.getName();
            String raw = getString(name);
            if (raw == null) {
                if (option.isRequired()) return "Provide `" + name + "`.";
                continue;
            }
            if (option.getType() == OptionType.INTEGER) {
                Integer value = getInt(name);
                if (value == null || (option.getMinValue() != null && value < option.getMinValue().doubleValue())
                        || (option.getMaxValue() != null && value > option.getMaxValue().doubleValue()))
                    return "Provide a valid whole number for `" + name + "`.";
                if (!option.getChoices().isEmpty() && option.getChoices().stream().noneMatch(c -> c.getAsLong() == value))
                    return "Choose a supported value for `" + name + "`.";
            } else if (option.getType() == OptionType.BOOLEAN && getBool(name) == null) {
                return "Use true or false for `" + name + "`.";
            } else if (option.getType() == OptionType.USER && getUserOption(name) == null) {
                return "Mention a reachable user for `" + name + "`.";
            } else if (option.getType() == OptionType.STRING) {
                if ((option.getMinLength() != null && raw.length() < option.getMinLength())
                        || (option.getMaxLength() != null && raw.length() > option.getMaxLength()))
                    return "Check the length of `" + name + "`.";
                if (!option.getChoices().isEmpty()) {
                    var choice = option.getChoices().stream()
                            .filter(c -> c.getAsString().equalsIgnoreCase(raw)).findFirst();
                    if (choice.isEmpty())
                        return "Choose a supported value for `" + name + "`.";
                    // Slash commands only ever deliver the declared value; match that exactly
                    // so handlers relying on it (e.g. Enum.valueOf) do not break on "semester".
                    values.put(name, choice.get().getAsString());
                }
            }
        }
        return null;
    }

    @Override public void replyMessage(net.dv8tion.jda.api.utils.messages.MessageCreateData message) {
        event.getMessage().reply(message).mentionRepliedUser(false)
                .queue(null, e -> BunnyLog.error("[MentionContext] message failed", e));
    }

    @Override
    public JDA getJDA() {
        return event.getJDA();
    }

    @Override
    public User getUser() {
        return event.getAuthor();
    }

    @Override
    public Member getMember() {
        return event.getMember();
    }

    @Override
    public Guild getGuild() {
        return event.isFromGuild() ? event.getGuild() : null;
    }

    @Override
    public MessageChannel getChannel() {
        return event.getChannel();
    }

    @Override
    public boolean isSlash() {
        return false;
    }

    @Override
    protected void doDefer(boolean ephemeral) {
        // No acknowledgement window to satisfy here; the typing indicator is the
        // closest equivalent and tells the user something is happening.
        event.getChannel().sendTyping().queue(null, e -> {});
    }

    @Override
    protected void doReply(MessageEmbed embed, Collection<? extends ActionRow> rows,
                           FileUpload file, boolean ephemeral) {
        // `ephemeral` is unsatisfiable on a normal message. Replying in-channel as a
        // reply to the invocation keeps it attributable without dropping the answer.
        var action = event.getMessage().replyEmbeds(embed).mentionRepliedUser(false);

        if (rows != null && !rows.isEmpty())
            action = action.setComponents(List.copyOf(rows));
        if (file != null)
            action = action.setFiles(file);

        // Logged rather than discarded: a rejected reply looks identical to the bot
        // ignoring the user, so the reason has to surface somewhere.
        action.queue(null, e -> BunnyLog.error("[MentionContext] reply failed", e));
    }

    @Override
    public void replyTransient(MessageEmbed embed) {
        // There is no ephemeral message on this path, so the reply removes itself
        // instead. Deleting our own message needs no permission beyond sending it.
        event.getMessage().replyEmbeds(embed).mentionRepliedUser(false).queue(
                sent -> sent.delete().queueAfter(TRANSIENT_SECONDS, TimeUnit.SECONDS, null, e -> {}),
                e -> BunnyLog.error("[MentionContext] transient reply failed", e));
    }

    @Override
    public void replyContent(String content, Collection<? extends ActionRow> rows) {
        var action = event.getMessage().reply(content).mentionRepliedUser(false);

        if (rows != null && !rows.isEmpty())
            action = action.setComponents(List.copyOf(rows));

        action.queue(null, e -> BunnyLog.error("[MentionContext] content reply failed", e));
    }

    @Override
    public boolean transientRepliesVanish() {
        // Always: there is no ephemeral message on a normal channel reply.
        return true;
    }

    @Override
    public boolean replyModal(Modal modal) {
        // Discord only permits modals in response to an interaction. Reporting this
        // rather than throwing lets the caller point the user at the slash command.
        return false;
    }

    @Override
    public String getString(String name) {
        return values.get(name);
    }

    @Override
    public Integer getInt(String name) {
        String raw = values.get(name);
        if (raw == null)
            return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Boolean getBool(String name) {
        String raw = values.get(name);
        if (raw == null)
            return null;

        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.equals("true") || value.equals("yes") || value.equals("y") || value.equals("1"))
            return Boolean.TRUE;
        if (value.equals("false") || value.equals("no") || value.equals("n") || value.equals("0"))
            return Boolean.FALSE;
        return null;
    }

    @Override
    public GuildChannel getChannelOption(String name) {
        String raw = values.get(name);
        if (raw == null || !event.isFromGuild())
            return null;

        Matcher matcher = CHANNEL_MENTION.matcher(raw.trim());
        String id = matcher.matches() ? matcher.group(1) : raw.trim();

        return id.matches("\\d{17,20}") ? event.getGuild().getGuildChannelById(id) : null;
    }

    @Override
    public User getUserOption(String name) {
        String raw = values.get(name);
        if (raw == null)
            return null;

        Matcher matcher = USER_MENTION.matcher(raw.trim());
        String id = matcher.matches() ? matcher.group(1) : raw.trim();

        if (!id.matches("\\d{17,20}"))
            return null;

        // Mentions resolved on the message come free; fall back to JDA's cache. A
        // blocking REST fetch is deliberately not attempted - this runs on the
        // bounded command pool.
        for (User mentioned : event.getMessage().getMentions().getUsers())
            if (mentioned.getId().equals(id))
                return mentioned;

        return event.getJDA().getUserById(id);
    }

    @Override
    public Member getMemberOption(String name) {
        User user = getUserOption(name);
        if (user == null || !event.isFromGuild())
            return null;

        // Same trade-off as getUserOption: members named in the message arrive resolved,
        // anything else falls back to whatever JDA already holds. Without GUILD_MEMBERS
        // that can miss, and the callers treat a null as "no guild-specific data".
        for (Member mentioned : event.getMessage().getMentions().getMembers())
            if (mentioned.getIdLong() == user.getIdLong())
                return mentioned;

        return event.getGuild().getMemberById(user.getIdLong());
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static Map<String, String> parse(List<OptionData> options, String argumentTail) {
        Map<String, String> resolved = new HashMap<>();
        if (argumentTail == null || argumentTail.isBlank() || options.isEmpty())
            return resolved;

        List<String> tokens = tokenize(argumentTail);
        List<String> positional = new ArrayList<>();

        // Pass 1: name:value pairs, matched only against declared option names so a
        // literal colon in free text ("ratio 3:1") is not mistaken for one.
        for (String token : tokens) {
            int colon = token.indexOf(':');
            boolean named = false;

            if (colon > 0) {
                String key = token.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                for (OptionData option : options) {
                    if (option.getName().equalsIgnoreCase(key)) {
                        resolved.put(option.getName(), token.substring(colon + 1));
                        named = true;
                        break;
                    }
                }
            }

            if (!named)
                positional.add(token);
        }

        // Pass 2: fill what is still empty, in declared order.
        int cursor = 0;
        for (int i = 0; i < options.size() && cursor < positional.size(); i++) {
            OptionData option = options.get(i);
            if (resolved.containsKey(option.getName()))
                continue;

            boolean isLastFillable = isFinalStringOption(options, i, resolved);

            if (isLastFillable && option.getType() == OptionType.STRING) {
                resolved.put(option.getName(), String.join(" ", positional.subList(cursor, positional.size())));
                cursor = positional.size();
            } else {
                resolved.put(option.getName(), positional.get(cursor++));
            }
        }

        return resolved;
    }

    /** True when no later option could still take a positional value. */
    private static boolean isFinalStringOption(List<OptionData> options, int index, Map<String, String> resolved) {
        for (int i = index + 1; i < options.size(); i++)
            if (!resolved.containsKey(options.get(i).getName()))
                return false;
        return true;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(input.trim());

        while (matcher.find()) {
            if (matcher.group(1) != null)
                tokens.add(matcher.group(1) + ":" + matcher.group(2)); // key:"quoted value"
            else if (matcher.group(3) != null)
                tokens.add(matcher.group(3));                          // "quoted value"
            else
                tokens.add(matcher.group(4));                          // plain word
        }

        return tokens;
    }
}
