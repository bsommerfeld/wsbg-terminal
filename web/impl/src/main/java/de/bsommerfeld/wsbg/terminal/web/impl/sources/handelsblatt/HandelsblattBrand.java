package de.bsommerfeld.wsbg.terminal.web.impl.sources.handelsblatt;

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

    /** Handelsblatt - 1.411 topic pages. */
    HANDELSBLATT("handelsblatt", "Handelsblatt", "Handelsblatt Plus",
            "https://content.www.handelsblatt.com",
            "https://www.handelsblatt.com"),

    /** WirtschaftsWoche - 937 topic pages, same CMS. */
    WIWO("wiwo", "WirtschaftsWoche", "WirtschaftsWoche Plus",
            "https://content.www.wiwo.de",
            "https://www.wiwo.de");

    private final String key;
    private final String publisher;
    private final String gatedPublisher;
    private final String apiBase;
    private final String siteBase;

    HandelsblattBrand(String key, String publisher, String gatedPublisher,
                      String apiBase, String siteBase) {
        this.key = key;
        this.publisher = publisher;
        this.gatedPublisher = gatedPublisher;
        this.apiBase = apiBase;
        this.siteBase = siteBase;
    }

    /** Stable short key. */
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
}
