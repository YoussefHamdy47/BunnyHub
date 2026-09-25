package org.bunnys.handler.utils;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.bunnys.utils.BunnyLog;

public class TokenLoader {
    private static Dotenv dotenv;

    private static synchronized void init() {
        if (dotenv == null) {
            try {
                var directory = java.nio.file.Files.exists(java.nio.file.Path.of(".env"))
                        || !java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/.env"))
                        ? "." : "src/main/resources";
                dotenv = Dotenv.configure().directory(directory).ignoreIfMissing().load();
            } catch (DotenvException e) {
                BunnyLog.warning("No .env file found. Searching system environment variables...");
                dotenv = Dotenv.configure().ignoreIfMissing().load();
            }
        }
    }

    public static String getEnv(String key) {
        init();
        String value = dotenv.get(key);

        if (value == null || value.trim().isEmpty()) {
            BunnyLog.warning("Environment variable '" + key + "' not found.");
            return null;
        }
        return value;
    }

    /** Optional keys: returns null without logging a warning. */
    public static String getEnvQuietly(String key) {
        init();
        String value = dotenv.get(key);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public static String getToken(String tokenKey) {
        init();

        String keyToSearch = (tokenKey != null && !tokenKey.isEmpty()) ? tokenKey : "DISCORD_TOKEN";

        String token = dotenv.get(keyToSearch);

        if (token == null || token.trim().isEmpty()) {
            BunnyLog.error("Failed to load token. Key '" + keyToSearch + "' not found.");
            throw new IllegalArgumentException("Missing Discord Token");
        }

        BunnyLog.info("Discord token retrieved successfully using key: " + keyToSearch);
        return token;
    }
}
