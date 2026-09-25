package org.bunnys;

import org.bunnys.handler.BunnyHub;

public class Main {
    public static void main(String[] args) {

        BunnyHub client = BunnyHub.create()
                .setDatabaseName("GBF")
                .onShutdown("pending study sessions", org.bunnys.bunnynexus.timers.services.PendingSessionManager::shutdown)
                .onShutdown("GPA menus", org.bunnys.bunnynexus.timers.buttons.GPAPaginator::shutdown)
                .onShutdown("free-game alerts", org.bunnys.bunnynexus.freebies.FreebieSystem::shutdown)
                .setCommandPool(24, 100)
                .setEventPackage("org.bunnys.events")
                .setCommandPackage("org.bunnys.commands")
                .setButtonPackage("org.bunnys.buttons")
                .setModalPackage("org.bunnys.modals")
                .setSelectPackage("org.bunnys.selects")
                .setLogActions(false)
                .setAutoLogin(true)
                .addTestServerIds("1187559385359200316")
                .addDeveloperIds("333644367539470337")
                .setTokenKey("TOKEN")
                .addIntents(net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MESSAGES, net.dv8tion.jda.api.requests.GatewayIntent.DIRECT_MESSAGES)
                .build();

        // Stays off (with a logged reason) unless FREEBIE_REVIEW_CHANNEL_ID and FREEBIE_OWNER_IDS are set in .env.
        org.bunnys.bunnynexus.freebies.FreebieSystem.start(client);

    }
}