package de.bsommerfeld.wsbg.terminal.handelsblatt;

import java.util.List;

/**
 * One Handelsblatt / WirtschaftsWoche article as far as this house is willing
 * to read it (probed 2026-08-02).
 *
 * <p>The publisher classifies every piece in {@code meta.classification} as
 * {@code FREE}, {@code METERED} or {@code PREMIUM}, independently of the
 * {@code contentAccessCategory} the teaser surfaces carry ({@code NONE},
 * {@code H_PLUS}, {@code H_PLUS_PREMIUM_BUSINESS}, {@code WIWO_PLUS}) - a
 * teaser marked {@code NONE} is very often classified {@code METERED}.
 *
 * <p><b>Only {@code FREE} carries a {@link #text()}.</b> See
 * {@link HandelsblattNewsClient#article(String)} for why {@code METERED} is
 * deliberately treated like {@code PREMIUM} even though the technical door
 * stands open.
 *
 * @param link           the article permalink it was read from
 * @param classification {@code FREE} / {@code METERED} / {@code PREMIUM}, or
 *                       {@code null} when the publisher named none
 * @param accessCategory {@code NONE} / {@code H_PLUS} /
 *                       {@code H_PLUS_PREMIUM_BUSINESS} / {@code WIWO_PLUS}
 * @param headline       the article headline (always available)
 * @param leadText       the teaser / lead paragraph (always available)
 * @param isins          ISINs the newsroom itself linked in the piece
 *                       ({@code <a href="/boerse/isin/…" class="vhb-stock-icon">}) -
 *                       an EDITORIAL instrument tag, worth more than any fuzzy
 *                       name match. Empty when the piece names no instrument.
 * @param text           the plain-text body, or {@code null} when the piece is
 *                       not {@code FREE} - never a partially walled body
 */
public record HandelsblattArticle(
        String link,
        String classification,
        String accessCategory,
        String headline,
        String leadText,
        List<String> isins,
        String text) {

    public HandelsblattArticle {
        isins = isins == null ? List.of() : List.copyOf(isins);
    }

    /** True when a full body was read - i.e. the piece is classified FREE. */
    public boolean hasBody() {
        return text != null && !text.isBlank();
    }
}
