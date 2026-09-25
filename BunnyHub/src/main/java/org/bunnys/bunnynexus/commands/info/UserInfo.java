package org.bunnys.bunnynexus.commands.info;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.info.InfoEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

public final class UserInfo extends BunnySubcommand {
    public UserInfo() {
        setName("user");
        setDescription("Show account and membership details for a user");
        addAliases("member");
        setExample("/info user user:@someone");
        addOption(new OptionData(OptionType.USER, "user", "Who to look up. Defaults to you", false));
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        User target = ctx.getUserOption("user");

        // A raw value that resolved to nobody means the mention parser could not
        // find who was named. Silently answering about the caller would be worse.
        if (target == null && ctx.getString("user") != null) {
            ctx.reply(InfoEmbeds.unknownUser(), true);
            return;
        }

        Member member;
        if (target == null) {
            target = ctx.getUser();
            member = ctx.getMember();
        } else {
            member = ctx.getMemberOption("user");
        }

        ctx.defer();
        User resolved = target;
        if (member == null && ctx.isFromGuild()) {
            ctx.getGuild().retrieveMemberById(target.getId()).queue(
                    found -> replyUser(client, ctx, resolved, found),
                    failure -> replyUser(client, ctx, resolved, null));
        } else replyUser(client, ctx, resolved, member);
    }

    private static void replyUser(BunnyHub client, CommandContext ctx, User target, Member member) {
        try {
            boolean self = target.getId().equals(ctx.getJDA().getSelfUser().getId());
            ctx.reply(self ? InfoEmbeds.userInfo(target, member, client.getVersion(), client.getDeveloperName(),
                    client.getCommandRegistry().getDeveloperIds().stream().findFirst().orElse(null))
                    : InfoEmbeds.userInfo(target, member));
        } catch (RuntimeException error) {
            String reference = org.bunnys.utils.ErrorReporter.report("info user", null, error);
            ctx.replyTransient(org.bunnys.utils.SystemEmbeds.crashed(reference, ctx.transientRepliesVanish()));
        }
    }
}
