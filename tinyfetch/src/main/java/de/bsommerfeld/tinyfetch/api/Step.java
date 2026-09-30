package de.bsommerfeld.tinyfetch.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the browser does for one request. A site that checks its visitor first
 * wants all three; a plain file - a feed, a sitemap, a public API - answers
 * the first request, and every step before it is one more request a strict
 * rate limit counts.
 *
 * <pre>{@code
 * FetchRequest.of(url)                                   // LOAD_PAGE, READINESS_CHECK, FETCH
 * FetchRequest.of(url, LOAD_PAGE, READINESS_CHECK, FETCH) // the same, spelled out
 * FetchRequest.of(url, LOAD_PAGE, FETCH)                 // a page, but no probing
 * FetchRequest.of(url, FETCH)                            // one request, no page
 * }</pre>
 */
public enum Step {

    /**
     * Park a hidden tab on the site ({@code https://<host>/}, or the host's
     * {@link TinyFetch.Builder#anchor anchor}) and ask from inside it: the
     * request is the page's own {@code fetch()}, same-origin, with the
     * cookies and whatever the site's scripts set up for their visitor. Costs
     * the page load - once per tab, and a tab stays for as long as it is used.
     */
    LOAD_PAGE,

    /**
     * Before the first request, probe until the site lets the tab through:
     * interstitials resolve, a first-visit check gets its second visit, a
     * refusal is waited out. Later refusals reload the page. Needs
     * {@link #LOAD_PAGE}.
     */
    READINESS_CHECK,

    /**
     * The request itself. Alone, it is sent straight from the browser's
     * network stack - the same browser, cookies, cache and TLS to the site,
     * but no page around it: no {@code Referer}, none of the client hints a
     * page sends, {@code sec-fetch-mode: no-cors}. Redirects are followed.
     */
    FETCH;

    /** What a request does unless told otherwise: every step. */
    public static final Set<Step> ALL = Collections.unmodifiableSet(EnumSet.allOf(Step.class));

    /**
     * The steps as a set, checked: {@link #FETCH} always,
     * {@link #READINESS_CHECK} only with {@link #LOAD_PAGE}. None at all
     * means {@link #ALL}.
     *
     * @throws IllegalArgumentException for a combination that asks for nothing or probes without a page
     */
    public static Set<Step> of(Step... steps) {
        if (steps.length == 0) {
            return ALL;
        }
        EnumSet<Step> set = EnumSet.noneOf(Step.class);
        for (Step step : steps) {
            set.add(Objects.requireNonNull(step, "step"));
        }
        if (!set.contains(FETCH)) {
            throw new IllegalArgumentException("steps without FETCH ask for nothing: " + set);
        }
        if (set.contains(READINESS_CHECK) && !set.contains(LOAD_PAGE)) {
            throw new IllegalArgumentException("READINESS_CHECK probes from a page - it needs LOAD_PAGE");
        }
        return Collections.unmodifiableSet(set);
    }
}
