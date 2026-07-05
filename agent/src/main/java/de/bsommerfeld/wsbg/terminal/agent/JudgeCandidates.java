package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats the identity judge's candidate lines: the NAME plus the hard facts we
 * already hold (instrument type + exchange), so the verdict rests on delivered
 * facts rather than the model's world knowledge of the name — "Kakao Corp —
 * EQUITY, KSC (Seoul)" is decidable without knowing the company. Shared by
 * {@link IdentityVeto} and {@link JudgeMatcher}.
 */
final class JudgeCandidates {

    private JudgeCandidates() {}

    static List<String> candidateLines(List<YahooQuote> quotes) {
        List<String> lines = new ArrayList<>(quotes == null ? 0 : quotes.size());
        if (quotes == null) return lines;
        for (YahooQuote q : quotes) {
            StringBuilder b = new StringBuilder(q.displayName());
            String type = q.quoteType() == null ? "" : q.quoteType().trim();
            String exch = q.exchangeDisplay() == null || q.exchangeDisplay().isBlank()
                    ? (q.exchange() == null ? "" : q.exchange().trim()) : q.exchangeDisplay().trim();
            if (!type.isEmpty() || !exch.isEmpty()) {
                b.append(" — ");
                if (!type.isEmpty()) b.append(type.toUpperCase(Locale.ROOT));
                if (!type.isEmpty() && !exch.isEmpty()) b.append(", ");
                if (!exch.isEmpty()) b.append(exch);
            }
            lines.add(b.toString());
        }
        return lines;
    }
}
