package org.bunnys.bunnynexus.commands.alerts;

import java.util.*;
import org.bunnys.bunnynexus.alerts.domain.GuildConfiguration;
import org.bunnys.handler.utils.InteractionErrors;

/** One setting per page keeps even maximum-sized aggregates within Discord's message limit. */
public final class FreebieSettings {
    private FreebieSettings() {}
    public static String render(GuildConfiguration config, int page) {
        int pages = Math.max(1, config.destinations().stream().mapToInt(d -> d.subscriptions().isEmpty() ? 1
                : d.subscriptions().stream().mapToInt(s -> Math.max(1, (s.roles().size() + 24) / 25)).sum()).sum());
        if (page < 1 || page > pages) throw new InteractionErrors.InputFailure("Choose a settings page between 1 and " + pages + ".");
        String row = "No destinations. Use /freebie setup with this revision.";
        int currentPage = 0;
        outer:
        for (var d : config.destinations()) {
            String channel = "Channel ID: " + d.ref().channelId() + " (" + state(d.enabled()) + ")";
            if (d.subscriptions().isEmpty() && ++currentPage == page) {
                row = channel + "\nNo store settings."; break;
            }
            for (var s : d.subscriptions()) {
                int rolePages = Math.max(1, (s.roles().size() + 24) / 25);
                if (currentPage + rolePages < page) { currentPage += rolePages; continue; }
                var roles = s.roles().stream().sorted().toList();
                int offset = (page - currentPage - 1) * 25;
                row = channel + "\nStore: " + safe(s.store().value())
                    + "\nMarket: " + safe(s.market().value()) + "\nTopic: " + s.topic()
                    + "\nSubscription: " + state(s.enabled()) + "\nRole IDs: "
                    + (s.roles().isEmpty() ? "none" : String.join(", ", roles.subList(offset, Math.min(offset + 25, roles.size()))) + " (roles " + (offset + 1) + "-" + Math.min(offset + 25, roles.size()) + "/" + roles.size() + ")")
                    + "\nNew observations since: " + s.enabledSince();
                break outer;
            }
        }
        return "Free-game settings — revision " + config.revision() + "\nServer: "
                + (config.deleted() ? "not configured" : state(config.enabled()))
                + "\nPage " + page + "/" + pages + "\n"
                + row
                + "\nSettings describe subscriptions, not delivery health. Use this revision for edits; /freebie setup replaces that store's market, roles and enabled state.";
    }
    private static String state(boolean enabled) { return enabled ? "enabled" : "disabled"; }
    private static String safe(String text) {
        // Identifiers may contain formatting; render inert text without expanding their bounded size.
        return text.replaceAll("[@`*_~|<>\\\\]", "·");
    }
}

