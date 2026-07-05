package de.bsommerfeld.wsbg.terminal.core.util;

/**
 * Pure HTML text helpers shared by the RSS parsers (FinancialJuice,
 * finanznachrichten, ...).
 * <p>
 * Dependency-free (only {@link String}) so it can live in {@code core}.
 */
public final class HtmlText {

    private HtmlText() {
    }

    /**
     * Un-escapes the basic HTML entity set ({@code &amp; &lt; &gt; &quot; &apos;})
     * in application order. Nothing else is touched; tag stripping and whitespace
     * handling stay the caller's concern.
     */
    public static String unescapeBasic(String text) {
        if (text == null) return null;
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
