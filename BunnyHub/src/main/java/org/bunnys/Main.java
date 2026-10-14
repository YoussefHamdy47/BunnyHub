package org.bunnys;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.utils.TokenLoader;

public class Main {
    public static void main(String[] args) {
        // MONGO_DB_NAME overrides the database (default GBF); a test instance refuses to run on GBF.
        String database = Deployment.databaseName(TokenLoader::getEnvQuietly);
        Deployment.requireSafe(Deployment.testInstance(), database);
        // Commands, events, buttons, modals, selects and services are discovered under org.bunnys.<kind>.
        BunnyHub.create()
                .setBasePackage("org.bunnys")
                .setDatabaseName(database)
                .setTokenKey("TOKEN")
                .setCommandPool(24, 100)
                .addIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES,
                        // Privileged: welcome/goodbye messages and join roles. Enable "Server Members Intent" in the Developer Portal.
                        GatewayIntent.GUILD_MEMBERS)
                .addDeveloperIds("333644367539470337")
                .addTestServerIds("1187559385359200316")
                .setLogActions(false)
                .build();
    }
}
