package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.bunnys.bunnynexus.alerts.application.ConfigurationRepository.Receipt;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.AlertDocuments.*;
import static org.bunnys.bunnynexus.alerts.domain.GuildConfiguration.*;

/** Current bounded configuration aggregate, with compatible v1 send/fan-out projections. */
final class ConfigurationDocuments {
    private ConfigurationDocuments() {}
    static Document configuration(GuildConfiguration value) {
        var result = base(value.guildId()).append("revision", value.revision()).append("enabled", value.enabled())
                .append("deleted", value.deleted()).append("destinations", value.destinations().stream().map(d -> {
                    var document = destination(d);
                    document.append("subscriptions", d.subscriptions().stream().map(s -> subscription(d.projection(s, value.enabled()))).toList());
                    return document;
                }).toList());
        if (result.toJson().getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024)
            throw new IllegalArgumentException("Configuration exceeds technical document budget.");
        return result;
    }
    static GuildConfiguration configuration(Document document) {
        version(document); String guildId = string(document, "_id");
        var guild = base(guildId).append("enabled", bool(document, "enabled"));
        List<Destination> destinations = new ArrayList<>();
        int total = 0;
        for (var d : documents(document, "destinations", MAX_DESTINATIONS)) {
            version(d);
            var ref = new AlertIdentity.DestinationRef(string(d, "guildId"), string(d, "channelId"), string(d, "_id"), string(d, "incarnation"));
            List<Setting> settings = new ArrayList<>();
            for (var s : documents(d, "subscriptions", MAX_SUBSCRIPTIONS)) {
                if (++total > MAX_SUBSCRIPTIONS) throw new IllegalArgumentException("Too many subscriptions.");
                var sub = subscription(s, guild, d);
                if (!sub.destination().equals(ref)) throw new IllegalArgumentException("Foreign nested subscription.");
                settings.add(new Setting(sub.id(), sub.store(), sub.market(), sub.topic(), sub.enabled(), sub.enabledSince(), sub.revision(), sub.roleIds()));
            }
            destinations.add(new Destination(ref, number(d, "revision"), bool(d, "enabled"), settings));
        }
        return new GuildConfiguration(guildId, number(document, "revision"), bool(document, "enabled"), bool(document, "deleted"), destinations);
    }
    static Document destination(Destination d) {
        return base(d.ref().destinationId()).append("guildId", d.ref().guildId()).append("channelId", d.ref().channelId())
                .append("incarnation", d.ref().incarnation()).append("revision", d.revision()).append("enabled", d.enabled());
    }
    static Document receipt(Receipt receipt, ConfigurationChange change) {
        return base(receipt.actionId()).append("guildId", receipt.guildId()).append("actorId", receipt.actorId())
                .append("fingerprint", receipt.fingerprint()).append("revision", receipt.revision())
                .append("previousRevision", change.expectedRevision()).append("committedAt", date(receipt.committedAt()))
                .append("intent", change.identityParts());
    }
    static Receipt receipt(Document document) {
        version(document);
        return new Receipt(string(document, "_id"), string(document, "actorId"), string(document, "guildId"),
                string(document, "fingerprint"), number(document, "revision"), instant(document, "committedAt"));
    }
    private static List<Document> documents(Document parent, String field, int limit) {
        if (!(parent.get(field) instanceof List<?> values) || values.size() > limit) throw new IllegalArgumentException("Invalid bounded configuration list.");
        List<Document> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Document d)) throw new IllegalArgumentException("Expected configuration document.");
            result.add(d);
        }
        return result;
    }
    private static boolean bool(Document document, String field) {
        if (!(document.get(field) instanceof Boolean b)) throw new IllegalArgumentException("Expected boolean: " + field);
        return b;
    }
}
