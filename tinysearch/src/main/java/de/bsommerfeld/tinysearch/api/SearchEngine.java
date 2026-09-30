package de.bsommerfeld.tinysearch.api;

/**
 * The search engines TinySearch asks. Each is read from its own result
 * page, the HTML a browser gets for a search - measured 2026-09-30 through
 * TinyFetch: every constant here answers with its hits in that HTML.
 *
 * <h2>Whose index answers</h2>
 * Several engines resell another's index, so asking them all is not
 * asking seven indexes: Bing, Brave and Yandex run their own, DuckDuckGo
 * and Yahoo answer largely from Bing's, Startpage from Google's, and
 * Ecosia's hits carry the provider EUSP, the European index it runs with
 * Qwant.
 *
 * <h2>Measured and left out</h2>
 * Besides Google and Marginalia (see the notes after the constants), none
 * of these is usable as it stands (2026-09-30):
 * <ul>
 *   <li>Mojeek - a JavaScript challenge page; a tab parked on its search
 *       page was answered with 403.</li>
 *   <li>Qwant - its API refuses with a challenge (403).</li>
 *   <li>Swisscows - the hits only arrive through the page's script.</li>
 *   <li>Seznam - turns a German query Czech and answers with Czech pages.</li>
 *   <li>Baidu - the tab never got ready.</li>
 *   <li>AOL - redirects to Yahoo.</li>
 *   <li>Mwmbl - JSON only, and its tab came up in one run of three.</li>
 * </ul>
 */
public enum SearchEngine {

    /** Microsoft's own index. */
    BING("www.bing.com"),

    /** Brave's own index; twenty hits a page. */
    BRAVE("search.brave.com"),

    /** DuckDuckGo's page for browsers without JavaScript. */
    DUCKDUCKGO("html.duckduckgo.com"),

    ECOSIA("www.ecosia.org"),

    /** Google's hits, fetched by Startpage - how Google is asked for now. */
    STARTPAGE("www.startpage.com"),

    /** Bing's index; seven hits a page. */
    YAHOO("search.yahoo.com"),

    /** Yandex's own index. */
    YANDEX("yandex.com");

    /*
     * GOOGLE("www.google.com") - left out; STARTPAGE brings Google's hits.
     * Measured 2026-09-30 through TinyFetch:
     *  - fetch() of /search (with &gbv=1 too) answers 200 with Google's
     *    JavaScript shell and no hits: a <noscript> refresh to
     *    /httpservice/retry/enablejs and an obfuscated check script. The
     *    hits only come once that script has run, which a fetch() never
     *    does. TinyFetch's WallDetector does not know the shell, so it
     *    passes as an ordinary answer.
     *  - A tab parked on a /search page runs the script - and Google
     *    answered with /sorry/ (429, reCAPTCHA, "unusual traffic from your
     *    computer network"): the hidden tab is taken for a bot, and the
     *    network stays flagged for a while.
     * To bring it back, the hidden tab has to pass Google's check. Which
     * signal gives it away is not known - a tab that draws nothing, the
     * images it never loads, a fresh profile. Measure before enabling:
     * every attempt risks another /sorry/ for the user's network.
     *
     * MARGINALIA - an independent, open-source index of the small web.
     * Measured 2026-09-30:
     *  - The website (marginalia-search.com, old-search.marginalia.nu)
     *    puts a "Wait a moment" page before every query: its script
     *    forwards after a countdown to the same search plus a token the
     *    server issued (&sst=...). A fetch() never runs it; a tab parked on
     *    a search page got through for that one query only.
     *  - The API (api.marginalia.nu/public/search/<query>, JSON) answers
     *    the shared "public" key only now and then - once in 0.2 s,
     *    otherwise nothing within 15 s. With a key of our own it is the way
     *    in (about.marginalia-search.com/article/api). It needs a JSON
     *    reader and an anchor on the API host, whose root redirects away.
     */

    private final String host;

    SearchEngine(String host) {
        this.host = host;
    }

    /** The host the searches go to - the unit TinyFetch paces and pauses. */
    public String host() {
        return host;
    }
}
