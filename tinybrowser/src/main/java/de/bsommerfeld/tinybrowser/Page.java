package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.SocketFrame;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/** One hidden page of a {@link Browser}: what a {@link Tab} parks on a site, and what {@link Sockets} keeps sockets in. */
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

    /**
     * Opens a WebSocket in the page's document, as the site's own scripts
     * would - once the page has a document, the first or the next. Returns at
     * once; the socket's life goes to {@code events}: {@link SocketFrame.Opened},
     * {@link SocketFrame.Message}s, and one {@link SocketFrame.Close}, last -
     * in order, off the browser's UI thread. A document that goes, and a
     * renderer that dies, close the sockets they held with {@code 1006}.
     */
    void openSocket(SocketFrame.Open open, Consumer<SocketFrame> events);

    /**
     * Sends a {@link SocketFrame.Message} on a socket of this page, or
     * {@link SocketFrame.Close}s it; a socket that is gone drops it. Returns
     * at once; the socket's own Close still comes through its events.
     */
    void sendSocket(SocketFrame frame);

    /** The document's source, for the log. */
    void source(Consumer<String> receiver);

    /** Closes the page; fetches in flight end as failed, sockets close with {@code 1006}. Idempotent. */
    void close();
}
