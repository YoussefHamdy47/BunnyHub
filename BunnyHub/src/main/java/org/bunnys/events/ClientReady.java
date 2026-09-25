package org.bunnys.events;

import com.github.lalyos.jfiglet.FigletFont;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.utils.BunnyLog;

import java.io.IOException;
import java.util.Arrays;

@SuppressWarnings("unused")
public class ClientReady extends BunnyEvent {

    public ClientReady(BunnyHub client) {
        super(client);
    }

    @Override
    public void onReady(ReadyEvent event) {
        // Deploy commands cleanly through our single centralized manager
        client.getCommandRegistry().deployCommands(event.getJDA());

        long endTime = System.currentTimeMillis();
        double duration = (endTime - client.getStartTime()) / 1000.0;

        String username = event.getJDA().getSelfUser().getName();
        String asciiArt = renderASCII(username);
        String underline = "_".repeat(longestLine(asciiArt));

        int totalUsers = event.getJDA().getGuilds().stream().mapToInt(Guild::getMemberCount).sum();
        int totalServers = event.getJDA().getGuilds().size();
        int totalCommands = client.getCommandRegistry().getCommandCount(); // Fetched dynamically

        String currentStatus = event.getJDA().getPresence().getStatus().name();
        Activity currentActivity = event.getJDA().getPresence().getActivity();
        String activityName = (currentActivity != null) ? currentActivity.getName() : "No Status";

        BunnyLog.raw(BunnyLog.RED, "\n" + asciiArt);
        BunnyLog.raw(BunnyLog.RED, underline);
        BunnyLog.raw(BunnyLog.RED, username + " is now online! v" + client.getVersion() + " | BunnyHub " + client.getHandlerVersion());

        BunnyLog.raw(BunnyLog.CYAN, "> Total app users: " + String.format("%,d", totalUsers));
        BunnyLog.raw(BunnyLog.CYAN, "> Total Servers: " + String.format("%,d", totalServers));
        BunnyLog.raw(BunnyLog.CYAN, "> Total Commands Loaded: " + totalCommands); // Added dynamic loading metric
        BunnyLog.raw(BunnyLog.CYAN, "---------------------------------");
        BunnyLog.raw(BunnyLog.CYAN, "> Presence: " + currentStatus);
        BunnyLog.raw(BunnyLog.CYAN, "> Activity: " + activityName);

        BunnyLog.raw(BunnyLog.GREEN, "> Startup Time: " + String.format("%.2f", duration) + " seconds");

        event.getJDA().getPresence().setActivity(Activity.playing("BunnyClient"));
    }

    private String renderASCII(String text) {
        try {
            return FigletFont.convertOneLine(text);
        } catch (IOException e) {
            return text;
        }
    }

    private int longestLine(String asciiArt) {
        return Arrays.stream(asciiArt.split("\n")).mapToInt(String::length).max().orElse(0);
    }
}
