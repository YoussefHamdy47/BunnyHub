package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FreebieSectionTest {
    static final String MAIN = "1187559385359200316", OTHER = "1187559385359200317", OWNER = "333644367539470337";

    @Test void everyLauncherHasOneSectionAndMajorsArePriority() {
        assertEquals(List.of(FreebieStore.EPIC, FreebieStore.STEAM, FreebieStore.GOG, FreebieStore.UBISOFT, FreebieStore.EA,
                FreebieStore.BATTLENET), FreebieSection.MAJOR_PC.stores());
        assertEquals(List.of(FreebieStore.ITCHIO, FreebieStore.DRM_FREE, FreebieStore.OTHER), FreebieSection.OTHER_PC.stores());
        int covered = 0;
        for (var section : FreebieSection.values()) {
            covered += section.stores().size();
            assertEquals(section.priority(), section == FreebieSection.MAJOR_PC);
            assertEquals(section, FreebieSection.byCode(String.valueOf(section.code())).orElseThrow());
        }
        assertEquals(FreebieStore.values().length, covered);
        assertEquals(FreebieSection.values().length, Arrays.stream(FreebieSection.values()).map(FreebieSection::code).distinct().count());
        assertTrue(FreebieSection.byCode("mm").isEmpty());
        var some = EnumSet.of(FreebieSection.MOBILE, FreebieSection.MAJOR_PC);
        assertEquals("mp", FreebieSection.encode(some));
        assertEquals(some, FreebieSection.decode("pm"));
        assertEquals(EnumSet.of(FreebieSection.CONSOLE), FreebieSection.decode("c?x"));
    }

    @Test void minorSectionsCanBeReviewedInTheirOwnChannel() {
        List<String> problems = new ArrayList<>();
        var single = FreebieConfig.load(Map.of("FREEBIE_REVIEW_CHANNEL_ID", MAIN, "FREEBIE_OWNER_IDS", OWNER)::get, problems).orElseThrow();
        assertEquals(MAIN, single.reviewChannelFor(FreebieStore.ITCHIO));
        assertEquals(MAIN, single.reviewChannelFor(FreebieStore.STEAM));

        var split = FreebieConfig.load(Map.of("FREEBIE_REVIEW_CHANNEL_ID", MAIN, "FREEBIE_OTHER_REVIEW_CHANNEL_ID", OTHER,
                "FREEBIE_OWNER_IDS", OWNER)::get, problems).orElseThrow();
        assertEquals(MAIN, split.reviewChannelFor(FreebieStore.EPIC));
        assertEquals(OTHER, split.reviewChannelFor(FreebieStore.ITCHIO));
        assertEquals(OTHER, split.reviewChannelFor(FreebieStore.ANDROID));
        assertTrue(split.isReviewChannel(MAIN) && split.isReviewChannel(OTHER));
        assertFalse(split.isReviewChannel("1187559385359200318"));
        assertTrue(problems.isEmpty());

        // A mistyped second channel fails closed instead of silently reviewing in the wrong place.
        assertTrue(FreebieConfig.load(Map.of("FREEBIE_REVIEW_CHANNEL_ID", MAIN, "FREEBIE_OTHER_REVIEW_CHANNEL_ID", "general",
                "FREEBIE_OWNER_IDS", OWNER)::get, problems).isEmpty());
    }

    @Test void aBrokenSecondReviewChannelFallsBackToTheMainOne() throws Exception {
        var config = new FreebieConfig(MAIN, OTHER, Set.of(OWNER), 10);
        var repository = mock(FreebieRepository.class);
        var subscriptions = mock(FreebieSubscriptions.class);
        when(subscriptions.audience(any())).thenReturn(new FreebieSubscriptions.Audience(0, 0));
        var jda = mock(JDA.class);
        var main = mock(GuildMessageChannel.class, RETURNS_DEEP_STUBS);
        when(main.canTalk()).thenReturn(true);
        var posted = mock(Message.class);
        when(posted.getId()).thenReturn("1234567890123456789");
        when(main.sendMessage(any(MessageCreateData.class)).setNonce(anyString()).timeout(anyLong(), any()).submit())
                .thenReturn(CompletableFuture.completedFuture(posted));
        when(jda.getChannelById(GuildMessageChannel.class, MAIN)).thenReturn(main);
        when(jda.getChannelById(GuildMessageChannel.class, OTHER)).thenReturn(null); // deleted, or the bot lost access

        var itch = FreebieUnitTest.offer("Indie", FreebieStore.ITCHIO);
        var store = mock(FreebieReviewStore.class);
        when(repository.reviews()).thenReturn(store);
        when(store.destination(any(), anyString(), any())).thenReturn(true);
        when(store.owns(any(), any())).thenReturn(true);
        var claim = new FreebieReviewStore.Claim(itch, "token", false, null);
        new FreebieReviewChannel(config, repository, subscriptions, () -> jda, Runnable::run, java.time.Clock.systemUTC()).post(jda, claim);
        verify(store).sent(eq(claim), eq(MAIN), eq("1234567890123456789"), any());
    }

    @Test void onlyPriorityReviewsPingTheOwners() {
        try (var major = FreebieMessages.review(FreebieUnitTest.offer("X", FreebieStore.STEAM), 1, 1, Set.of(OWNER));
             var minor = FreebieMessages.review(FreebieUnitTest.offer("Y", FreebieStore.ITCHIO), 1, 1, Set.of(OWNER))) {
            assertEquals(Set.of(OWNER), major.getMentionedUsers());
            assertTrue(major.getContent().startsWith("<@" + OWNER + ">"));
            assertTrue(minor.getMentionedUsers().isEmpty());
            assertFalse(minor.getContent().contains("<@"));
            assertTrue(minor.getContent().contains("indie & other pc"), minor.getContent());
        }
    }
}
