package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** "Send without ping": the alert still posts, with no role mention; the confirmations offer both choices. */
class FreebieQuietSendTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", ROLE = "300000000000000000";
    final Instant now = Instant.parse("2026-09-25T12:00:00Z"); // Before the shared test offer ends.
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieDeliveryStore deliveries = mock(FreebieDeliveryStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final SelfMember self = mock(SelfMember.class);
    final StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
    final MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final FreebieOffer offer = FreebieUnitTest.offer("Game", FreebieStore.EPIC);

    @AfterEach void stop() { executor.shutdownNow(); }

    @BeforeEach void setup() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(guild.getRoleById(ROLE)).thenReturn(mock(Role.class));
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        var user = mock(SelfUser.class);
        when(user.getId()).thenReturn("bot");
        when(client.getSelfUser()).thenReturn(user);
        when(repository.deliveries()).thenReturn(deliveries);
        when(deliveries.owns(any(), any())).thenReturn(true);
        when(deliveries.submitting(any(), any())).thenReturn(true);
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(send);
        when(send.submit()).thenReturn(new CompletableFuture<>());
    }

    MessageCreateData sent(boolean quiet) throws Exception {
        when(repository.quiet(offer.id())).thenReturn(quiet);
        var job = new FreebieRepository.Claimed(offer.id() + "|" + CHANNEL, offer.id(), GUILD, CHANNEL, Optional.of(ROLE), 1, false, "t");
        when(repository.claim(any(), any(Duration.class))).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var sender = new FreebieSender(repository, () -> client, executor, clock, new FreebieNotifier(repository, () -> client, clock, executor));
        executor.submit(sender::tick).get(5, TimeUnit.SECONDS);
        var captor = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(channel, timeout(5000)).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test void aNormalApprovalPingsOnlyTheSavedRole() throws Exception {
        var message = sent(false);
        assertEquals("<@&" + ROLE + ">", message.getContent());
        assertEquals(Set.of(ROLE), message.getMentionedRoles());
    }

    @Test void sendWithoutPingPostsTheSameAlertWithNoMention() throws Exception {
        var message = sent(true);
        assertEquals("", message.getContent());
        assertTrue(message.getMentionedRoles().isEmpty());
        assertTrue(message.getAllowedMentions().isEmpty());
        assertFalse(message.getEmbeds().isEmpty(), "the alert itself is unchanged");
    }

    static List<String> buttons(MessageCreateData data) {
        return data.getComponents().stream().flatMap(c -> ((ActionRow) c).getComponents().stream())
                .map(b -> ((Button) b).getCustomId()).toList();
    }

    @Test void everyConfirmationOffersSendingWithoutAPing() {
        var single = buttons(FreebieMessages.confirm(offer, 3, 2));
        assertEquals(List.of(FreebieMessages.BUTTON_PREFIX + ":confirm:" + offer.id(),
                FreebieMessages.BUTTON_PREFIX + ":" + FreebieMessages.CONFIRM_QUIET + ":" + offer.id()), single);
        String token = "0123456789abcdef01234567";
        var release = buttons(FreebieGroupApproval.confirm(offer, List.of(offer, offer), true, token, 3, 2));
        assertEquals(List.of(FreebieMessages.BUTTON_PREFIX + ":confirmrelease-r-" + token + ":" + offer.id(),
                FreebieMessages.BUTTON_PREFIX + ":confirmrelease-R-" + token + ":" + offer.id()), release);
        assertTrue(release.stream().allMatch(id -> id.length() <= 100), "Discord's custom ID limit");
    }
}
