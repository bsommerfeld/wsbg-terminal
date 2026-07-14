package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.MarketEventArchive;
import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The market memory's Abendausgabe block — DETERMINISTIC (the weather
 * doctrine: a 4B model never re-tells numbers it could mangle). For every
 * event class the register saw TODAY, the shelf gets the class's house base
 * rate ({@link BaseRates}, license gates in code) and the attributed
 * literature prior — so the edition can say "the market historically answers
 * this class with X" right beside the day's disclosure, and the discipline
 * line pins that a base rate is a PRIOR, never a prediction.
 *
 * <p>Self-contained on purpose: the weather files are the collector's/
 * material's territory — this class owns the whole block, the service only
 * appends it to the evening shelf.
 */
final class MarketMemoryBriefing {

    /** Instruments named per class line before the honest "+n more". */
    private static final int MAX_INSTRUMENTS_SHOWN = 4;

    private MarketMemoryBriefing() {
    }

    /**
     * The finished material block for {@code today}'s event classes, or null
     * when the register saw nothing today. German or English per {@code de};
     * numbers stay ROOT (the mixed-locale examiner reads both).
     */
    static String dayBlock(MarketEventArchive archive, LocalDate today, boolean de) {
        if (archive == null || today == null) return null;
        String day = today.toString();
        Map<String, List<MarketEventRecord>> todayByClass = new LinkedHashMap<>();
        for (MarketEventRecord e : archive.all()) {
            if (day.equals(e.date())) {
                todayByClass.computeIfAbsent(e.eventClass(), k -> new ArrayList<>()).add(e);
            }
        }
        if (todayByClass.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(512);
        sb.append(de
                ? "MARKT-GEDÄCHTNIS (Basisraten zu den heutigen Ereignisklassen - Prior, keine Prognose):\n"
                : "MARKET MEMORY (base rates for today's event classes - prior, never a prediction):\n");
        boolean anyRate = false;
        for (Map.Entry<String, List<MarketEventRecord>> entry : todayByClass.entrySet()) {
            String eventClass = entry.getKey();
            sb.append("- ").append(eventClass).append(de ? " heute: " : " today: ")
                    .append(instruments(entry.getValue(), de)).append('\n');
            var stats = BaseRates.forClass(eventClass, archive.byClass(eventClass), null);
            if (stats.isPresent()) {
                sb.append("  ").append(de ? "Hausstatistik: " : "house statistics: ")
                        .append(BaseRates.describe(stats.get())).append('\n');
                anyRate = true;
            }
            var prior = LiteraturePriors.priorFor(eventClass);
            if (prior.isPresent()) {
                sb.append("  ").append(de ? "Attribuierter Prior: " : "attributed prior: ")
                        .append(prior.get()).append('\n');
                anyRate = true;
            }
        }
        if (!anyRate) return null; // events without any rate or prior carry nothing tellable
        sb.append(de
                ? "  Disziplin: Basisraten sind Erfahrungswerte der KLASSE - gegen die heutige Lage abwägen, nie als Vorhersage aussprechen.\n"
                : "  Discipline: base rates are CLASS experience - weigh against today's picture, never voice as a forecast.\n");
        return sb.toString();
    }

    private static String instruments(List<MarketEventRecord> events, boolean de) {
        List<String> names = new ArrayList<>();
        for (MarketEventRecord e : events) {
            String n = e.symbol() != null && !e.symbol().isBlank() ? e.symbol() : e.isin();
            if (n != null && !names.contains(n)) names.add(n);
        }
        int shown = Math.min(MAX_INSTRUMENTS_SHOWN, names.size());
        StringBuilder sb = new StringBuilder(String.join(", ", names.subList(0, shown)));
        if (names.size() > shown) {
            sb.append(de ? " +" + (names.size() - shown) + " weitere"
                    : " +" + (names.size() - shown) + " more");
        }
        return sb.toString();
    }
}
