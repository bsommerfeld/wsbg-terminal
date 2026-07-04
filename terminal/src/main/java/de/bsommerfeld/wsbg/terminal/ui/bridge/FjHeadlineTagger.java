package de.bsommerfeld.wsbg.terminal.ui.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Derives display tags + a high-impact flag from a FinancialJuice headline. Pure
 * presentation — extracted from {@link FjNewsPublisher} (and kept out of the
 * transport-neutral source layer) because tags are a UI concern recomputed at
 * render time.
 */
final class FjHeadlineTagger {

    private FjHeadlineTagger() {
    }

    /** Display tags for a FinancialJuice headline. */
    static List<String> extractTags(String title) {
        List<String> tags = new ArrayList<>();
        String lower = title.toLowerCase(Locale.ROOT);

        if (lower.contains("oil") || lower.contains("energy") || lower.contains("opec") || lower.contains("wti") || lower.contains("brent")) {
            tags.add("Energy");
        }
        if (lower.matches(".*\\b(iran|israel|war|lebanon|strait|hormuz|gaza|idf|geopolitics)\\b.*")) {
            tags.add("Geopolitics");
        }
        if (lower.matches(".*\\b(ecb|eur|europe|germany|france)\\b.*")) {
            tags.add("EUR");
            tags.add("Europe");
        }
        if (lower.matches(".*\\b(fed|powell|usd|us)\\b.*")) {
            tags.add("USD");
        }
        if (lower.matches(".*\\b(s&p|nasdaq|dow|spy|qqq|indexes|mag 7|moo imbalance)\\b.*")) {
            tags.add("US Indexes");
        }
        if (lower.matches(".*\\b(bonds|treasury|yield)\\b.*")) {
            tags.add("US Bonds");
        }
        if (lower.matches(".*\\b(boj|ueda|japan|jpy)\\b.*")) {
            tags.add("Asia");
            tags.add("JPY");
        }
        if (lower.matches(".*\\b(china|pboc|cny)\\b.*")) {
            tags.add("China");
        }
        if (lower.matches(".*\\b(boe|uk|gbp|starmer)\\b.*")) {
            tags.add("UK");
            tags.add("GBP");
        }

        return tags.stream().distinct().toList();
    }

    /** High-impact flag for UI styling — geopolitics or market-moving language. */
    static boolean isRed(String title, List<String> tags) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (tags.contains("Geopolitics")) return true;
        return lower.contains("blockade") || lower.contains("attack") || lower.contains("missile")
                || lower.contains("urgent") || lower.contains("market moving");
    }
}
