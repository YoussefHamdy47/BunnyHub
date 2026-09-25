package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in, test-owned three-node replica set. No URI/environment configuration or existing database access. */
final class LocalReplicaSet implements AutoCloseable {
    final String name = "bunny_verify_" + UUID.randomUUID().toString().replace("-", "");
    final Path directory;
    final List<Integer> ports = new ArrayList<>();
    final List<Process> processes = new ArrayList<>();
    final List<MongoClient> direct = new ArrayList<>();
    MongoClient client;

    LocalReplicaSet() throws Exception {
        String executable = System.getProperty("bunny.test.mongod");
        if (executable == null || !Files.isRegularFile(Path.of(executable)))
            throw new IllegalStateException("Explicit -Dbunny.test.mongod=<local mongod executable> required; no external Mongo URI accepted.");
        Path root = Path.of(".tools", "replica-tests").toAbsolutePath().normalize();
        Files.createDirectories(root);
        directory = Files.createTempDirectory(root, "run-");
        try {
            for (int i = 0; i < 3; i++) {
                int port;
                do { try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); } }
                while (ports.contains(port));
                ports.add(port);
                Path data = Files.createDirectory(directory.resolve("node-" + i));
                var command = List.of(Path.of(executable).toAbsolutePath().toString(), "--bind_ip", "127.0.0.1",
                        "--port", Integer.toString(port), "--dbpath", data.toString(), "--replSet", name,
                        "--oplogSize", "64", "--wiredTigerCacheSizeGB", "0.25", "--setParameter", "enableTestCommands=1",
                        "--setParameter", "diagnosticDataCollectionEnabled=false", "--logpath", data.resolve("mongo.log").toString());
                processes.add(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(data.resolve("console.log").toFile()).start());
                var connection = connect("mongodb://127.0.0.1:" + port + "/?directConnection=true", 2);
                direct.add(connection);
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (true) {
                    if (!processes.get(i).isAlive()) throw new IllegalStateException("Test mongod exited; inspect " + data);
                    try {
                        var options = connection.getDatabase("admin").runCommand(new Document("getCmdLineOpts", 1));
                        var parsed = options.get("parsed", Document.class);
                        if (!Path.of(parsed.get("storage", Document.class).getString("dbPath")).toAbsolutePath().normalize().equals(data)
                                || !name.equals(parsed.get("replication", Document.class).getString("replSet")))
                            throw new IllegalStateException("Refusing non-test server on reserved port.");
                        break;
                    } catch (MongoException unavailable) {
                        if (System.nanoTime() >= deadline) throw unavailable;
                        Thread.sleep(100);
                    }
                }
            }
            var members = new ArrayList<Document>();
            for (int i = 0; i < 3; i++) members.add(new Document("_id", i).append("host", "127.0.0.1:" + ports.get(i)));
            direct.getFirst().getDatabase("admin").runCommand(new Document("replSetInitiate",
                    new Document("_id", name).append("members", members)
                            .append("settings", new Document("electionTimeoutMillis", 2000))));
            String hosts = String.join(",", ports.stream().map(p -> "127.0.0.1:" + p).toList());
            client = connect("mongodb://" + hosts + "/?replicaSet=" + name, 20);
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            System.out.println("TEST_REPLICA_SET " + directory + " MongoDB=" + client.getDatabase("admin")
                    .runCommand(new Document("buildInfo", 1)).getString("version"));
        } catch (Exception | Error failure) { close(); throw failure; }
    }

    private static MongoClient connect(String uri, int seconds) {
        return MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .applicationName("bunny-isolated-verification").timeout(seconds, TimeUnit.SECONDS)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(seconds, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(2, TimeUnit.SECONDS).readTimeout(seconds, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(b -> b.maxSize(24).maxWaitTime(seconds, TimeUnit.SECONDS))
                .writeConcern(WriteConcern.MAJORITY).readConcern(ReadConcern.MAJORITY).build());
    }

    MongoAlertDatabase database() {
        return new MongoAlertDatabase(client, "bunny_it_" + UUID.randomUUID().toString().replace("-", ""), Duration.ofSeconds(15));
    }

    int primary() {
        for (int i = 0; i < direct.size(); i++) {
            if (processes.get(i).isAlive() && Boolean.TRUE.equals(direct.get(i).getDatabase("admin")
                    .runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) return i;
        }
        throw new IllegalStateException("No test primary.");
    }

    void failOnce(String command, int code, String label) {
        direct.get(primary()).getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand")
                .append("mode", new Document("times", 1)).append("data", new Document("failCommands", List.of(command))
                        .append("appName", "bunny-isolated-verification").append("errorCode", code).append("errorLabels", List.of(label))));
    }

    @Override public void close() {
        if (client != null) client.close();
        direct.forEach(MongoClient::close);
        // Only handles created by this fixture are stopped; logs/data retained for diagnosis.
        processes.forEach(Process::destroy);
        for (var process : processes) {
            try { if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
            catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}
