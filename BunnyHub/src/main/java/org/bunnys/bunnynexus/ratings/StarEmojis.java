package org.bunnys.bunnynexus.ratings;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Makes sure the star emojis exist as the bot's own application emojis (usable in every server, no server slots), from
 * the images in {@code resources/emojis}, and hands their mentions to {@link Stars}. Missing ones are uploaded once;
 * later starts only list them. Runs on JDA's request threads, never blocking startup.
 */
public final class StarEmojis {
    private StarEmojis() {}

    public static void ensure(JDA jda) {
        jda.retrieveApplicationEmojis().queue(existing -> {
            Map<String, String> byName = new HashMap<>();
            for (ApplicationEmoji emoji : existing) if (Stars.NAMES.contains(emoji.getName())) byName.put(emoji.getName(), emoji.getAsMention());
            var missing = Stars.NAMES.stream().filter(name -> !byName.containsKey(name)).toList();
            if (missing.isEmpty()) { Stars.use(byName); return; }
            for (String name : missing) {
                Icon icon;
                try (InputStream image = StarEmojis.class.getResourceAsStream("/emojis/" + name + ".png")) {
                    if (image == null) { BunnyLog.warning("[Ratings] Star image " + name + " is missing from the build."); return; }
                    icon = Icon.from(image.readAllBytes(), Icon.IconType.PNG);
                } catch (IOException unreadable) {
                    BunnyLog.warning("[Ratings] Could not read star image " + name + ".");
                    return;
                }
                jda.createApplicationEmoji(name, icon).queue(created -> {
                    synchronized (byName) {
                        byName.put(created.getName(), created.getAsMention());
                        if (byName.keySet().containsAll(Stars.NAMES)) {
                            Stars.use(byName);
                            BunnyLog.success("[Ratings] Star emojis uploaded (" + missing.size() + " new).");
                        }
                    }
                }, failure -> BunnyLog.warning("[Ratings] Could not upload star emoji " + name + " | " + FailureDiagnostics.describe(failure)));
            }
        }, failure -> BunnyLog.warning("[Ratings] Could not list the bot's emojis; ratings show without stars | "
                + FailureDiagnostics.describe(failure)));
    }
}
