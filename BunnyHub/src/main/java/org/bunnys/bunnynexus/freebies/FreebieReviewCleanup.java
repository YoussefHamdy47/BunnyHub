package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The review channel's Clear finished button: deletes the bot's own messages there that no longer need an owner
 * (decided reviews, ended alerts, notices), and keeps anything with Approve, Reject, Stop or the launcher picker.
 * Only the bot's own messages are touched, so this needs Read Message History but no Manage Messages permission
 * and no privileged intent.
 */
public final class FreebieReviewCleanup {
    /** How far back one click looks. */
    static final int LOOK_BACK = 300;

    public record Result(int deleted, int kept) {}

    private FreebieReviewCleanup() {}

    public static CompletableFuture<Result> clear(GuildMessageChannel channel) {
        String self = channel.getJDA().getSelfUser().getId();
        return channel.getIterableHistory().takeAsync(LOOK_BACK).thenCompose(history -> {
            List<Message> finished = new ArrayList<>();
            int kept = 0;
            for (Message message : history) {
                if (!message.getAuthor().getId().equals(self)) continue;
                if (FreebieMessages.needsOwner(message.getComponents())) kept++;
                else finished.add(message);
            }
            int keptCount = kept;
            if (finished.isEmpty()) return CompletableFuture.completedFuture(new Result(0, keptCount));
            var deletions = channel.purgeMessages(finished);
            return CompletableFuture.allOf(deletions.toArray(CompletableFuture[]::new))
                    .handle((ignored, failure) -> new Result((int) deletions.stream().filter(d -> !d.isCompletedExceptionally()).count(), keptCount));
        });
    }
}
