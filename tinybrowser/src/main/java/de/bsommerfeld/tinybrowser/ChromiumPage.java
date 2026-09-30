package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.SocketFrame;
import org.cef.browser.CefBrowser;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A hidden Chromium tab. Its fetches leave as a script ({@link PageFetch}) and
 * come home as router messages, which this collects until an answer is whole;
 * its sockets ({@link PageSocket}) live in its document and report the same way.
 *
 * <h3>A renderer that dies</h3>
 * takes the document along, and nothing reports it but CEF: the sockets it
 * held close with {@code 1006}, and the page loads again - at most once per
 * {@link #REVIVE_COOLDOWN_MS}, so a page that kills its renderer on every load
 * cannot loop.
 *
 * <h3>Threading</h3>
 * {@link #fetch} is safe from any number of threads except the EDT, which
 * JCEF needs to pump the very work a fetch waits for. The socket calls return
 * at once, from any thread.
 */
final class ChromiumPage implements Page {

    private static final long REVIVE_COOLDOWN_MS = 60_000;

    private final Chromium chromium;
    private final String url;
    private final String tag = PageFetch.randomTag();
    private final BiConsumer<CefBrowser, Integer> loadEndListener;
    private final Consumer<CefBrowser> terminationListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile CefBrowser browser;
    private volatile long revivedAt;

    /** Every socket of this page until its Close is delivered, with where its life goes. */
    private final Map<Long, Consumer<SocketFrame>> sockets = new ConcurrentHashMap<>();
    /** The part of the message each socket is in the middle of. */
    private final Map<Long, StringBuilder> parts = new ConcurrentHashMap<>();
    private final Object socketLock = new Object();
    /** Opens waiting for a document - the first, or the next after the renderer died. Guarded by socketLock. */
    private final List<SocketFrame.Open> waitingOpens = new ArrayList<>();
    /** Whether a document is there for sockets to live in. Guarded by socketLock. */
    private boolean document;
    /**
     * The sockets' router messages leave the browser's UI thread here, one
     * after another: joining parts and decoding base64 there would hold up
     * every tab, and the frames they become are written to the client from
     * here. Its thread exists only while a socket has something to say.
     */
    private final ExecutorService socketEvents = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("tinybrowser-socket-events").factory());

    /**
     * @param url    the address the page is opened on
     * @param loaded every main-frame load end of this page, with its HTTP status
     */
    ChromiumPage(Chromium chromium, String url, IntConsumer loaded) {
        this.chromium = chromium;
        this.url = url;
        this.loadEndListener = (loadedBrowser, status) -> {
            if (loadedBrowser == browser) {
                documentLoaded();
                loaded.accept(status);
            }
        };
        this.terminationListener = died -> {
            if (died == browser) {
                rendererDied();
            }
        };
    }

    /** Ties the page to its browser, before that starts loading. */
    void attach(CefBrowser browser) {
        this.browser = browser;
        chromium.onMessages(tag, this::handleMessage);
        chromium.addLoadEndListener(loadEndListener);
        chromium.addTerminationListener(terminationListener);
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
    public void openSocket(SocketFrame.Open open, Consumer<SocketFrame> events) {
        sockets.put(open.id(), events);
        synchronized (socketLock) {
            if (!closed.get()) {
                if (document) {
                    run(PageSocket.open(chromium.queryFunction(), tag, open));
                } else {
                    waitingOpens.add(open);
                }
                return;
            }
        }
        finish(open.id(), 1006, "tab closed");
    }

    @Override
    public void sendSocket(SocketFrame frame) {
        if (!sockets.containsKey(frame.id())) {
            return;
        }
        synchronized (socketLock) {
            if (frame instanceof SocketFrame.Close && waitingOpens.removeIf(open -> open.id() == frame.id())) {
                Log.info(url + ": socket " + frame.id() + " given up while the page still had no document");
                finish(frame.id(), 1006, "closed before it opened");
            } else if (document) {
                run(PageSocket.send(tag, frame));
            }
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
        chromium.removeTerminationListener(terminationListener);
        CefBrowser closing = browser;
        browser = null;
        if (closing != null) {
            chromium.closeTab(closing);
        }
        pending.values().forEach(waiting -> waiting.future.complete(Tab.Result.failed("tab closed")));
        synchronized (socketLock) {
            waitingOpens.clear();
            document = false;
        }
        sockets.keySet().forEach(id -> finish(id, 1006, "tab closed"));
        socketEvents.shutdown();
    }

    // ---- the sockets' document -------------------------------------------------

    /** A new document: the sockets of the one before went with it, and the waiting ones open in this one. */
    private void documentLoaded() {
        synchronized (socketLock) {
            dropLiveSockets("the page navigated away");
            document = true;
            for (SocketFrame.Open open : waitingOpens) {
                run(PageSocket.open(chromium.queryFunction(), tag, open));
            }
            waitingOpens.clear();
        }
    }

    private void rendererDied() {
        synchronized (socketLock) {
            document = false;
            dropLiveSockets("the tab's renderer died");
        }
        long now = System.currentTimeMillis();
        if (now - revivedAt < REVIVE_COOLDOWN_MS) {
            Log.info(url + ": renderer died again - the page stays dead");
            return;
        }
        revivedAt = now;
        Log.info(url + ": renderer died - loading the page again");
        load(url);
    }

    /** Closes the sockets that lived in the document - all but those still waiting for one. Holds socketLock. */
    private void dropLiveSockets(String reason) {
        for (Long id : sockets.keySet()) {
            if (waitingOpens.stream().noneMatch(open -> open.id() == id)) {
                finish(id, 1006, reason);
            }
        }
    }

    /**
     * Delivers a socket's Close, unless it had one already - after whatever
     * of it is still queued, so nothing it said is lost.
     */
    private void finish(long id, int code, String reason) {
        Runnable delivery = () -> {
            parts.remove(id);
            Consumer<SocketFrame> events = sockets.remove(id);
            if (events != null) {
                events.accept(new SocketFrame.Close(id, code, reason));
            }
        };
        try {
            socketEvents.execute(delivery);
        } catch (RejectedExecutionException closedPage) {
            delivery.run();
        }
    }

    private void run(String script) {
        CefBrowser tab = browser;
        if (tab != null) {
            tab.executeJavaScript(script, url, 0);
        }
    }

    // ---- the way home ----------------------------------------------------------

    /**
     * One router message of this page, in {@link PageFetch}'s or
     * {@link PageSocket}'s layout. Runs on the browser UI thread, which a
     * socket's message leaves at once.
     */
    private void handleMessage(String message) {
        int typeAt = tag.length() + 1;
        if (message.length() > typeAt && PageSocket.carries(message.charAt(typeAt))) {
            char type = message.charAt(typeAt);
            try {
                socketEvents.execute(() -> handleSocketMessage(type, message));
            } catch (RejectedExecutionException closedPage) {
                // the page is closed, and its sockets with it
            }
            return;
        }
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

    /** One router message of a socket, in {@link PageSocket}'s layout. Runs on socketEvents, in arrival order. */
    private void handleSocketMessage(char type, String message) {
        try {
            String[] fields = message.split(String.valueOf(PageFetch.DELIMITER), 4);
            long id = Long.parseLong(fields[2]);
            String rest = fields.length > 3 ? fields[3] : "";
            switch (type) {
                case PageSocket.PART -> parts.computeIfAbsent(id, key -> new StringBuilder()).append(rest);
                case PageSocket.CLOSED -> {
                    parts.remove(id);
                    String[] close = rest.split(String.valueOf(PageFetch.DELIMITER), 2);
                    Consumer<SocketFrame> events = sockets.remove(id);
                    if (events != null) {
                        events.accept(new SocketFrame.Close(id, Integer.parseInt(close[0]),
                                close.length > 1 ? PageSocket.unescape(close[1]) : ""));
                    }
                }
                case PageSocket.OPENED -> {
                    Consumer<SocketFrame> events = sockets.get(id);
                    if (events != null) {
                        events.accept(new SocketFrame.Opened(id, rest));
                    }
                }
                default -> {
                    StringBuilder begun = parts.remove(id);
                    String whole = begun == null ? rest : begun.append(rest).toString();
                    Consumer<SocketFrame> events = sockets.get(id);
                    if (events != null) {
                        events.accept(type == PageSocket.BINARY
                                ? new SocketFrame.Message(id, true, Base64.getDecoder().decode(whole))
                                : new SocketFrame.Message(id, false,
                                        PageSocket.unescape(whole).getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
        } catch (RuntimeException failure) {
            Log.debug(url + ": malformed socket message: " + failure);
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
