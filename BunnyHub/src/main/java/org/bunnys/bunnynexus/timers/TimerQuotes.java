package org.bunnys.bunnynexus.timers;

import java.util.Random;

public class TimerQuotes {

    private static final Random RANDOM = new Random();

    private static final String[] GREETINGS = {
            "Good to see you again, %s.",
            "Your progress summary is ready, %s.",
            "Semester record updated for %s.",
            "Current semester overview for %s.",
            "Study tracker loaded, %s.",

            "Another day, another page in the story, %s.",
            "The city keeps moving. So do you, %s.",
            "Progress never waits, %s.",

            "Opportunities don't stay parked forever, %s.",
            "Building something bigger, one session at a time, %s.",
            "The next chapter starts now, %s.",

            "Keep moving forward, %s.",
            "One step at a time, %s.",
            "A good record is built day by day, %s.",
            "Some things take time. Keep going, %s.",
            "The trail continues, %s.",

            "Profile verified. Welcome back, %s.",
            "Academic record loaded for %s.",
            "Performance review available, %s.",

            "The work speaks for itself, %s.",
            "Another entry added to the ledger, %s.",
            "No shortcuts. Just progress, %s.",
            "Consistency beats motivation, %s.",
            "The record continues, %s.",

            "Did somebody say yoga?",

            "Looks like someone's been keeping busy, %s.",
            "Good. You're here, %s.",
            "Well, there you are, %s.",
            "Back again? Excellent, %s.",
            "I was beginning to wonder, %s.",
            "Let's see what we've got today, %s.",
            "Still at it, huh, %s?",
            "No rest for the ambitious, %s.",

            "Good habits. Dangerous things, %s.",
            "You know, this actually works, %s.",

            "Keep this up and they'll start expecting things from you, %s.",
            "You're exactly where you're supposed to be, %s.",
            "Discipline. That's the difference, %s.",
            "Results don't happen by accident, %s.",

            "Let's review the numbers, %s.",
            "Consistency is a rare commodity, %s.",
            "The data looks promising, %s.",
            "Progress is progress, %s.",
            "The operation remains active, %s.",
            "Everything appears to be running smoothly, %s.",

            "I've been looking at the numbers, %s.",
            "Looks like somebody's been putting in the work, %s.",
            "Not bad. Not bad at all, %s.",
            "You know, most people never get this far, %s.",
            "Let's see what happened while you were away, %s.",
            "I've got some updates for you, %s.",

            "Ah, there you are, Captain.",
            "A productive day, yes, %s?",
            "We continue, %s.",
            "Excellent work, Captain.",
            "Good. Very good, %s.",
            "Let's see how things are going, %s.",
            "One day at a time, %s.",
            "Slow and steady, %s.",
            "Work's getting done, %s.",
            "Not a bad day's work, %s.",
            "Keep moving, %s.",
            "The journal grows, %s.",
            "Another day in the books, %s.",

            "The ledger remembers everything, %s.",
            "A little further than yesterday, %s.",
            "Momentum is building, %s.",
            "Every session counts, %s.",
            "The numbers are starting to add up, %s.",
            "Another brick in the foundation, %s.",
            "The machine keeps running, %s.",
            "Progress has been logged, %s.",
            "One more step toward the goal, %s.",
            "Steady hands. Clear direction, %s.",
            "Small gains become large victories, %s.",
            "The effort is accumulating, %s.",
            "Another milestone approaches, %s.",
            "The account of your work grows daily, %s.",
            "Today's work becomes tomorrow's advantage, %s.",
            "Stay the course, %s.",
            "The investment continues, %s.",
            "The road ahead is shorter than it was yesterday, %s.",
            "Keep stacking victories, %s.",
            "The system is working, %s.",
            "Another session. Another improvement, %s.",
            "The habit is taking shape, %s.",
            "The foundation gets stronger, %s.",
            "You're building something worth keeping, %s.",
            "Every page turned is progress, %s.",
            "Keep the streak alive, %s.",
            "The clock is running in your favor, %s.",
            "One more entry for the archives, %s.",
            "The books are looking better every day, %s.",
            "Forward is forward, %s.",
            "The future is built here, %s.",
            "Another productive chapter begins, %s.",
            "Your effort has been recorded, %s.",
            "The operation continues without interruption, %s.",
            "Work now. Results later, %s.",
            "You've come too far to stop now, %s.",
            "Let's add another win to the record, %s.",
            "The story moves forward, %s.",
            "Time invested. Progress earned, %s.",
            "Good work leaves a trail, %s.",

            "You're late. Not really, but it sounded dramatic.",
            "Good. The operation survived another night.",
            "I've kept the lights on for you.",
            "The paperwork never sleeps.",
            "Business appears to be booming.",
            "The shareholders remain cautiously optimistic.",
            "The board is requesting results. Again.",
            "We appear to be winning.",
            "Nobody panic. Everything is under control.",
            "Everything is proceeding according to a plan.",
            "Probably.",

            "The empire expands.",
            "The empire requires maintenance.",
            "The machine demands another session.",
            "The machine approves.",
            "The numbers continue to climb.",
            "The accountants are confused, but pleased.",
            "Productivity levels remain suspiciously high.",
            "That can't be healthy.",

            "You've been busy.",
            "Suspiciously busy.",
            "That's a respectable amount of work.",
            "Another successful operation.",
            "Mission progress updated.",
            "The dossier has been updated.",
            "Records indicate continued competence.",
            "The archive grows.",
            "The evidence is mounting.",
            "The case for success grows stronger.",
            "The statistics are becoming difficult to ignore.",

            "Work has been detected.",
            "Motivation optional. Progress mandatory.",
            "Achievement through stubbornness.",
            "Professional procrastinators hate this one trick.",
            "Results acquired.",
            "Another victory for consistency.",

            "The grind continues.",
            "The hustle remains operational.",
            "The schedule fears you.",
            "Deadlines are approaching cautiously.",
            "One more session won't hurt.",
            "Probably.",

            "The future version of you approves.",
            "The future version of you is also confused.",
            "You survived yesterday. Let's try again.",
            "Welcome back to the campaign.",
            "Character progression updated.",
            "Experience gained.",
            "Skill issue resolved.",
            "The quest log has been updated.",
            "Another checkpoint reached.",
            "Save successful.",
            "The save file grows stronger.",
            "Progress has been safely stored.",

            "Your legacy expands.",
            "The books have been balanced.",
            "Mostly.",

            "Remarkable. You're still doing the thing.",
            "The operation remains profitable.",
            "The crew is standing by.",
            "The city never sleeps.",
            "Neither does the workload.",
            "The journal gets heavier.",
            "Another page written.",
            "One more story for the record.",
            "The record keeper is impressed.",

            "Keep cooking.",
            "The engine is warmed up.",
            "Let's make today expensive.",
            "The competition remains unaware.",
            "Keep stacking wins.",
            "The streak remains intact.",
            "The streak demands nourishment.",

            "The vault isn't going to fill itself.",
            "There's money on the table.",
            "Go collect it.",

            "Captain, the crew awaits."
    };

    /**
     * Retrieves a random greeting, formatting it with the user's name if necessary.
     */
    public static String getRandomGreeting(String userName) {
        String template = GREETINGS[RANDOM.nextInt(GREETINGS.length)];
        if (template.contains("%s"))
            return String.format(template, userName);
        return template;
    }
}