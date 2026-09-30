package de.bsommerfeld.tinybrowser;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The browser the hidden tabs live in - {@link Chromium}, embedded in this
 * JVM. {@link Tab} and {@link Tabs} know nothing but this: another engine
 * would be another implementation (a headless Firefox over WebDriver BiDi
 * was one, measured and dropped on 2026-09-30).
 */
interface Browser {

    /** {@code Chromium 146.0.7680.179}. */
    String version();

    /**
     * Opens a hidden page on {@code url}.
     *
     * @param created receives the page before it starts loading
     * @param loaded  every main-frame load end of the page, with its HTTP status
     */
    void open(String url, Consumer<Page> created, IntConsumer loaded) throws Exception;

    /**
     * Sets a cookie for {@code site} and every subdomain of it.
     *
     * @return whether the store took it
     */
    boolean plantCookie(String site, String name, String value, Instant expires) throws Exception;

    /** Has the cookies written to the profile and lets go of the browser, within {@code timeoutMillis}. */
    void leave(long timeoutMillis);
}
