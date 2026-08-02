package de.bsommerfeld.wsbg.terminal.handelsblatt;

/**
 * The two mastheads of the Handelsblatt Media Group this source reads. They run
 * the SAME headless CMS API on two hosts, so one client serves both - the brand
 * only decides which host is asked and which byline the item carries.
 *
 * <p>The API lives on the {@code content.www.*} sub-host, which serves no
 * {@code robots.txt} at all and answers without a {@code User-Agent}, cookie or
 * {@code Referer} (probed 2026-08-02). The sitemaps live on the editorial host.
 */
public enum HandelsblattBrand {

    /** Handelsblatt - 1.411 topic pages, 12 section feeds. */
    HANDELSBLATT("handelsblatt", "Handelsblatt", "Handelsblatt Plus",
            "https://content.www.handelsblatt.com",
            "https://www.handelsblatt.com",
            "https://feeds.cms.handelsblatt.com/%s"),

    /** WirtschaftsWoche - 937 topic pages, 6 section feeds, same CMS. */
    WIWO("wiwo", "WirtschaftsWoche", "WirtschaftsWoche Plus",
            "https://content.www.wiwo.de",
            "https://www.wiwo.de",
            "https://feeds.cms.wiwo.de/rss/%s");

    private final String key;
    private final String publisher;
    private final String gatedPublisher;
    private final String apiBase;
    private final String siteBase;
    private final String feedPattern;

    HandelsblattBrand(String key, String publisher, String gatedPublisher,
                      String apiBase, String siteBase, String feedPattern) {
        this.key = key;
        this.publisher = publisher;
        this.gatedPublisher = gatedPublisher;
        this.apiBase = apiBase;
        this.siteBase = siteBase;
        this.feedPattern = feedPattern;
    }

    /** Stable short key used in feed keys ({@code handelsblatt/finanzen}). */
    public String key() {
        return key;
    }

    /** Byline for freely readable pieces. */
    public String publisher() {
        return publisher;
    }

    /** Byline for pieces behind the subscription wall - headline stays, body does not. */
    public String gatedPublisher() {
        return gatedPublisher;
    }

    /** {@code https://content.www.…} - the keyless JSON API host. */
    public String apiBase() {
        return apiBase;
    }

    /** {@code https://www.…} - the editorial host permalinks and sitemaps live on. */
    public String siteBase() {
        return siteBase;
    }

    /** RSS URL for a section key, e.g. {@code finanzen}. */
    public String feedUrl(String sectionKey) {
        return String.format(feedPattern, sectionKey);
    }
}
