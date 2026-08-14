package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.DossierFact;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brief's dossier block: permanent news facts render as established
 * knowledge with publisher/consolidated tags, a fact whose article currently
 * renders fresh is skipped (its digest already stands under the title), and
 * the consolidator's reply parsing strips bullets and caps lines.
 */
class DossierBriefTest {

    private static DossierFact fact(String url, String text, boolean consolidated) {
        return new DossierFact("TST.DE", null, "Testwerk AG", text,
                Instant.now().getEpochSecond() - 3600,
                "Titel", "Testblatt", url, null, consolidated);
    }

    @Test
    void dossierRendersAsEstablishedKnowledgeWithSourceTags() {
        SubjectUnit u = new SubjectUnit("TST.DE", "Testwerk AG");
        u.updateResolved("Testwerk AG", "TST.DE", null, List.of());
        String brief = UnitBriefWriter.unitBrief(u, true, BriefLabels.of("de"), null, List.of(
                fact("https://x/1", "Testwerk erhielt einen Auftrag über 80 Millionen Euro.", false),
                fact(null, "Seit Mai drei Aufträge über zusammen 200 Millionen Euro.", true)));
        assertTrue(brief.contains("FAKTENBLATT"), "the dossier block must render");
        assertTrue(brief.contains("Testblatt] Testwerk erhielt einen Auftrag über 80 Millionen Euro."),
                "a sourced fact carries its publisher tag");
        assertTrue(brief.contains("Sammelstand] Seit Mai drei Aufträge über zusammen 200 Millionen Euro."),
                "a consolidated line carries the consolidated tag");
    }

    @Test
    void factOfACurrentlyFreshArticleIsSkipped() {
        SubjectUnit u = new SubjectUnit("TST.DE", "Testwerk AG");
        u.updateResolved("Testwerk AG", "TST.DE", null, List.of(
                new Article("u1", "Frischer Titel", "Testblatt", "https://x/1",
                        Instant.now(), List.of())));
        String brief = UnitBriefWriter.unitBrief(u, true, BriefLabels.of("de"), null, List.of(
                fact("https://x/1", "Derselbe Artikel als Dossier-Fakt.", false)));
        assertFalse(brief.contains("FAKTENBLATT"),
                "a dossier whose only fact renders fresh above shows no block");
        assertFalse(brief.contains("Derselbe Artikel als Dossier-Fakt."));
    }

    @Test
    void parseSummariesStripsBulletsAndCaps() {
        assertEquals(List.of(), DossierConsolidator.parseSummaries(null));
        List<String> lines = DossierConsolidator.parseSummaries(
                "- Erste Sammelzeile.\n2) Zweite Sammelzeile.\n\n• Dritte.\nVierte.\nFünfte.\nSechste.\nSiebte.");
        assertEquals(6, lines.size(), "capped at six summary lines");
        assertEquals("Erste Sammelzeile.", lines.get(0));
        assertEquals("Zweite Sammelzeile.", lines.get(1));
    }
}
