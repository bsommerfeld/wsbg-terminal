package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The news-window economics after the 6→12 fetch raise: the char budget still
 * caps the fully-rendered items, the COMPACT tail keeps the rest visible as
 * title-only lines, and the {@code [N#]} ordinals stay a strict PREFIX of the
 * fresh list (the citation resolution indexes into that list — a gap would
 * resolve a citation onto the wrong article).
 */
class UnitBriefBudgetTest {

    /** Distinct topics per item so the story clusterer does not fold them into one. */
    private static final String[] TOPICS = {
            "Quartalszahlen übertreffen die Erwartungen deutlich",
            "Großauftrag aus Norwegen über achtzig Millionen",
            "Vorstand kündigt Aktienrückkauf im Herbst an",
            "Neue Fabrik in Magdeburg geht ans Netz",
            "Übernahmegerüchte um den Wettbewerber verdichten sich",
            "Analystenkonferenz nennt Margenziel für nächstes Jahr",
            "Patentstreit in Texas endet mit Vergleich",
            "Dividende steigt auf zwei Euro je Aktie",
            "Chipmangel bremst die Produktion im Werk Dresden",
            "Partnerschaft mit japanischem Zulieferer besiegelt",
            "Kartellamt genehmigt den Zukauf ohne Auflagen",
            "Belegschaft stimmt dem neuen Tarifvertrag zu"};

    private static Article news(int i, int summaryLen) {
        StringBuilder sum = new StringBuilder();
        while (sum.length() < summaryLen) {
            sum.append("Einzelheit ").append(i).append(" zu ").append(TOPICS[(i - 1) % TOPICS.length])
                    .append(' ');
        }
        return new Article("u" + i, "Testwerk: " + TOPICS[(i - 1) % TOPICS.length],
                "Testblatt", "https://example.org/" + i,
                Instant.now().minusSeconds(60L * i), List.of(), null,
                sum.substring(0, summaryLen), false);
    }

    private static SubjectUnit unitWith(int items) {
        SubjectUnit u = new SubjectUnit("TST.DE", "Testwerk AG");
        List<Article> news = new ArrayList<>();
        for (int i = 1; i <= items; i++) news.add(news(i, 200));
        u.updateResolved("Testwerk AG", "TST.DE", null, news);
        u.addEvidence(new SubjectUnit.EvidenceRef("t1", "c1",
                "Die Affen kaufen Testwerk.", "reddit", Instant.now().getEpochSecond()));
        return u;
    }

    @Test
    void ordinalsStayAStrictPrefixOfTheFreshList() {
        String brief = UnitBriefWriter.unitBrief(unitWith(12), true, BriefLabels.of("de"), null);
        Matcher m = Pattern.compile("\\[N(\\d+)\\]").matcher(brief);
        int expected = 0;
        while (m.find()) {
            assertEquals(++expected, Integer.parseInt(m.group(1)),
                    "ordinals must be gapless and in order — the citation resolver indexes by them");
        }
        assertTrue(expected >= 10, "the compact tail carries the box beyond the old 6-item window");
    }

    @Test
    void printBudgetGauge() {
        // Measurement, not assertion: brief size at the old fetch cap (6) vs the new
        // one (12, compact tail) — chars and a ≈4-chars/token estimate.
        for (int items : new int[] {6, 12}) {
            String brief = UnitBriefWriter.unitBrief(unitWith(items), true, BriefLabels.of("de"), null);
            long ordinals = Pattern.compile("\\[N\\d+\\]").matcher(brief).results().count();
            System.out.printf(Locale.ROOT,
                    "[BRIEF] %2d Items gefetcht → %d gerendert | %d Zeichen ≈ %d Tokens%n",
                    items, ordinals, brief.length(), brief.length() / 4);
        }
    }
}
