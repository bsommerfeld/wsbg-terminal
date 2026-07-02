package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Locale;

/**
 * Localised structural labels for the editorial briefs ({@link EditorialAgent#unitBrief}
 * and {@link ReportBuilder}). The brief the model reads is built in Java and was
 * historically English-only; a 4B model fed an English scaffold around German Reddit
 * data then code-switches English structure into its German headline. Rendering the
 * labels in the model's own output language keeps the whole context in one language.
 *
 * <p><b>English is the canonical/default set</b> and matches the strings the builders
 * emitted before localisation existed — the no-language builder overloads and the unit
 * tests rely on that. German is additive. Structural tokens the prompts reference by
 * name ({@code [STALE]}, {@code [news:ID]}, {@code [✓ ...]}, {@code POLL}, ticker fields)
 * stay identical across languages on purpose, so the prompt and the brief agree.
 */
final class BriefLabels {

    static final BriefLabels EN = new BriefLabels(false);
    static final BriefLabels DE = new BriefLabels(true);

    private final boolean de;

    private BriefLabels(boolean de) {
        this.de = de;
    }

    /** Picks the label set for a language code; English for {@code null}/unknown/{@code "en"}. */
    static BriefLabels of(String langCode) {
        return "de".equalsIgnoreCase(langCode) ? DE : EN;
    }

    /** Relative age phrasing: English trails ("5m ago"), German leads ("vor 5m"). */
    String ago(String age) {
        return de ? "vor " + age : age + " ago";
    }

    // ---- unitBrief ----

    String subjectHeader(String name, String tickerOrNull) {
        StringBuilder sb = new StringBuilder(de ? "=== THEMA: " : "=== SUBJECT: ");
        sb.append(name);
        if (tickerOrNull != null) sb.append(" (").append(tickerOrNull).append(")");
        return sb.append(" ===\n").toString();
    }

    String defaultVenue() {
        return de ? "Markt" : "Market";
    }

    String liveData(String venue, double price, String currencySuffix) {
        return String.format(Locale.ROOT,
                de ? "Live-Marktdaten (%s): %.2f%s" : "Live market data (%s): %.2f%s",
                venue, price, currencySuffix);
    }

    String liveDataIndex(String venue, double price) {
        return String.format(Locale.ROOT,
                de ? "Live-Marktdaten (%s): %.0f Punkte (Index)"
                   : "Live market data (%s): %.0f Punkte (Index)",
                venue, price);
    }

    String dayMove(double pct) {
        return String.format(Locale.ROOT, de ? ", Tag %+.2f%%" : ", day %+.2f%%", pct);
    }

    String marketClosed() {
        return de
                ? " [Markt geschlossen — letzter Kurs, KEIN Live-Preis: nicht als frische Bewegung darstellen]"
                : " [Market closed — last close, NOT a live price: do not present as a fresh move]";
    }

    /**
     * Multi-day price context, numbers only — the model reads the arc, we never
     * interpret it. Renders only the parts that exist; empty when none do.
     */
    String trend(Double fiveDayPct, Double monthPct, Double offHighPct) {
        StringBuilder sb = new StringBuilder();
        if (fiveDayPct != null) {
            sb.append(String.format(Locale.ROOT,
                    de ? ", 5 Handelstage %+.1f%%" : ", 5 trading days %+.1f%%", fiveDayPct));
        }
        if (monthPct != null) {
            sb.append(String.format(Locale.ROOT,
                    de ? ", 1 Monat %+.1f%%" : ", 1 month %+.1f%%", monthPct));
        }
        if (offHighPct != null) {
            sb.append(String.format(Locale.ROOT,
                    de ? ", %.0f%% unter dem 52-Wochen-Hoch" : ", %.0f%% below the 52-week high",
                    Math.abs(offHighPct)));
        }
        return sb.toString();
    }

    String sinceFirstMention(String ageStr, double pct, double anchor, double price) {
        return String.format(Locale.ROOT,
                de ? "; seit Erstnennung (vor %s): %+.2f%% (%.2f → %.2f)"
                   : "; since first mention (%s ago): %+.2f%% (%.2f → %.2f)",
                ageStr, pct, anchor, price);
    }

    String noTicker() {
        return de
                ? "Kein Ticker — Thema/Person; schreibe aus der Stimmung des Raums, ohne Ticker.\n"
                : "No ticker — theme/person; write from the room's sentiment, no ticker.\n";
    }

    String newsHeader() {
        return de
                ? "News (dein verifizierter Anker — nimm daraus die harten Fakten."
                + " [STALE] = älterer Hintergrund, KEIN frischer Katalysator):\n"
                : "News (your verified anchor — take the hard facts from it."
                + " [STALE] = older background, NOT a fresh catalyst):\n";
    }

    String newsToldTag() {
        return de
                ? "[ERZÄHLT — steckt schon in deinen Schlagzeilen unten; als Anker nutzbar, nicht als Neuigkeit]"
                : "[TOLD — already woven into your headlines below; usable as the anchor, not as news]";
    }

    String visionLoc() {
        return de ? "Bild" : "image";
    }

    String evidenceHeader(boolean haveHeadlines) {
        if (de) {
            return "\nWas der Raum über DIESES Thema gesagt hat"
                    + (haveHeadlines
                        ? " (NEU seit der letzten Schlagzeile — ältere Erwähnungen sind bereits von den"
                        + " Schlagzeilen unten abgedeckt):\n"
                        : " (Belege — die Story):\n");
        }
        return "\nWhat the room said about THIS subject"
                + (haveHeadlines
                    ? " (NEW since the last headline — older mentions are already covered by the headlines below):\n"
                    : " (evidence — the story):\n");
    }

    String coveredOmitted(int n) {
        return de
                ? "  (" + n + " frühere Erwähnung(en) bereits in den Schlagzeilen unten enthalten —"
                + " weggelassen; schreibe einen Nachschlag nur aus dem neuen Material hier)\n"
                : "  (" + n + " earlier mention(s) already reflected in the prior headlines below —"
                + " omitted; write a follow-up only from the new material here)\n";
    }

    String budgetOmitted(int n) {
        return de
                ? "  (" + n + " weitere ältere Erwähnung(en) weggelassen, um ins Kontext-Budget zu passen —"
                + " die Schlagzeilen unten spiegeln sie wider)\n"
                : "  (" + n + " further older mention(s) omitted to fit the context budget —"
                + " prior headlines below reflect them)\n";
    }

    String conversationContext() {
        return de
                ? "Gespräch, auf das diese Erwähnungen eine Antwort waren (Kontext — NICHT das Thema"
                + " selbst, sondern der Diskussionsstrang, in dem es genannt wurde):\n"
                : "Conversation those mentions were a reply to (context — NOT the subject "
                + "itself, but the thread of discussion it was named in):\n";
    }

    String storyMemoryHeader() {
        return de
                ? "\nDEINE BISHERIGEN SCHLAGZEILEN ZU DIESEM THEMA (die Geschichte bisher —"
                + " deine nächste Zeile ist ihr nächster Satz):\n"
                : "\nYOUR HEADLINES SO FAR FOR THIS SUBJECT (the story so far —"
                + " your next line is its next sentence):\n";
    }

    String earlierHeadlines(int n, String ageStr) {
        return de
                ? "  (+" + n + " frühere Schlagzeile(n) seit " + ago(ageStr) + " — die Story ist ÄLTER als"
                + " die gezeigten Zeilen; ein dort schon erzählter Aspekt ist keine Neuigkeit mehr)\n"
                : "  (+" + n + " earlier headline(s) since " + ago(ageStr) + " — the story is OLDER than the"
                + " lines shown; an angle already told there is no longer news)\n";
    }

    String sentimentArcPrefix() {
        return de ? "Stimmungsverlauf bisher: " : "Sentiment arc so far: ";
    }

    // ---- ReportBuilder ----

    String caseId(String id) {
        return (de ? "--- FALL-ID: " : "--- CASE ID: ") + id + " ---\n";
    }

    String clusterTopic() {
        return de ? "Cluster-Thema: " : "Cluster Topic: ";
    }

    String clusterAge() {
        return de ? "Cluster-Alter: " : "Cluster Age: ";
    }

    String activeThreads() {
        return de ? "Aktive Threads: " : "Active Threads: ";
    }

    String tickersSeen() {
        return de ? "Gesehene Ticker: " : "Tickers seen: ";
    }

    String priorHeadlinesHeader() {
        return de
                ? "\nFRÜHERE SCHLAGZEILEN FÜR DIESEN CLUSTER (chronologisch — bereits veröffentlicht,"
                + " NICHT wiederholen):\n"
                : "\nPRIOR HEADLINES FOR THIS CLUSTER (chronological — already published, do NOT repeat):\n";
    }

    String priorHeadlineLine(int n, String ageStr, String headline) {
        return "  H" + n + " [" + ago(ageStr) + "]: " + headline + "\n";
    }

    String onlyFreshEvidence() {
        return de
                ? "Nur die FRISCHEN Belege unten (neu seit der letzten Schlagzeile) werden vollständig"
                + " gezeigt; was die früheren Schlagzeilen bereits abgedeckt haben, ist weggelassen. Trägt"
                + " das frische Material neue Information, veröffentliche einen Nachschlag. Ist nichts"
                + " Frisches gezeigt, hat dieser Cluster nichts Neues — weiter zum nächsten Cluster.\n"
                : "Only the FRESH evidence below (new since the last headline) is shown in full; "
                + "what the prior headlines already covered is omitted. If the fresh material carries "
                + "new information, publish a follow-up. If nothing fresh is shown, this cluster has "
                + "nothing new — move on to the next dirty cluster.\n";
    }

    String threadSourceHeader(int idx, boolean covered) {
        String tag = covered
                ? (de ? " [✓ POST ABGEDECKT — nur neue Kommentare/Bilder unten]"
                      : " [✓ POST COVERED — only new comments/images below]")
                : "";
        return (de ? "=== THREAD-QUELLE " : "=== THREAD SOURCE ") + idx + tag + " ===\n";
    }

    String idLine(String id) {
        return "ID: " + id + "\n";
    }

    String titleLine(String title) {
        return (de ? "Titel: " : "Title: ") + title + "\n";
    }

    String communityQuestion() {
        return de
                ? "POST-TYP: COMMUNITY-FRAGE — der Titel ist nur ein Aufhänger;"
                + " das eigentliche Signal steckt in den Kommentaren unten.\n"
                : "POST TYPE: COMMUNITY QUESTION — the title is a prompt;"
                + " the real signal lives in the comments below.\n";
    }

    String imageLabel(int idx, int total) {
        if (total == 1) return de ? "[BILD-ANALYSE]" : "[IMAGE ANALYSIS]";
        return de ? "[BILD " + idx + "/" + total + " ANALYSE]"
                  : "[IMAGE " + idx + "/" + total + " ANALYSIS]";
    }

    String newImageLabel(int idx, int total) {
        return de ? "[NEUES BILD " + idx + "/" + total + " — seit der letzten Schlagzeile analysiert]"
                  : "[NEW IMAGE " + idx + "/" + total + " — analysed since the last headline]";
    }

    String contentSnippet() {
        return de ? "Inhalt-Auszug: " : "Content Snippet: ";
    }

    String pollPrefix() {
        return de ? "UMFRAGE: " : "POLL: ";
    }

    String pollTotalOpen() {
        return de ? "  (gesamt " : "  (total ";
    }

    String pollLive() {
        return ", LIVE";
    }

    String pollEnded() {
        return de ? ", BEENDET" : ", ENDED";
    }

    String relevantCommentsHeader() {
        return de
                ? "RELEVANTE KOMMENTARE (frisch + neue Bild-Belege, in Gesprächsreihenfolge —"
                + " Antworten sind unter dem Kommentar eingerückt, auf den sie antworten):\n"
                : "RELEVANT COMMENTS (fresh + new image evidence, in conversation order — "
                + "replies are indented under the comment they answer):\n";
    }

    String scoreTag(int score) {
        if (score < 0) {
            return de ? "Score: " + score + " — von der Meute heruntergevotet"
                      : "Score: " + score + " — downvoted by the crowd";
        }
        return "Score: " + score;
    }

    String newImageEvidence() {
        return de ? " [neue Bild-Belege seit der letzten Schlagzeile]:\n"
                  : " [new image evidence since the last headline]:\n";
    }

    String commentImage() {
        return de ? "    [KOMMENTAR-BILD]: " : "    [COMMENT IMAGE]: ";
    }

    String alreadyCoveredHeader() {
        return de
                ? "BEREITS ABGEDECKT (hast du schon veröffentlicht — NICHT wiederholen):\n"
                : "ALREADY COVERED (you already published these — do NOT repeat them):\n";
    }

    String earlierCoveredTag() {
        return de ? " [früher — bereits abgedeckt]: " : " [earlier — already covered]: ";
    }

    String unknown() {
        return de ? "Unbekannt" : "Unknown";
    }
}
