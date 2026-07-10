package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heuristic readable-text extraction feeding the news digest: paragraph text
 * wins over page chrome, scripts/styles never leak, entities unescape, and the
 * length cap holds (a runaway page must not eat the digest prompt).
 */
class ArticleReaderTest {

    private static String para(String text) {
        // extractReadableText only keeps <p> blocks of >= 40 chars.
        return "<p>" + text + " — padded to pass the forty-character paragraph floor.</p>";
    }

    @Test
    void prefersParagraphTextAndDropsScripts() {
        String html = "<html><head><script>var tracking = 'evil';</script>"
                + "<style>.nav { color: red; }</style></head><body>"
                + "<nav>Home News Login</nav>"
                + para("Die Quartalszahlen von Beispiel AG lagen bei 2,4 Milliarden Euro Umsatz")
                + para("Der Vorstand erwartet für das Gesamtjahr ein Wachstum von 12 Prozent")
                + para("Analysten hatten im Vorfeld mit deutlich weniger gerechnet, hieß es")
                + para("Die Aktie legte nach der Veröffentlichung um 4,2 Prozent zu im Handel")
                + para("Weitere Details will das Unternehmen im September nennen laut Bericht")
                + "</body></html>";

        String text = ArticleReader.extractReadableText(html);

        assertTrue(text.contains("2,4 Milliarden Euro"), "paragraph content survives");
        assertFalse(text.contains("tracking"), "script bodies never leak");
        assertFalse(text.contains(".nav"), "style bodies never leak");
        assertFalse(text.contains("Home News Login"), "nav chrome outside <p> is skipped");
    }

    @Test
    void unescapesEntitiesAndCollapsesWhitespace() {
        String html = para("Gewinn &amp; Verlust:   der Kurs stieg &gt; 10&nbsp;Prozent, sagte man")
                + para("Zweiter Absatz mit genug Inhalt, um den Längenfilter sicher zu passieren")
                + para("Dritter Absatz mit genug Inhalt, um die Paragraph-Schwelle zu überwinden")
                + para("Vierter Absatz mit noch etwas mehr Füllung für die Mindestlänge hier");

        String text = ArticleReader.extractReadableText(html);

        assertTrue(text.contains("Gewinn & Verlust"), "entities unescaped");
        assertTrue(text.contains("> 10 Prozent"), "&gt; and &nbsp; unescaped");
        assertFalse(text.contains("  "), "whitespace collapsed");
    }

    @Test
    void capsRunawayLength() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            html.append(para("Absatz " + i + " mit reichlich Text, damit die Seite die Kappe reisst"));
        }
        String text = ArticleReader.extractReadableText(html.toString());
        assertTrue(text.length() <= ArticleReader.ARTICLE_MAX_CHARS + 1, "capped (+ellipsis)");
    }

    @Test
    void emptyAndBlankInputYieldEmpty() {
        assertEquals("", ArticleReader.extractReadableText(null));
        assertEquals("", ArticleReader.extractReadableText("   "));
    }

    @Test
    void consentShellIsNoArticle() {
        // The EU consent interstitial a cookie-less client gets instead of a
        // Yahoo-hosted article: readable, always the same, long enough to pass the
        // length gate — the 2026-07-09 live run digested it for EVERY article. It
        // must be recognised as a shell and yield no text at all.
        String shell = "<html><body><form action=\"https://consent.yahoo.com/v2/collectConsent\">"
                + para("Wir und unsere Partner verarbeiten Daten, um Inhalte bereitzustellen und zu messen")
                + para("Klicken Sie auf Alle akzeptieren, um der Verarbeitung zuzustimmen und fortzufahren")
                + para("Weitere Informationen finden Sie jederzeit in unserer Datenschutzerklärung hier")
                + para("Sie können Ihre Auswahl unter Einstellungen verwalten jederzeit gerne widerrufen")
                + "</form></body></html>";
        ArticleReader reader = new ArticleReader(
                new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
                    @Override
                    public String name() {
                        return "test";
                    }

                    @Override
                    public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                            String url, java.util.Map<String, String> headers, java.time.Duration timeout) {
                        return new de.bsommerfeld.wsbg.terminal.source.net.WebResponse(
                                200, shell, java.util.Map.of());
                    }
                }, "test-ua");

        assertTrue(reader.fetchArticleText("https://finance.yahoo.com/news/x.html").isEmpty(),
                "the consent shell must never reach the digest model");
    }
}
