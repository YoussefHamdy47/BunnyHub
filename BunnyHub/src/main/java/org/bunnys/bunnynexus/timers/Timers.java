package org.bunnys.bunnynexus.timers;

import com.mongodb.client.model.Filters;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.timers.services.SessionClock;
import org.bunnys.bunnynexus.timers.services.SubjectTopics;
import org.bunnys.bunnynexus.timers.services.TimerAccountService;
import org.bunnys.bunnynexus.timers.services.TimerSessionService;
import org.bunnys.bunnynexus.timers.services.TimerSubjectService;
import org.bunnys.database.models.timers.Session;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.handler.utils.InteractionErrors.StateFailure;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * One user's timer actions for a single interaction. Loads the account and timer documents at most once per
 * instance and reloads after its own writes; persistence rules live in the services it calls.
 */
public class Timers {

    public enum RecordDestination { ACCOUNT, SEMESTER }

    private static final long END_SEMESTER_CONFIRM_MILLIS = 300_000;

    private final String userId;
    private final IReplyCallback interaction;

    private BunnyUser cachedUser;
    private TimerData cachedTimerData;
    private boolean isDataLoaded;

    public Timers(String userId, IReplyCallback interaction) {
        this.userId = userId;
        this.interaction = interaction;
    }

    private void loadData() {
        if (isDataLoaded) return;
        cachedUser = DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId));
        cachedTimerData = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
        isDataLoaded = true;
    }

    public void checkUser() {
        loadData();
        if (cachedUser == null || cachedTimerData == null)
            throw new StateFailure("You don't have a BunnyHub Timers account. Use `/timer register <semester>` first.");
    }

    public void checkSemester() {
        checkUser();
        if (cachedTimerData.getCurrentSemester() == null || cachedTimerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("You don't have an active semester. Register one to begin tracking time.");
    }

    /** The current session state, loaded with the semester. */
    public Session session() {
        checkSemester();
        return cachedTimerData.getSessionData();
    }

    public MessageEmbed register(String semesterName) {
        if (semesterName == null || semesterName.isBlank())
            throw new InputFailure("You must provide a semester name to register (e.g., `" + suggestedSemesterName() + "`).");

        loadData();
        String cleanName = semesterName.trim();
        boolean brandNewAccount = cachedUser == null || cachedTimerData == null;
        if (brandNewAccount) {
            TimerAccountService.registerAccount(userId);
            isDataLoaded = false;
            loadData();
        }
        if (cachedTimerData.getCurrentSemester() != null && cachedTimerData.getCurrentSemester().getSemesterName() != null)
            throw new StateFailure("Semester `" + cachedTimerData.getCurrentSemester().getSemesterName() + "` is already active.\n"
                    + "Use `/timer end_semester` to close it before starting a new one.");

        TimerAccountService.registerSemester(userId, cleanName);
        String description = brandNewAccount
                ? "Welcome aboard, **" + interaction.getUser().getEffectiveName() + "**.\n\nYour account has been registered and **"
                        + cleanName + "** is now active."
                : "Successfully registered **" + cleanName + "**.\n\n> A new chapter has been added to your academic record.";
        var embed = Embeds.of(AppDesign.Emojis.VERIFY, brandNewAccount ? "Welcome to BunnyHub" : "Semester Registered", description);
        return Embeds.footer(embed, "Semester file opened").build();
    }

    public MessageEmbed addSubject(RecordDestination destination, Subject subject) {
        String code = subject.getSubjectCode().toUpperCase(Locale.ROOT);
        EmbedBuilder embed;
        if (destination == RecordDestination.SEMESTER) {
            checkSemester();
            TimerSubjectService.addSubjectToSemester(userId, subject);
            embed = Embeds.of(AppDesign.Emojis.WHITE_HEART_SPIN, "Course Added", "Module **" + code + "** — *"
                    + subject.getSubjectName() + "* has been added to the current semester.\n\n> Telemetry tracking is now live.");
            Embeds.footer(embed, "Semester record updated");
        } else {
            checkUser();
            TimerSubjectService.addSubjectToAccount(userId, subject);
            embed = Embeds.of(AppDesign.Emojis.WHITE_HEART_SPIN, "Course Registered", "Module **" + code + "** — *"
                    + subject.getSubjectName() + "* has been logged into your permanent academic record.");
            Embeds.footer(embed, "Academic record updated");
        }
        isDataLoaded = false;
        return embed.build();
    }

    public MessageEmbed removeSubject(RecordDestination destination, String subjectCode) {
        String code = subjectCode.toUpperCase(Locale.ROOT).trim();
        EmbedBuilder embed;
        if (destination == RecordDestination.SEMESTER) {
            TimerSubjectService.removeSubjectFromSemester(userId, code);
            embed = Embeds.of(AppDesign.Emojis.STOP, "Course Dropped", "**" + code + "** has been removed from the active semester.\n\n"
                    + "> The course is no longer available for study tracking this term.");
            Embeds.footer(embed, "Semester record updated");
        } else {
            TimerSubjectService.removeSubjectFromAccount(userId, code);
            embed = Embeds.of(AppDesign.Emojis.STOP, "Course Removed", "**" + code + "** has been removed from your academic record.");
            Embeds.footer(embed, "Academic record updated");
        }
        isDataLoaded = false;
        return embed.build();
    }

    public MessageEmbed startSession(String messageId, String channelId, String guildId, String topic, String objective) {
        TimerSessionService.startSession(userId, messageId, channelId, guildId, topic);
        isDataLoaded = false;
        checkSemester();
        return embeds().activeSession(SubjectTopics.code(topic), objective, 0.0, 0.0, false);
    }

    public MessageEmbed buildPendingSessionEmbed(String topic) {
        checkSemester();
        if (cachedTimerData.getSessionData().getSessionStartTime() != null)
            throw new StateFailure("End your active session before starting another.");
        SubjectTopics.require(cachedTimerData.getCurrentSemester().getSemesterSubjects(), topic);
        String code = SubjectTopics.code(topic);
        var embed = Embeds.of(AppDesign.Emojis.WHITE_HEART_SPIN,
                TimerQuotes.getRandomGreeting(interaction.getUser().getEffectiveName()) + " | " + topic,
                "Telemetry link established for **" + code
                        + "**.\n\n> ☕ Click **Start** below when you are ready to begin. This menu closes after 10 minutes.");
        return Embeds.footer(embed, "Waiting to start").build();
    }

    public MessageEmbed changeSubject(String newTopic) {
        TimerSessionService.changeSubject(userId, newTopic);
        isDataLoaded = false;
        String code = SubjectTopics.code(newTopic);
        return Embeds.of("🔄", "Module Updated | " + code, "✦ **New Active Module:** `" + code
                + "`\n\n> *Your session card picks this up the next time it refreshes.*").build();
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
     * Opening a modal cannot be deferred, so live semester data is validated on submission instead, after that
     * interaction is acknowledged; a slow database cannot expire this click.
     *
     * @param revisionToken the {@link #revisionToken()} captured when the prompt was shown; archival is refused if
     *                      the semester changed after the user saw the warning.
     */
    public Modal buildEndSemesterModal(String revisionToken) {
        parseRevisionToken(revisionToken);
        TextInput confirmInput = TextInput.create("confirmation_input", TextInputStyle.SHORT)
                .setPlaceholder("Type Archive followed by the semester name")
                .setRequired(true)
                .setMinLength(9)
                .setMaxLength(93)
                .build();
        String customId = "semester_end_modal:" + userId + ":" + System.currentTimeMillis() + ":" + revisionToken;
        return Modal.create(customId, "End Current Semester")
                .addComponents(Label.of("Type Archive followed by the semester name", confirmInput))
                .build();
    }

    /** Checks the typed phrase and the 5-minute window, archives the semester and returns the recap card. */
    public MessageEmbed processEndSemesterModal(ModalInteractionEvent event, long requestTime, Long confirmedRevision) {
        checkSemester();
        String semesterName = cachedTimerData.getCurrentSemester().getSemesterName();
        String expectedPhrase = "Archive " + semesterName;

        var mapping = event.getValue("confirmation_input");
        if (mapping == null)
            throw new InputFailure("Confirmation input was completely missing. The operation has been cancelled.");
        long now = System.currentTimeMillis();
        if (requestTime > now || now - requestTime > END_SEMESTER_CONFIRM_MILLIS)
            throw new StateFailure("Confirmation timed out. You must confirm within 5 minutes. The operation has been cancelled.");
        if (!mapping.getAsString().trim().equalsIgnoreCase(expectedPhrase))
            throw new InputFailure("Invalid confirmation phrase. Expected `" + expectedPhrase + "`. The operation has been cancelled.");

        String recap = TimerAccountService.endSemester(userId, event, confirmedRevision);
        isDataLoaded = false;
        var embed = Embeds.of(AppDesign.Emojis.VERIFY, "Semester Archived | " + semesterName,
                recap + "\n> *Your telemetry for this semester has been finalized. Your dedication is officially on the record.*");
        return Embeds.footer(embed, "Telemetry finalized • " + event.getUser().getEffectiveName(),
                event.getUser().getEffectiveAvatarUrl()).build();
    }

    /** Re-renders a running session's card with live totals, keeping the objective text already on it. */
    public MessageEmbed refreshMainEmbed(MessageEmbed currentEmbed) {
        Session session = session();
        if (session.getSessionStartTime() == null) return currentEmbed;
        var clock = SessionClock.of(session, System.currentTimeMillis());
        String topic = session.getSessionTopic();
        EmbedBuilder refreshed = new EmbedBuilder(embeds().activeSession(topic != null ? SubjectTopics.code(topic) : "UNKNOWN",
                null, clock.activeStudy() * 1000, clock.breaks() * 1000, true));
        refreshed.setDescription(currentEmbed.getDescription());
        return refreshed.build();
    }

    public MessageEmbed statDisplay() {
        checkSemester();
        return embeds().statistics();
    }

    public List<MessageEmbed> buildGPAMenu() {
        checkUser();
        return embeds().gpa();
    }

    private TimerEmbeds embeds() {
        return new TimerEmbeds(cachedTimerData, cachedUser, interaction.getUser());
    }

    private static String suggestedSemesterName() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        return (month <= 5 ? "Spring " : month <= 8 ? "Summer " : "Fall ") + now.getYear();
    }
}
