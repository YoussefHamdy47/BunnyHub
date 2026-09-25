package org.bunnys.bunnynexus.timers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Utils;

import java.awt.Color;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Presentation-only view of a loaded timer/account snapshot; no persistence or interaction callbacks. */
final class TimerEmbeds {
    private static final Color MIAMI_PINK = AppDesign.ColorCodes.CYAN;
    private static final Color MIAMI_CYAN = new Color(5, 217, 232);
    private final BunnyUser cachedUser;
    private final TimerData cachedTimerData;
    private final net.dv8tion.jda.api.entities.User user;
    TimerEmbeds(TimerData timer, BunnyUser account, net.dv8tion.jda.api.entities.User user) {
        this.cachedTimerData = Objects.requireNonNull(timer);
        this.cachedUser = Objects.requireNonNull(account);
        this.user = Objects.requireNonNull(user);
    }
    MessageEmbed activeSession(String cleanTopic, String objective, double additionalStudyMs,
            double additionalBreakMs, boolean isRefresh) {
        TimerStats stats = new TimerStats(cachedTimerData, cachedUser);
        org.bunnys.database.models.timers.Session session = cachedTimerData.getSessionData();

        double semesterMs = stats.getSemesterTime() + additionalStudyMs;
        double semesterBreakMs = stats.getBreakTime() + additionalBreakMs;

        int timesStudied = 0;
        double subjectTimeMs = Math.max(0, additionalStudyMs - session.getSessionTime() * 1000);

        if (cachedTimerData.getCurrentSemester().getSemesterSubjects() != null) {
            var subOpt = cachedTimerData.getCurrentSemester().getSemesterSubjects().stream()
                    .filter(s -> s.getSubjectCode().equalsIgnoreCase(cleanTopic))
                    .findFirst();

            if (subOpt.isPresent()) {
                timesStudied = subOpt.get().getTimesStudied();
                subjectTimeMs += (subOpt.get().getTotalStudyTime() != null ? subOpt.get().getTotalStudyTime() : 0.0)
                        * 1000;
            }
        }

        String userName = user.getEffectiveName();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(MIAMI_CYAN);
        eb.setTimestamp(Instant.now());

        if (!isRefresh) {
            eb.setFooter("🌴 Session timer is live", user.getEffectiveAvatarUrl());
        }

        String displayTopic = session.getSessionTopic() != null ? session.getSessionTopic() : cleanTopic;
        eb.setTitle("🌴 " + TimerQuotes.getRandomGreeting(userName) + " | " + displayTopic);

        if (objective != null && !objective.trim().isEmpty()) {
            eb.setDescription("> **Mission Objective:** " + objective);
        }

        String recordSb = "✦ Semester Focus: `" + formatMs(semesterMs) + "` *[" + formatHoursAsNum(semesterMs)
                + " hours]*\n" +
                "✦ Total Sessions: `" + stats.getSessionCount() + "`\n" +
                "✦ Average Session Time: `" + formatMs(stats.getAverageSessionTime()) + "`\n\u200B";
        eb.addField("🍸 Executive Study Record", recordSb, false);

        String breakSb = "✦ Semester Break Time: `" + formatMs(semesterBreakMs) + "`\n" +
                "✦ Total Breaks: `" + stats.getBreakCount() + "`\n" +
                "✦ Average Break Time: `" + formatMs(stats.getAverageBreakTime()) + "`\n\u200B";
        eb.addField("🏖️ Leisure & Recovery", breakSb, false);

        String subSb = "✦ Active Module: **" + cleanTopic + "**\n" +
                "✦ Study Instances: `" + timesStudied + "`\n" +
                "✦ Total Time Logged: `" + formatMs(subjectTimeMs) + "` *[" + formatHoursAsNum(subjectTimeMs)
                + " hours]*\n\u200B";
        eb.addField(AppDesign.Emojis.WHITE_HEART_SPIN + " Module Telemetry", subSb, false);

        return eb.build();
    }

    MessageEmbed statistics() {


        TimerStats stats = new TimerStats(cachedTimerData, cachedUser);
        String userName = user.getEffectiveName();
        String avatarUrl = user.getEffectiveAvatarUrl();
        String semName = cachedTimerData.getCurrentSemester().getSemesterName();

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(MIAMI_CYAN);
        embed.setTimestamp(Instant.now());
        embed.setFooter(userName + "'s BunnyTimer Data", avatarUrl);

        String greetingText = TimerQuotes.getRandomGreeting(userName);
        embed.setTitle("🌴 " + greetingText + " — " + semName);

        StringBuilder recordSb = new StringBuilder();
        double lifetimeMs = stats.getTotalStudyTime();
        recordSb.append("✦ Lifetime Focus: `").append(formatMs(lifetimeMs)).append("` *[")
                .append(formatHoursAsNum(lifetimeMs)).append(" hours]*\n");

        double semesterMs = stats.getSemesterTime();
        recordSb.append("✦ Semester Focus: `").append(formatMs(semesterMs)).append("` *[")
                .append(formatHoursAsNum(semesterMs)).append(" hours]*\n");

        recordSb.append("✦ Average Session Time: `").append(formatMs(stats.getAverageSessionTime())).append("`\n");
        recordSb.append("✦ Longest Session: `").append(formatMs(stats.getLongestSessionTime())).append("`\n");
        recordSb.append("✦ Total Sessions: `").append(stats.getSessionCount()).append("`\n\u200B");

        embed.addField("🍸 Executive Study Record", recordSb.toString(), false);

        String avgStartTime = "`Not Enough Data`";
        List<Long> startTimes = cachedTimerData.getCurrentSemester().getSessionStartTimes();
        if (startTimes != null && !startTimes.isEmpty()) {
            long avgMillisOfDay = TimerStats.averageMillisOfDay(startTimes);
            long todayMidnightUTC = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();
            long discordTimestamp = (todayMidnightUTC + avgMillisOfDay) / 1000;
            avgStartTime = "<t:" + discordTimestamp + ":t>";
        }

        String streakSb = "✦ Active Streak: `" + stats.getCurrentStreak() + " Days` 🔥\n" +
                "✦ Longest Streak: `" + stats.getLongestStreak() + " Days` 🔥\n" +
                "✦ Average Start Time: " + avgStartTime + "\n\u200B";
        embed.addField("✦ Streaks & Habits", streakSb, false);

        String breakSb = "✦ Total Recovery Time: `" + formatMs(stats.getBreakTime()) + "`\n" +
                "✦ Break Count: `" + stats.getBreakCount() + "`\n" +
                "✦ Average Break Time: `" + formatMs(stats.getAverageBreakTime()) + "`\n" +
                "✦ Sustained Focus (Time Between Breaks): `" + formatMs(stats.getAverageTimeBetweenBreaks())
                + "`\n\u200B";
        embed.addField("🏖️ Leisure & Recovery", breakSb, false);

        var subjects = stats.getSubjectsInOrder();
        int totalInstances = subjects.stream().mapToInt(Subject::getTimesStudied).sum();

        StringBuilder portfolioHeader = new StringBuilder();
        portfolioHeader.append("✦ Total Subjects: `").append(subjects.size()).append("`\n")
                .append("✦ Total Subject Instances: `").append(totalInstances).append("`\n\n")
                .append(AppDesign.Emojis.WHITE_HEART_SPIN).append(" **Active Modules**\n");

        if (subjects.isEmpty()) {
            portfolioHeader.append("> *No courses registered this semester.*\n\u200B");
            embed.addField(AppDesign.Emojis.DIAMOND_SPIN + " Academic Portfolio", portfolioHeader.toString(), false);
        } else {
            subjects.sort((s1, s2) -> {
                double t1 = s1.getTotalStudyTime() != null ? s1.getTotalStudyTime() : 0.0;
                double t2 = s2.getTotalStudyTime() != null ? s2.getTotalStudyTime() : 0.0;
                return Double.compare(t2, t1);
            });

            StringBuilder currentField = new StringBuilder(portfolioHeader);
            int fieldIndex = 1;

            int displayedSubjects = Math.min(12, subjects.size());
            for (int i = 0; i < displayedSubjects; i++) {
                var sub = subjects.get(i);
                int credits = sub.getCreditHours();
                String creditStr = credits > 0 ? " (`" + credits + "`)" : "";
                double subMs = (sub.getTotalStudyTime() != null ? sub.getTotalStudyTime() : 0.0) * 1000;
                double hours = subMs / 3_600_000.0;

                String entry;
                if (i < 5) {
                    String prefix = switch (i) {
                        case 0 -> "🥇";
                        case 1 -> "🥈";
                        case 2 -> "🥉";
                        default -> "•";
                    };

                    // Clean 2-line format for Top 5
                    entry = prefix + " **" + sub.getSubjectCode().toUpperCase(java.util.Locale.ROOT) + "**" + creditStr + " — *"
                            + sub.getSubjectName() + "*\n" +
                            "  ↳ Instances: `" + sub.getTimesStudied() + "` | Accumulated: `" + formatMs(subMs) + "` *["
                            + String.format("%,.2f hours", hours) + "]*\n\n";
                } else {
                    if (i == 5)
                        currentField.append("**Additional Modules**\n");
                    entry = "• **" + sub.getSubjectCode().toUpperCase(java.util.Locale.ROOT) + "**" + creditStr + " — *"
                            + sub.getSubjectName() + "* | `" + sub.getTimesStudied() + "` | `"
                            + String.format("%,.2f hours", hours) + "`\n";
                }

                if (currentField.length() + entry.length() > 950) {
                    embed.addField(fieldIndex == 1 ? AppDesign.Emojis.DIAMOND_SPIN + " Academic Portfolio"
                            : "Academic Portfolio (Cont.)", currentField.toString(), false);
                    currentField = new StringBuilder();
                    fieldIndex++;
                }
                currentField.append(entry);
            }

            if (currentField.length() > 800) {
                embed.addField("Academic Portfolio (Cont.)", currentField.toString(), false);
                currentField = new StringBuilder();
            }
            if (subjects.size() > displayedSubjects)
                currentField.append("\nFurther courses are listed in `/timer gpa`.\n");
            double avgSubMs = semesterMs / subjects.size();
            currentField.append("\n✦ Average Study Time Per Subject: `").append(formatMs(avgSubMs)).append("`\n\u200B");

            embed.addField(fieldIndex == 1 ? AppDesign.Emojis.DIAMOND_SPIN + " Academic Portfolio"
                    : "Academic Portfolio (Cont.)", currentField.toString(), false);
        }

        StringBuilder progSb = new StringBuilder();
        int semLevel = stats.getSemesterLevel();
        long semXpReq = LevelEngine.xpRequired(semLevel);

        progSb.append(LevelEngine.rankUpEmoji(semLevel)).append(" **Level ").append(semLevel).append("**\n")
                .append("✦ XP to next level: `").append(String.format("%,.0f", stats.getSemesterXP())).append(" / ")
                .append(String.format("%,d", semXpReq)).append("`\n")
                .append("  ").append(stats.generateProgressBar(stats.percentageToNextLevel())).append(" `[")
                .append(stats.percentageToNextLevel()).append("%]`\n")
                .append("✦ Est. time to level up: `").append(formatMs(stats.getMsToNextLevel())).append("`\n\n");

        int accLevel = stats.getAccountLevel();
        long accRpReq = LevelEngine.rpRequired(accLevel);

        progSb.append(LevelEngine.rankUpEmoji(accLevel)).append(" **Rank ").append(accLevel).append("**\n")
                .append("✦ RP to next rank: `").append(String.format("%,d", stats.getAccountRP())).append(" / ")
                .append(String.format("%,d", accRpReq)).append("`\n")
                .append("  ").append(stats.generateProgressBar(stats.percentageToNextRank())).append(" `[")
                .append(stats.percentageToNextRank()).append("%]`\n")
                .append("✦ Est. time to rank up: `").append(formatMs(stats.getMsToNextRank())).append("`\n");

        embed.addField(AppDesign.Emojis.DIAMOND_SPIN + " Progression & Status", progSb.toString(), false);

        return embed.build();
    }

    List<MessageEmbed> gpa() {


        List<Subject> accountSubjects = new ArrayList<>(
                cachedUser.getSubjects() != null ? cachedUser.getSubjects() : Collections.emptyList());
        List<Subject> semesterSubjects = new ArrayList<>();

        if (cachedTimerData.getCurrentSemester() != null
                && cachedTimerData.getCurrentSemester().getSemesterSubjects() != null)
            semesterSubjects.addAll(cachedTimerData.getCurrentSemester().getSemesterSubjects());

        accountSubjects.sort((s1, s2) -> {
            double gpa1 = s1.getGradeEnum() != null ? s1.getGradeEnum().getGpaValue() : -1.0;
            double gpa2 = s2.getGradeEnum() != null ? s2.getGradeEnum().getGpaValue() : -1.0;

            int gradeCompare = Double.compare(gpa2, gpa1);
            if (gradeCompare != 0)
                return gradeCompare;
            return Integer.compare(s2.getCreditHours(), s1.getCreditHours());
        });

        semesterSubjects.sort((s1, s2) -> Integer.compare(s2.getCreditHours(), s1.getCreditHours()));

        List<MessageEmbed> pages = new ArrayList<>();
        String userName = user.getEffectiveName();
        String avatarUrl = user.getEffectiveAvatarUrl();

        if (accountSubjects.isEmpty() && semesterSubjects.isEmpty()) {
            EmbedBuilder emptyEmbed = new EmbedBuilder()
                    .setColor(MIAMI_PINK)
                    .setAuthor("Academic Record — " + userName, null, avatarUrl)
                    .setDescription("> *No subjects found in your permanent record or current semester.*")
                    .setFooter("Page 1 of 1");

            pages.add(emptyEmbed.build());
            return pages;
        }

        int itemsPerPage = 5;
        double cumulativeGpa = cachedUser.calculateCumulativeGPA();

        if (!semesterSubjects.isEmpty()) {
            int semPages = (int) Math.ceil((double) semesterSubjects.size() / itemsPerPage);
            for (int i = 0; i < semPages; i++) {
                int start = i * itemsPerPage;
                int end = Math.min(start + itemsPerPage, semesterSubjects.size());
                List<Subject> chunk = semesterSubjects.subList(start, end);

                EmbedBuilder embed = new EmbedBuilder()
                        .setColor(MIAMI_CYAN)
                        .setAuthor("Current Semester — " + userName, null, avatarUrl);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format(AppDesign.Emojis.VERIFY + " **Cumulative GPA:** `%.3f`\n\n", cumulativeGpa));
                sb.append("**🌴 Active Courses (In Progress)**\n\n");

                for (Subject sub : chunk) {
                    sb.append("• **").append(sub.getSubjectCode().toUpperCase(java.util.Locale.ROOT)).append("** — *")
                            .append(sub.getSubjectName()).append("*\n")
                            .append("  ↳ Grade: **").append(sub.getGrade() == null ? "In Progress" : sub.getGrade()).append("** | Credits: `").append(sub.getCreditHours())
                            .append(" CH`\n\n");
                }

                embed.setDescription(sb.toString().trim());
                pages.add(embed.build());
            }
        }

        if (!accountSubjects.isEmpty()) {
            int accPages = (int) Math.ceil((double) accountSubjects.size() / itemsPerPage);
            for (int i = 0; i < accPages; i++) {
                int start = i * itemsPerPage;
                int end = Math.min(start + itemsPerPage, accountSubjects.size());
                List<Subject> chunk = accountSubjects.subList(start, end);

                EmbedBuilder embed = new EmbedBuilder()
                        .setColor(MIAMI_PINK)
                        .setAuthor("Academic Record — " + userName, null, avatarUrl);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format(AppDesign.Emojis.VERIFY + " **Cumulative GPA:** `%.3f`\n\n", cumulativeGpa));
                sb.append(AppDesign.Emojis.DIAMOND_SPIN + " **Completed Courses**\n\n");

                for (Subject sub : chunk) {
                    String gradeStr = sub.getGrade() != null ? sub.getGrade() : "N/A";
                    sb.append("• **").append(sub.getSubjectCode().toUpperCase(java.util.Locale.ROOT)).append("** — *")
                            .append(sub.getSubjectName()).append("*\n")
                            .append("  ↳ Grade: **").append(gradeStr).append("** | Credits: `")
                            .append(sub.getCreditHours()).append("`\n\n");
                }

                embed.setDescription(sb.toString().trim());
                pages.add(embed.build());
            }
        }

        int totalPages = pages.size();
        for (int i = 0; i < totalPages; i++) {
            MessageEmbed original = pages.get(i);
            EmbedBuilder builder = new EmbedBuilder(original);
            builder.setFooter(String.format("Page %d of %d", i + 1, totalPages));
            pages.set(i, builder.build());
        }

        return pages;
    }

    private String formatMs(double ms) {
        return ms > 0 ? Utils.msToTime((long) ms).orElse("0s") : "0s";
    }

    private String formatHoursAsNum(double ms) {
        return ms > 0 ? String.format("%,.2f", ms / 3_600_000.0) : "0.00";
    }

}
