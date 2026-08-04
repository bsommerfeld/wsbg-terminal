package de.bsommerfeld.wsbg.terminal.db;

import java.time.LocalDate;
import java.util.Map;

/**
 * One day of the cage's own mention count, as it sits on disk: the raw
 * SPELLINGS the room used, each with how often it was written, plus how many
 * posts and comments contributed.
 *
 * <p>Spellings, not symbols — resolution happens on read (see
 * {@code MentionLexicon}), so a name the resolver only learns tomorrow still
 * folds into its ticker across every day already stored.
 *
 * @param day      the calendar day these counts belong to
 * @param phrases  spelling → number of times it was written that day
 * @param items    posts + comments that were scanned into this day
 */
public record MentionDay(LocalDate day, Map<String, Integer> phrases, int items) {

    public MentionDay {
        phrases = phrases == null ? Map.of() : Map.copyOf(phrases);
    }

    /** Every mention written that day, across all spellings. */
    public int total() {
        int sum = 0;
        for (int c : phrases.values()) sum += c;
        return sum;
    }

    public boolean isEmpty() {
        return phrases.isEmpty();
    }
}
