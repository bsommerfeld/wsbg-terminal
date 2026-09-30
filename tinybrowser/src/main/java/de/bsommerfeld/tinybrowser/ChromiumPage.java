package de.bsommerfeld.tinybrowser;

import org.cef.browser.CefBrowser;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A hidden Chromium tab. Its fetches leave as a script ({@link PageFetch}) and
 * come home as router messages, which this collects until an answer is whole.
 *
 * <h3>Threading</h3>
 * {@link #fetch} is safe from any number of threads except the EDT, which
 * JCEF needs to pump the very work a fetch waits for.
 */
final class ChromiumPage implements Page {

    private final Chromium chromium;
    private final String url;
    private final String tag = PageFetch.randomTag();
    private final BiConsumer<CefBrowser, Integer> loadEndListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile CefBrowser browser;

    /**
     * @param url    the address the page is opened on
     * @param loaded every main-frame load end of this page, with its HTTP status
     */
    ChromiumPage(Chromium chromium, String url, IntConsumer loaded) {
        this.chromium = chromium;
        this.url = url;
        this.loadEndListener = (loadedBrowser, status) -> {
            if (loadedBrowser == browser) {
                loaded.accept(status);
            }
        };
    }

    /** Ties the page to its browser, before that starts loading. */
    void attach(CefBrowser browser) {
        this.browser = browser;
        chromium.onMessages(tag, this::handleMessage);
        chromium.addLoadEndListener(loadEndListener);
    }

    @Override
    public void load(String url) {
        CefBrowser tab = browser;
        if (tab != null) {
            SwingUtilities.invokeLater(() -> tab.loadURL(url));
        }
    }

    @Override
    public Tab.Result fetch(String url, String method, Map<String, String> headers, byte[] body,
            String credentials, Duration timeout) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("a page's fetch must not run on the EDT - JCEF is pumped there");
        }
        CefBrowser tab = browser;
        if (tab == null || closed.get()) {
            throw new IllegalStateException("page on " + this.url + " is closed");
        }
        long id = nextId.incrementAndGet();
        Pending waiting = new Pending();
        pending.put(id, waiting);
        try {
            tab.executeJavaScript(PageFetch.script(chromium.queryFunction(), tag, credentials, id, url, method,
                    headers, body, timeout.toMillis() + ABORT_MARGIN_MS), this.url, 0);
            return waiting.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void source(Consumer<String> receiver) {
        CefBrowser tab = browser;
        if (tab != null) {
            tab.getSource(receiver::accept);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        chromium.removeMessages(tag);
        chromium.removeLoadEndListener(loadEndListener);
        CefBrowser closing = browser;
        browser = null;
        if (closing != null) {
            chromium.closeTab(closing);
        }
        pending.values().forEach(waiting -> waiting.future.complete(Tab.Result.failed("tab closed")));
    }

    // ---- the way home ----------------------------------------------------------

    /** One router message of this page, in {@link PageFetch}'s layout. Runs on the browser UI thread. */
    private void handleMessage(String message) {
        String[] parts = message.split(String.valueOf(PageFetch.DELIMITER), 3);
        char type = parts[1].charAt(0);
        String rest = parts[2];
        switch (type) {
            case 'M' -> {
                String[] fields = rest.split(String.valueOf(PageFetch.DELIMITER), 5);
                Pending waiting = pending.get(Long.parseLong(fields[0]));
                if (waiting != null) {
                    waiting.onMeta(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3],
                            PageFetch.headers(fields.length > 4 ? fields[4] : ""));
                }
            }
            case 'C' -> {
                String[] fields = rest.split(String.valueOf(PageFetch.DELIMITER), 3);
                Pending waiting = pending.get(Long.parseLong(fields[0]));
                if (waiting != null) {
                    waiting.onChunk(Integer.parseInt(fields[1]), fields.length > 2 ? fields[2] : "");
                }
            }
            case 'E' -> {
                String[] fields = rest.split(String.valueOf(PageFetch.DELIMITER), 2);
                Pending waiting = pending.get(Long.parseLong(fields[0]));
                if (waiting != null) {
                    waiting.future.complete(
                            Tab.Result.failed("page fetch failed: " + (fields.length > 1 ? fields[1] : "")));
                }
            }
            default -> Log.debug(url + ": unknown message type " + type);
        }
    }

    /** Collects the meta and the chunks of one fetch until it is whole. */
    private static final class Pending {
        final CompletableFuture<Tab.Result> future = new CompletableFuture<>();
        private final Map<Integer, String> chunks = new HashMap<>();
        private int total = -1;
        private int status;
        private String url = "";
        private List<Map.Entry<String, String>> headers = List.of();

        synchronized void onMeta(int total, int status, String url, List<Map.Entry<String, String>> headers) {
            this.total = total;
            this.status = status;
            this.url = url;
            this.headers = headers;
            maybeComplete();
        }

        synchronized void onChunk(int sequence, String data) {
            chunks.put(sequence, data);
            maybeComplete();
        }

        private void maybeComplete() {
            if (future.isDone() || total < 0 || chunks.size() < total) {
                return;
            }
            /*
             * This runs on the browser UI thread. Joining and decoding a body
             * of several MB there would hold up every tab; the thread waiting
             * for it does it instead.
            */
            int count = total;
            Map<Integer, String> parts = new HashMap<>(chunks);
            Tab.Result meta = new Tab.Result(status, url, headers, null, null);
            future.completeAsync(() -> {
                StringBuilder encoded = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String part = parts.get(i);
                    if (part != null) {
                        encoded.append(part);
                    }
                }
                byte[] body = Base64.getDecoder().decode(encoded.toString());
                return new Tab.Result(meta.status(), meta.url(), meta.headers(), body, null);
            });
        }
    }
}
