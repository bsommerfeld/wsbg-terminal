package de.bsommerfeld.tinyreddit.api;

/**
 * The ways into Reddit, best data first. {@link RedditClient} walks them in
 * order and answers from the first that works; every result names its route
 * ({@link Fetched#route()}), because what a value means depends on it.
 */
public enum Route {

    /**
     * The official API on {@code oauth.reddit.com}, application-only OAuth
     * (no user login; the app is registered once at
     * {@code reddit.com/prefs/apps}). Full data. Identifies as the app, not as
     * a browser: Reddit's API rules require a descriptive user agent, and the
     * client id is what the rate budget (100 requests a minute) is bound to.
     */
    OAUTH,

    /**
     * The {@code .json} view of Reddit's pages on {@code www.reddit.com}. Full
     * data, no registration. Requested as the user's own browser opening the
     * address. Reddit refuses anonymous access from some networks outright
     * (measured 2026-09-25 on a German residential line: CAPTCHA, whatever the
     * client) - the route is then paused, not pushed.
     */
    JSON,

    /**
     * The Atom feeds of the same pages. Requested like {@link #JSON}. Reduced
     * data: no scores, ratios, comment counts or polls; comments flat under
     * their post, about 100 per post; no lookup by id.
     */
    RSS
}
