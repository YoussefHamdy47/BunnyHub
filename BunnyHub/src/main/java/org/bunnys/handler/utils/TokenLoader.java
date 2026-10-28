package org.bunnys.handler.utils;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.bunnys.utils.BunnyLog;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads settings from the environment or a {@code .env} file, never from the packaged jar. */
public class TokenLoader {
    /** {@code -Dbunny.env.file=.env.test} reads that file (in the working directory) instead of {@code .env}. */
    public static final String ENV_FILE_PROPERTY = "bunny.env.file";
    private static Dotenv dotenv;

    /**
     * The explicitly requested env file, if any. A requested file that does not exist is an error: silently
     * falling back to {@code .env} would start a test instance with production settings.
     */
    static String requestedFile(String requested, java.util.function.Predicate<String> exists) {
        if (requested == null || requested.isBlank()) return null;
        String name = requested.strip();
        if (name.contains("/") || name.contains("\\") || !name.startsWith(".env"))
            throw new IllegalStateException(ENV_FILE_PROPERTY + " must name an .env* file in the working directory.");
        if (!exists.test(name)) throw new IllegalStateException("Env file " + name + " not found; refusing to fall back to .env.");
        return name;
    }

    private static synchronized void init() {
        if (dotenv == null) {
            String requested = requestedFile(System.getProperty(ENV_FILE_PROPERTY), name -> Files.exists(Path.of(name)));
            if (requested != null) {
                dotenv = Dotenv.configure().directory(".").filename(requested).load();
                BunnyLog.info("Settings loaded from " + requested + " (not .env).");
                return;
            }
            try {
                var directory = Files.exists(Path.of(".env"))
                        || !Files.exists(Path.of("src/main/resources/.env"))
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
