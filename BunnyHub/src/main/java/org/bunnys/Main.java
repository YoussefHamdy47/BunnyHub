package org.bunnys;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bunnys.handler.BunnyHub;

public class Main {
    public static void main(String[] args) {
        // Commands, events, buttons, modals, selects and services are discovered under org.bunnys.<kind>.
        BunnyHub.create()
                .setBasePackage("org.bunnys")
                .setDatabaseName("GBF")
                .setTokenKey("TOKEN")
                .setCommandPool(24, 100)
                .addIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES)
                .addDeveloperIds("333644367539470337")
                .addTestServerIds("1187559385359200316")
                .setLogActions(false)
                .build();
    }
}
