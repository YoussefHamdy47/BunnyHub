package org.bunnys.bunnynexus.timers;

import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.handler.utils.InteractionErrors.StateFailure;

import com.mongodb.client.model.Filters;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.bunnynexus.timers.services.TimerAccountService;
import org.bunnys.bunnynexus.timers.services.TimerSubjectService;
import org.bunnys.utils.AppDesign;

import java.awt.Color;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.*;

@SuppressWarnings("unused")
public class Timers {

    public enum RecordDestination {
        ACCOUNT,
        SEMESTER
    }

    // Miami pink is too ugly idc enough to update and remove the old one, so just
    // use cyan for now
    private static final Color MIAMI_PINK = AppDesign.ColorCodes.CYAN;
    private static final Color MIAMI_CYAN = new Color(5, 217, 232);

    private final String userId;
    private final IReplyCallback interaction;

    private BunnyUser cachedUser = null;
    private TimerData cachedTimerData = null;
    private boolean isDataLoaded = false;

    public Timers(String userId, IReplyCallback interaction) {
        this.userId = userId;
        this.interaction = interaction;
    }

    private void loadData() {
        if (isDataLoaded)
            return;
        this.cachedUser = DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId));
        this.cachedTimerData = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
        this.isDataLoaded = true;
    }

    public void checkUser() {
        loadData();
        if (cachedUser == null || cachedTimerData == null)
            throw new StateFailure(
                    "You don't have a BunnyHub Timers account. Use `/timer register <semester>` first.");
    }

    public void checkSemester() {
        checkUser();
        if (cachedTimerData.getCurrentSemester() == null
                || cachedTimerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("You don't have an active semester. Register one to begin tracking time.");
    }

    public MessageEmbed register(String semesterName) {
        if (semesterName == null || semesterName.trim().isEmpty())
            throw new InputFailure(
                    "You must provide a semester name to register (e.g., `" + getCurrentSemester() + "`).");

        loadData();
        String cleanName = semesterName.trim();
        boolean isBrandNewAccount = false;

        if (cachedUser == null || cachedTimerData == null) {
            TimerAccountService.registerAccount(userId);
            isBrandNewAccount = true;
            this.isDataLoaded = false;
            loadData();
        }

        if (cachedTimerData.getCurrentSemester() != null
                && cachedTimerData.getCurrentSemester().getSemesterName() != null)
            throw new StateFailure(
                    "Semester `" + cachedTimerData.getCurrentSemester().getSemesterName() + "` is already active.\n" +
                            "Use `/timer end_semester` to close it before starting a new one.");

        TimerAccountService.registerSemester(userId, cleanName);

        String userName = interaction.getUser().getEffectiveName();
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(MIAMI_PINK);
        embed.setTitle("🌴 Welcome to BunnyHub");
        embed.setTimestamp(Instant.now());

        if (isBrandNewAccount) {
            embed.setDescription("Welcome aboard, **" + userName + "**.\n\nYour account has been registered and **"
                    + cleanName + "** is now active.");
        } else {
            embed.setDescription("Successfully registered **" + cleanName
                    + "**.\n\n> A new chapter has been added to your academic record.");
        }

        embed.setFooter("🌴 Semester file opened");
        return embed.build();
    }

    public MessageEmbed addSubject(RecordDestination destination, Subject subject) {
        String code = subject.getSubjectCode().toUpperCase(java.util.Locale.ROOT);
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(MIAMI_PINK);
        embed.setTimestamp(Instant.now());

        if (destination == RecordDestination.SEMESTER) {
            checkSemester();
            TimerSubjectService.addSubjectToSemester(userId, subject);

            embed.setTitle(AppDesign.Emojis.WHITE_HEART_SPIN + " Course Added");
            embed.setDescription("🍸 Module **" + code + "** — *" + subject.getSubjectName()
                    + "* has been added to the current semester.\n\n" +
                    "> " + AppDesign.Emojis.DIAMOND_SPIN + " Telemetry tracking is now live.");
            embed.setFooter("🌴 Semester record updated");

        } else if (destination == RecordDestination.ACCOUNT) {
            checkUser();
            TimerSubjectService.addSubjectToAccount(userId, subject);

            embed.setTitle("🎓 Course Registered");
            embed.setDescription("💎 Module **" + code + "** — *" + subject.getSubjectName()
                    + "* has been logged into your permanent academic record.\n\n");
            embed.setFooter("💎 Academic record updated");
        }
        return embed.build();
    }

    public MessageEmbed removeSubject(RecordDestination destination, String subjectCode) {
        String code = subjectCode.toUpperCase(java.util.Locale.ROOT).trim();
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(MIAMI_PINK);
        embed.setTimestamp(Instant.now());

        if (destination == RecordDestination.SEMESTER) {
            TimerSubjectService.removeSubjectFromSemester(userId, code);

            embed.setTitle("📚 Course Dropped");
            embed.setDescription("**" + code + "** has been removed from the active semester.\n\n" +
                    "> The course is no longer available for study tracking this term.");
            embed.setFooter("🌴 Semester record updated");

        } else if (destination == RecordDestination.ACCOUNT) {
            TimerSubjectService.removeSubjectFromAccount(userId, code);

            embed.setTitle("🎓 Course Removed");
            embed.setDescription("**" + code + "** has been removed from your academic record.\n\n");
            embed.setFooter("💎 Academic record updated");
        }

        isDataLoaded = false;
        return embed.build();
    }

    public MessageEmbed startSession(String messageId, String channelId, String guildId, String topic,
            String objective) {
        org.bunnys.bunnynexus.timers.services.TimerSessionService.startSession(userId, messageId, channelId, guildId, topic);
        isDataLoaded = false;
        checkSemester();
        String cleanTopic = org.bunnys.bunnynexus.timers.services.SubjectTopics.code(topic);
        return embeds().activeSession(cleanTopic, objective, 0.0, 0.0, false);
    }

    public MessageEmbed buildPendingSessionEmbed(String topic) {
        checkSemester();
        if (cachedTimerData.getSessionData().getSessionStartTime() != null)
            throw new StateFailure("End your active session before starting another.");
        org.bunnys.bunnynexus.timers.services.SubjectTopics.require(cachedTimerData.getCurrentSemester().getSemesterSubjects(), topic);

        String userName = interaction.getUser().getEffectiveName();
        String greetingText = TimerQuotes.getRandomGreeting(userName);
        String cleanTopic = org.bunnys.bunnynexus.timers.services.SubjectTopics.code(topic);

        return new EmbedBuilder()
                .setColor(MIAMI_CYAN)
                .setTitle("🌴 " + greetingText + " | " + topic)
                .setDescription("Telemetry link established for **" + cleanTopic
                        + "**.\n\n> ☕ Click the **Start** button below when you are physically ready to begin.")
                .setTimestamp(Instant.now())
                .build();
    }

    public MessageEmbed changeSubject(String newTopic) {
        org.bunnys.bunnynexus.timers.services.TimerSessionService.changeSubject(userId, newTopic);
        isDataLoaded = false;

        String cleanTopic = newTopic != null ? org.bunnys.bunnynexus.timers.services.SubjectTopics.code(newTopic) : "UNKNOWN";
        return new EmbedBuilder()
                .setColor(MIAMI_CYAN)
                .setTitle("🔄 Module Updated | " + cleanTopic)
                .setDescription("✦ **New Active Module:** `" + cleanTopic
                        + "`\n\n> *Telemetry feed successfully routed to the new subject. Your existing terminal will automatically update its data on its next refresh.*")
                .setTimestamp(Instant.now())
                .build();
    }

    /** Revision of the loaded timer document, captured when the end-semester prompt is shown. */
    public String revisionToken() {
        checkSemester();
        return String.valueOf(cachedTimerData.getRevision());
    }

    public static Long parseRevisionToken(String token) {
        return "null".equals(token) ? null : Long.valueOf(token);
    }

    /**
     * @param revisionToken the {@link #revisionToken()} captured when the prompt was shown; archival
     *                      is refused if the semester changed after the user saw the warning.
     */
    public Modal buildEndSemesterModal(String revisionToken) {
        // Opening a modal cannot be deferred. Validate live semester data on submission,
        // after that interaction is acknowledged, so a slow database cannot expire this click.
        parseRevisionToken(revisionToken);

        TextInput confirmInput = TextInput
                .create("confirmation_input", TextInputStyle.SHORT)
                .setPlaceholder("Type Archive followed by the semester name")
                .setRequired(true)
                .setMinLength(9)
                .setMaxLength(93)
                .build();

        String customId = "semester_end_modal:" + userId + ":" + System.currentTimeMillis() + ":" + revisionToken;

        return Modal.create(customId, "End Current Semester")
                .addComponents(net.dv8tion.jda.api.components.label.Label.of("Type Archive followed by the semester name", confirmInput))
                .build();
    }

    /**
     * Processes the submitted modal, verifies the 5-minute rule, checks the phrase,
     * archives the semester using internal cached logic, and generates the final
     * recap embed.
     */
    public net.dv8tion.jda.api.entities.MessageEmbed processEndSemesterModal(
            net.dv8tion.jda.api.events.interaction.ModalInteractionEvent event, long requestTime, Long confirmedRevision) {
        checkSemester();

        String semName = cachedTimerData.getCurrentSemester().getSemesterName();
        String expectedPhrase = "Archive " + semName;

        var mapping = event.getValue("confirmation_input");
        if (mapping == null)
            throw new InputFailure(
                    "Confirmation input was completely missing. The operation has been cancelled.");

        String userInput = mapping.getAsString().trim();

        if (requestTime > System.currentTimeMillis() || System.currentTimeMillis() - requestTime > 300_000)
            throw new StateFailure(
                    "Confirmation timed out. You must confirm within 5 minutes. The operation has been cancelled.");

        if (!userInput.equalsIgnoreCase(expectedPhrase))
            throw new InputFailure("Invalid confirmation phrase. Expected `" + expectedPhrase
                    + "`. The operation has been cancelled.");

        String recapData = finalizeSemesterArchival(event, confirmedRevision);
        String userName = event.getUser().getEffectiveName();

        net.dv8tion.jda.api.EmbedBuilder embed = new net.dv8tion.jda.api.EmbedBuilder();
        embed.setColor(org.bunnys.utils.AppDesign.ColorCodes.CYAN);
        embed.setTitle("💎 Semester Archived | " + semName);
        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Telemetry finalized • " + userName, event.getUser().getEffectiveAvatarUrl());

        String sb = recapData
                + "\n> *Your telemetry for this semester has been successfully finalized. Your dedication is officially on the record.*";
        embed.setDescription(sb);

        return embed.build();
    }

    /**
     * Private worker method that handles the mathematical execution and database
     * updates for ending a semester, using the class's built-in memory caching.
     */
    private String finalizeSemesterArchival(net.dv8tion.jda.api.events.interaction.ModalInteractionEvent event,
                                            Long confirmedRevision) {
        String recap = TimerAccountService.endSemester(userId, event, confirmedRevision);
        isDataLoaded = false;
        return recap;
    }
    public MessageEmbed refreshMainEmbed(MessageEmbed currentEmbed) {
        checkSemester();
        org.bunnys.database.models.timers.Session session = cachedTimerData.getSessionData();
        if (session.getSessionStartTime() == null)
            return currentEmbed;

        long now = System.currentTimeMillis();
        long startMs = session.getSessionStartTime().getTime();

        double storedBreakSecs = session.getSessionBreaks() != null ? session.getSessionBreaks().getSessionBreakTime()
                : 0.0;
        double activeBreakSecs = (session.getSessionBreaks() != null
                && session.getSessionBreaks().getSessionBreakStart() != null)
                        ? (now - session.getSessionBreaks().getSessionBreakStart().getTime()) / 1000.0
                        : 0.0;

        double totalBreakSecs = storedBreakSecs + activeBreakSecs;
        double totalElapsedSecs = (now - startMs) / 1000.0;
        double activeStudySecs = Math.max(0, totalElapsedSecs - totalBreakSecs);

        String topic = session.getSessionTopic();
        String cleanTopic = topic != null ? org.bunnys.bunnynexus.timers.services.SubjectTopics.code(topic) : "UNKNOWN";

        EmbedBuilder refreshed = new EmbedBuilder(embeds().activeSession(cleanTopic, null, activeStudySecs * 1000, totalBreakSecs * 1000, true));
        refreshed.setDescription(currentEmbed.getDescription());
        return refreshed.build();
    }

    /**
     * Unified embed builder for both Start Session and Refresh Main Embed to keep
     * the logic DRY.
     */
    private TimerEmbeds embeds() {
        return new TimerEmbeds(cachedTimerData, cachedUser, interaction.getUser());
    }
    public MessageEmbed statDisplay() {
        checkSemester();
        return embeds().statistics();
    }
    public List<MessageEmbed> buildGPAMenu() {
        checkUser();
        return embeds().gpa();
    }
    private static String getCurrentSemester() {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        Month month = now.getMonth();

        if (month.getValue() <= 5)
            return "Spring " + year;
        else if (month.getValue() <= 8)
            return "Summer " + year;
        else
            return "Fall " + year;
    }
}
