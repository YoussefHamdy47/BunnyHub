package org.bunnys.services;

import org.bunnys.bunnynexus.mailbridge.*;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.FailureDiagnostics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Gmail → Discord task notifications. Stays off, with a logged reason, unless the GMAIL_* keys are set. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class MailBridgeService implements BunnyService {
    @Override public String name() { return "mail bridge"; }

    @Override public void start(BunnyHub client) {
        List<String> problems = new ArrayList<>();
        var config = MailBridgeConfig.fromEnvironment(problems);
        if (config.isEmpty()) {
            MailBridge.disable(String.join("; ", problems));
            return;
        }
        MongoMailLedger ledger;
        try {
            ledger = new MongoMailLedger(DB.getDatabase());
            ledger.installIndexes();
        } catch (RuntimeException failure) {
            MailBridge.disable("database setup failed | " + FailureDiagnostics.describe(failure));
            return;
        }
        MailBridge.start(config.get(), new GmailApi(config.get(), Clock.systemUTC()), ledger, new JdaMailPoster(client::getJDA));
    }

    @Override public void shutdown() { MailBridge.shutdown(); }
}
