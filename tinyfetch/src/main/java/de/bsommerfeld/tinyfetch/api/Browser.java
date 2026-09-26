package de.bsommerfeld.tinyfetch.api;

/**
 * The browser TinyFetch presents as. One per client, for its whole lifetime:
 * a person does not switch browsers between two clicks, and a session whose
 * fingerprint changes mid-way is exactly what bot detection looks for.
 *
 * <p>Each constant pairs a libcurl-impersonate target (TLS ClientHello,
 * HTTP/2 settings and pseudo-header order) with the headers that browser
 * version sends. Both halves must describe the same version - a Chrome 150
 * handshake with a Chrome 136 user agent is a contradiction a server can see.
 *
 * <h3>Keeping it current</h3>
 * Browsers update every four weeks, and a fingerprint many versions behind
 * the current one grows conspicuous. Raise the version together with the
 * library ({@code .script/natives.sh}), taking the brand list from what the
 * new target actually sends.
 */
public enum Browser {

    /** Chrome 150 on desktop - the platform in the headers follows the machine. */
    CHROME("chrome150", "150",
            "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"", null),

    /**
     * A desktop application with embedded Chromium (CEF 132, Chromium
     * 132.0.6834.83) - the engine TinyUnlock opens pages in, and the one the
     * terminal's embedded browser always was. Measured 2026-09-26 from that
     * engine against tls.peet.ws: TLS and HTTP/2 equal the {@code chrome131}
     * target exactly (JA4 {@code t13d1516h2_8daaf6152771_02713d6af862}), the
     * brand list carries no "Google Chrome", and CEF's accept-language is
     * {@code en-US,en;q=0.9} unless the application sets its own.
     *
     * <p>The client to use with a {@code SessionUnlocker}: cookies a site
     * issues to that engine come back from the same fingerprint.
     */
    CHROMIUM_EMBEDDED("chrome131", "132",
            "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"", "en-US,en;q=0.9");

    private final String impersonateTarget;
    private final String majorVersion;
    private final String brands;
    private final String defaultAcceptLanguage;

    Browser(String impersonateTarget, String majorVersion, String brands, String defaultAcceptLanguage) {
        this.impersonateTarget = impersonateTarget;
        this.majorVersion = majorVersion;
        this.brands = brands;
        this.defaultAcceptLanguage = defaultAcceptLanguage;
    }

    /** The libcurl-impersonate target name, e.g. {@code chrome150}. */
    public String impersonateTarget() {
        return impersonateTarget;
    }

    /** Major version as the user agent writes it. */
    public String majorVersion() {
        return majorVersion;
    }

    /** The {@code sec-ch-ua} value. */
    public String brands() {
        return brands;
    }

    /**
     * The accept-language this browser sends out of the box, or {@code null}
     * when it follows the person's settings (then {@link TinyFetch.Builder#acceptLanguage}
     * decides).
     */
    public String defaultAcceptLanguage() {
        return defaultAcceptLanguage;
    }
}
