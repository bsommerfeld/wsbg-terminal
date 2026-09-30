package de.bsommerfeld.tinybrowser;

import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefNativeAdapter;
import org.cef.callback.CefURLRequestClient;
import org.cef.network.CefPostData;
import org.cef.network.CefPostDataElement;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One request straight from Chromium's network stack, without a page - what
 * a {@code FETCH} alone asks for. The site meets the same browser as in a
 * tab: its TLS, its HTTP/2, its user agent, the profile's cookies and cache.
 * What it does not meet is a document. Measured against an echo service
 * (2026-09-30): {@code sec-fetch-mode: no-cors}, {@code sec-fetch-dest: empty},
 * {@code sec-fetch-site: same-origin}, but no {@code Referer} and none of the
 * {@code sec-ch-ua} client hints a page's {@code fetch()} carries - and no
 * {@code Accept} at all, so {@code *}{@code /*} is set here, as {@code fetch()}
 * sends it, unless the caller brings one.
 *
 * <h3>Redirects</h3>
 * CEF follows them silently and its response does not say where it ended up,
 * so each one stops here and is followed by hand - the answer names its final
 * address, which relative links in a feed resolve against.
 */
final class NetworkRequest {

    /*
     * CEF 146's cef_urlrequest_flags_t. JCEF's CefRequest.CefUrlRequestFlags
     * still carries an older numbering - its ALLOW_CACHED_CREDENTIALS (2) is
     * ONLY_FROM_CACHE today - so the values are CEF's own.
    */
    /** Cookies go along, and the answer's are kept. */
    static final int ALLOW_STORED_CREDENTIALS = 1 << 3;
    /** A redirect ends the request, with the 3xx and its {@code Location}. */
    static final int STOP_ON_REDIRECT = 1 << 7;

    /** Browsers give up after 20; so does this. */
    private static final int MAX_REDIRECTS = 20;

    private NetworkRequest() {
    }

    /**
     * @param headers the caller's own, already stripped of what the browser owns
     * @param body    {@code null} for none
     * @return the answer, or the network's reason for none
     */
    static Tab.Result send(String url, String method, Map<String, String> headers, byte[] body, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String current = URI.create(url).toASCIIString();
        String currentMethod = method;
        byte[] currentBody = body;
        for (int redirects = 0; ; redirects++) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Tab.Result.failed("no answer within " + timeout.toMillis() + " ms");
            }
            Tab.Result result = once(current, currentMethod, headers, currentBody, left);
            String location = result.failure() == null && isRedirect(result.status())
                    ? header(result.headers(), "location") : null;
            if (location == null) {
                return result;
            }
            if (redirects >= MAX_REDIRECTS) {
                return Tab.Result.failed("more than " + MAX_REDIRECTS + " redirects from " + url);
            }
            current = URI.create(current).resolve(URI.create(location.trim())).toASCIIString();
            // 303, and a POST on 301/302, go on as GET - as every browser does
            if (result.status() == 303 || (result.status() == 301 || result.status() == 302)
                    && !currentMethod.equals("GET") && !currentMethod.equals("HEAD")) {
                currentMethod = "GET";
                currentBody = null;
            }
        }
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Tab.Result once(String url, String method, Map<String, String> headers, byte[] body,
            long timeoutNanos) throws InterruptedException {
        CefRequest request = CefRequest.create();
        request.setURL(url);
        request.setMethod(method);
        Map<String, String> requestHeaders = new HashMap<>(headers);
        if (requestHeaders.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("accept"))) {
            requestHeaders.put("accept", "*/*");
        }
        request.setHeaderMap(requestHeaders);
        if (body != null) {
            CefPostDataElement element = CefPostDataElement.create();
            element.setToBytes(body.length, body);
            CefPostData postData = CefPostData.create();
            postData.addElement(element);
            request.setPostData(postData);
        }
        request.setFlags(ALLOW_STORED_CREDENTIALS | STOP_ON_REDIRECT);
        // Same-site to itself, so SameSite cookies go along as on a visit.
        request.setFirstPartyForCookies(url);

        Collector collector = new Collector();
        CefURLRequest sent = CefURLRequest.create(request, collector);
        if (sent == null) {
            return Tab.Result.failed("Chromium refused to send " + method + " " + url);
        }
        try {
            if (!collector.done.await(timeoutNanos, TimeUnit.NANOSECONDS)) {
                sent.cancel();
                return Tab.Result.failed("no answer within " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + " ms");
            }
            return collector.result(url);
        } finally {
            sent.dispose();
        }
    }

    private static String header(List<Map.Entry<String, String>> headers, String name) {
        for (Map.Entry<String, String> header : headers) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /** Takes the body as it streams in and the response when it completes - on CEF's UI thread. */
    private static final class Collector extends CefNativeAdapter implements CefURLRequestClient {

        final CountDownLatch done = new CountDownLatch(1);
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private volatile CefURLRequest.Status status = CefURLRequest.Status.UR_UNKNOWN;
        private volatile String error;
        private volatile int httpStatus;
        private volatile List<Map.Entry<String, String>> headers = List.of();

        @Override
        public void onRequestComplete(CefURLRequest request) {
            try {
                status = request.getRequestStatus();
                CefResponse response = request.getResponse();
                if (response != null) {
                    httpStatus = response.getStatus();
                    Map<String, String> map = new HashMap<>();
                    response.getHeaderMap(map);
                    List<Map.Entry<String, String>> collected = new ArrayList<>();
                    map.forEach((name, value) -> collected.add(Map.entry(name, value)));
                    headers = collected;
                }
                if (status != CefURLRequest.Status.UR_SUCCESS) {
                    error = String.valueOf(request.getRequestError());
                }
            } catch (Throwable failure) {
                error = "unreadable answer: " + failure;
            } finally {
                done.countDown();
            }
        }

        @Override
        public void onDownloadData(CefURLRequest request, byte[] data, int length) {
            synchronized (body) {
                body.write(data, 0, length);
            }
        }

        @Override
        public void onUploadProgress(CefURLRequest request, int current, int total) {
        }

        @Override
        public void onDownloadProgress(CefURLRequest request, int current, int total) {
        }

        @Override
        public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme,
                CefAuthCallback callback) {
            return false;
        }

        /**
         * A stopped redirect completes as a failure with its 3xx attached -
         * it is an answer all the same.
         */
        Tab.Result result(String url) {
            boolean answered = httpStatus > 0 && (status == CefURLRequest.Status.UR_SUCCESS || isRedirect(httpStatus));
            if (!answered) {
                return Tab.Result.failed(error != null ? error : "no answer (" + status + ")");
            }
            byte[] bytes;
            synchronized (body) {
                bytes = body.toByteArray();
            }
            return new Tab.Result(httpStatus, url, headers, bytes, null);
        }
    }
}
