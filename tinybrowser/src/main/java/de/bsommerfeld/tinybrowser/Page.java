package de.bsommerfeld.tinybrowser;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/** One hidden page of a {@link Browser}: what a {@link Tab} parks on a site. */
interface Page {

    /** How long after the caller's own timeout the page gives up on a server that never answers. */
    long ABORT_MARGIN_MS = 30_000;

    /** Loads {@code url} in the page. Returns at once; the load end is reported to the page's listener. */
    void load(String url);

    /**
     * Runs one {@code fetch()} in the page's document and waits for its answer.
     *
     * @param credentials {@code include}: the site's cookies go along
     * @param body        sent as bytes; {@code null} for none
     * @return the answer, or the reason {@code fetch()} gave for none
     * @throws Exception no reply came within {@code timeout}
     */
    Tab.Result fetch(String url, String method, Map<String, String> headers, byte[] body, String credentials,
            Duration timeout) throws Exception;

    /** The document's source, for the log. */
    void source(Consumer<String> receiver);

    /** Closes the page; fetches in flight end as failed. Idempotent. */
    void close();
}
