package de.bsommerfeld.wsbg.terminal.agent.event;

import java.util.List;

/**
 * One entry of the KI-DD live workspace feed ("Blick in die Box") — the
 * richer sibling of the desk journal: FULL texts (never clipped), the acting
 * participant and the report locus the entry touches. Kinds:
 * <ul>
 *   <li>{@code chat} — one participant's full message (the model's reply or
 *       the house's composed verdict), optionally with a diff attachment
 *       (the change the message caused);</li>
 *   <li>{@code body} — the standing FULL text of one section after a
 *       mutation — the live report mirror; the diff carries what changed;</li>
 *   <li>{@code note} — a judge's margin note, anchored to section +
 *       paragraph (0 = unanchored);</li>
 *   <li>{@code pending} — a locus under rework (paragraph 0 = the whole
 *       section / its tail) — the UI covers it until the section's next
 *       {@code body} or {@code settled};</li>
 *   <li>{@code settled} — the announced rework ended without changing the
 *       section;</li>
 *   <li>{@code src} / {@code src-ok} / {@code src-out} — the triage board:
 *       a collected source appears on the list, earns its seat, or is
 *       struck as off-subject. {@code ref} carries the item's stable key so
 *       a verdict finds its row; {@code text} is the display line
 *       ("title · publisher"). A later verdict on the same ref may FLIP an
 *       earlier one (fail-open seats and runaway caps correct at the end of
 *       a sweep).</li>
 * </ul>
 * {@code section} is the 0-based skeleton index (-1 = not section-bound),
 * {@code paragraph} 1-based. {@code phase} and {@code participant} are
 * protocol tokens (phase: collect|triage|figures|sections|these|reclaim|
 * consistency|typeset; participant: author|chronicle|gate|weaver|diffjudge|
 * challenger|reviser|arbiter|polisher|final|reclaim|typesetter|triage),
 * localized by the UI, never display literals.
 */
public record DeepDiveLiveEvent(String subject, Entry entry) {

    public record Entry(String kind, String phase, String participant,
            int section, int paragraph, String text,
            List<DeepDiveJournalEvent.Line> diff, String ref) {

        /** The common case — an entry with no source reference. */
        public Entry(String kind, String phase, String participant,
                int section, int paragraph, String text,
                List<DeepDiveJournalEvent.Line> diff) {
            this(kind, phase, participant, section, paragraph, text, diff, null);
        }
    }
}
