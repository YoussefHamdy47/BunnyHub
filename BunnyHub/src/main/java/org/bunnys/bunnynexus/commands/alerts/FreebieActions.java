package org.bunnys.bunnynexus.commands.alerts;

import java.util.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.bunnys.handler.utils.InteractionErrors;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.domain.ConfigurationChange.*;

/** Trusted interaction boundary. Called only by the bounded command worker after private defer. */
public final class FreebieActions {
    private final ConfigurationService service;
    public FreebieActions(ConfigurationService service) { this.service = Objects.requireNonNull(service); }

    public String run(SlashCommandInteractionEvent event, String action) {
        try {
            requireActor(event);
            if (action.equals("status")) {
                var config = service.view(event.getUser().getId(), event.getGuild().getId());
                int page = event.getOption("page") == null ? 1 : Math.toIntExact(event.getOption("page").getAsLong());
                return FreebieSettings.render(config, page);
            }
            var change = parseChange(event, action);
            var result = service.change(change);
            return switch (result.status()) {
                case APPLIED -> "Settings saved. Revision: " + result.revision() + "." + boundary(change.operation());
                case REPLAYED -> "This interaction was already saved at revision " + result.revision() + "." + boundary(change.operation());
                case REVISION_CONFLICT -> "Settings changed. Run /freebie status and review the latest revision before trying again.";
                case IDEMPOTENCY_CONFLICT -> "This interaction does not match its saved request. Run /freebie status to review settings.";
            };
        } catch (ConfigurationAccess.AccessDenied denied) {
            return "Access could not be verified. You need Manage Server or Administrator; check the bot's channel and role permissions too.";
        } catch (ConfigurationPolicy.Rejected rejected) {
            return switch (rejected.failure()) {
                case REVISION_CONFLICT -> "Settings changed. Run /freebie status and review the latest revision.";
                case NOT_FOUND -> "That setting no longer exists. Run /freebie status to reload.";
                case LIMIT_REACHED -> "This server has reached a configured destination, subscription or role limit.";
                case UNSUPPORTED_SELECTION -> "That store or market is not supported by the current configuration policy.";
            };
        } catch (InteractionErrors.InputFailure invalid) {
            return InteractionErrors.userMessage(invalid);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(FreebieActions.class).error("Freebie interaction {} failed | {}",
                    event.getId(), org.bunnys.utils.FailureDiagnostics.describe(failure));
            return "The outcome could not be confirmed. Run /freebie status before making another change. Request ID: " + event.getId();
        }
    }

    private static ConfigurationChange parseChange(SlashCommandInteractionEvent event, String action) {
        try {
            // Revision is explicit input from status, never silently refreshed before writing.
            long revision = Long.parseLong(required(event, "revision"));
            if (revision < 0) throw new IllegalArgumentException();
            Operation operation = switch (action) {
                case "setup" -> new PutSubscription(required(event, "channel"),
                        new StoreId(required(event, "store")), new Market(required(event, "market")),
                        Topic.FREE_GAME, enabled(event), roles(required(event, "roles")));
                case "toggle" -> scoped(event, false);
                case "remove" -> scoped(event, true);
                default -> throw new IllegalArgumentException();
            };
            return new ConfigurationChange(event.getId(), event.getUser().getId(), event.getGuild().getId(), revision, operation);
        } catch (InteractionErrors.InputFailure invalid) {
            throw invalid;
        } catch (IllegalArgumentException invalid) {
            throw new InteractionErrors.InputFailure("Invalid settings. Use channel and role IDs, an exact store/market identifier, and the revision from /freebie status.");
        }
    }

    private static boolean enabled(SlashCommandInteractionEvent event) {
        var option = event.getOption("enabled");
        if (option == null) throw new IllegalArgumentException();
        return option.getAsBoolean();
    }

    private static void requireActor(SlashCommandInteractionEvent e) {
        var member = e.getMember();
        if (!e.isFromGuild() || e.getGuild() == null || member == null
                || !member.getId().equals(e.getUser().getId()) || !member.getGuild().getId().equals(e.getGuild().getId())
                || !(member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.ADMINISTRATOR)))
            throw new ConfigurationAccess.AccessDenied();
    }
    private static String required(SlashCommandInteractionEvent e, String name) {
        var option = e.getOption(name);
        if (option == null) throw new IllegalArgumentException();
        return option.getAsString();
    }
    private static Set<String> roles(String text) {
        if (text.equals("none")) return Set.of();
        if (text.length() > 2200) throw new IllegalArgumentException();
        var roles = new HashSet<String>();
        for (String role : text.split(",", -1)) roles.add(snowflake(role.strip()));
        if (roles.size() > Subscription.MAX_ROLE_IDS) throw new IllegalArgumentException();
        return Set.copyOf(roles);
    }
    private static Operation scoped(SlashCommandInteractionEvent e, boolean remove) {
        String scope = required(e, "scope");
        String channel = e.getOption("channel") == null ? null : required(e, "channel");
        String store = e.getOption("store") == null ? null : required(e, "store");
        boolean enabled = !remove && enabled(e);
        if (scope.equals("server") && channel == null && store == null)
            return remove ? new RemoveGuild() : new SetGuildEnabled(enabled);
        if (scope.equals("channel") && channel != null && store == null)
            return remove ? new RemoveDestination(channel) : new SetDestinationEnabled(channel, enabled);
        if (scope.equals("store") && channel != null && store != null)
            return remove ? new RemoveSubscription(channel, new StoreId(store), Topic.FREE_GAME)
                    : new SetSubscriptionEnabled(channel, new StoreId(store), Topic.FREE_GAME, enabled);
        throw new InteractionErrors.InputFailure("Server scope takes no channel/store; channel scope requires only channel; store scope requires both.");
    }
    private static String boundary(Operation operation) {
        boolean stopping = switch (operation) {
            case RemoveGuild p -> true;
            case RemoveDestination p -> true;
            case RemoveSubscription p -> true;
            case SetGuildEnabled p -> !p.enabled();
            case SetDestinationEnabled p -> !p.enabled();
            case SetSubscriptionEnabled p -> !p.enabled();
            case PutSubscription p -> !p.enabled();
        };
        return stopping ? " An already-authorized alert may still arrive." : " This does not start alert delivery; enabling uses new observations without automatic catch-up.";
    }
}


