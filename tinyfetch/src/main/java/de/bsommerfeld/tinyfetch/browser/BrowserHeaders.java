package de.bsommerfeld.tinyfetch.browser;

import de.bsommerfeld.tinyfetch.api.Browser;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.curl.NativePlatform;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the header lines Chrome sends for a request, in Chrome's order.
 *
 * <h3>Why order and values matter</h3>
 * Servers fingerprint the header block as much as the TLS handshake: which
 * headers, which values, which order. Everything here is what desktop Chrome
 * sends - measured against Chrome 150 through libcurl-impersonate's own
 * default set (tls.peet.ws, 2026-09-25) for navigations, and Chrome's
 * {@code fetch()} layout for script requests. The platform in the user agent
 * and in {@code sec-ch-ua-platform} is the machine's own, so the claim matches
 * where the traffic comes from.
 *
 * <h3>What a caller can change</h3>
 * A caller header with a browser header's name replaces its value in place;
 * any other caller header joins after {@code accept}. Conditional headers
 * ({@code if-none-match}, {@code if-modified-since}) sit before
 * {@code priority}, where Chrome puts them on a revalidation.
 */
public final class BrowserHeaders {

    /** What Chrome announces; libcurl must be told to decode the same set. */
    public static final String ACCEPT_ENCODING = "gzip, deflate, br, zstd";

    private static final String NAVIGATION_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,"
            + "image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";
    private static final String SCRIPT_ACCEPT = "*/*";

    /** Second-level labels under which a country TLD registers domains (example.co.uk). */
    private static final List<String> SECOND_LEVEL_LABELS = List.of("co", "com", "net", "org", "gov", "ac", "edu");

    private final Browser browser;
    private final String userAgent;
    private final String platformHint;
    private final String acceptLanguage;

    public BrowserHeaders(Browser browser, NativePlatform platform, String acceptLanguage) {
        this.browser = browser;
        this.acceptLanguage = acceptLanguage;

        /*
         * Chrome freezes the OS part of its user agent: every Mac reports
         * 10_15_7 on Intel, every Windows NT 10.0 - so these strings are what
         * real machines of each OS send, not a simplification.
        */
        String system = switch (platform) {
            case MACOS -> "Macintosh; Intel Mac OS X 10_15_7";
            case WINDOWS -> "Windows NT 10.0; Win64; x64";
            case LINUX -> "X11; Linux x86_64";
        };
        this.userAgent = "Mozilla/5.0 (" + system + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + browser.majorVersion() + ".0.0.0 Safari/537.36";
        this.platformHint = switch (platform) {
            case MACOS -> "\"macOS\"";
            case WINDOWS -> "\"Windows\"";
            case LINUX -> "\"Linux\"";
        };
    }

    public String userAgent() {
        return userAgent;
    }

    /**
     * @param conditional validator headers to add ({@code if-none-match} ...), may be empty
     * @return {@code name: value} lines in send order
     */
    public List<String> build(FetchRequest request, Map<String, String> conditional) {
        Optional<URI> referer = request.referer().map(from -> refererFor(from, request.uri()));
        String site = fetchSite(request, referer);

        List<String[]> headers = request.kind() == FetchRequest.Kind.PAGE
                ? navigation(request, referer, site)
                : script(request, referer, site);

        List<String[]> extra = new ArrayList<>();
        for (Map.Entry<String, String> header : request.headers()) {
            if (!replace(headers, header.getKey(), header.getValue())) {
                extra.add(new String[] {header.getKey(), header.getValue()});
            }
        }
        headers.addAll(indexOf(headers, "accept") + 1, extra);

        int priority = indexOf(headers, "priority");
        List<String[]> validators = new ArrayList<>();
        conditional.forEach((name, value) -> validators.add(new String[] {name, value}));
        headers.addAll(priority < 0 ? headers.size() : priority, validators);

        List<String> lines = new ArrayList<>(headers.size());
        for (String[] header : headers) {
            lines.add(header[0] + ": " + header[1]);
        }
        return lines;
    }

    /** A top-level document request - Chrome's navigation header block. */
    private List<String[]> navigation(FetchRequest request, Optional<URI> referer, String site) {
        List<String[]> headers = new ArrayList<>();
        add(headers, "sec-ch-ua", browser.brands());
        add(headers, "sec-ch-ua-mobile", "?0");
        add(headers, "sec-ch-ua-platform", platformHint);
        if (request.body() != null) {
            add(headers, "origin", origin(referer.orElse(request.uri())));
            add(headers, "content-type", request.contentType().orElseThrow());
        }
        add(headers, "upgrade-insecure-requests", "1");
        add(headers, "user-agent", userAgent);
        add(headers, "accept", NAVIGATION_ACCEPT);
        add(headers, "sec-fetch-site", site);
        add(headers, "sec-fetch-mode", "navigate");
        add(headers, "sec-fetch-user", "?1");
        add(headers, "sec-fetch-dest", "document");
        referer.ifPresent(from -> add(headers, "referer", from.toString()));
        add(headers, "accept-encoding", ACCEPT_ENCODING);
        add(headers, "accept-language", acceptLanguage);
        add(headers, "priority", "u=0, i");
        return headers;
    }

    /** A {@code fetch()} from the open page - Chrome's CORS-mode header block. */
    private List<String[]> script(FetchRequest request, Optional<URI> referer, String site) {
        List<String[]> headers = new ArrayList<>();
        add(headers, "sec-ch-ua-platform", platformHint);
        add(headers, "user-agent", userAgent);
        add(headers, "sec-ch-ua", browser.brands());
        request.contentType().ifPresent(type -> add(headers, "content-type", type));
        add(headers, "sec-ch-ua-mobile", "?0");
        add(headers, "accept", SCRIPT_ACCEPT);
        /*
         * Chrome sends origin on every non-GET and on every cross-origin
         * request - the one place a script request names the page it runs on.
        */
        URI page = referer.orElse(request.uri());
        if (!request.method().equals("GET") || !sameOrigin(page, request.uri())) {
            add(headers, "origin", origin(page));
        }
        add(headers, "sec-fetch-site", site);
        add(headers, "sec-fetch-mode", "cors");
        add(headers, "sec-fetch-dest", "empty");
        add(headers, "referer", referer.map(URI::toString).orElse(origin(request.uri()) + "/"));
        add(headers, "accept-encoding", ACCEPT_ENCODING);
        add(headers, "accept-language", acceptLanguage);
        add(headers, "priority", "u=1, i");
        return headers;
    }

    /**
     * {@code sec-fetch-site}: a navigation without referer is the person
     * typing the address ({@code none}); a script request without referer
     * runs on the target's own site ({@code same-origin}).
     */
    static String fetchSite(FetchRequest request, Optional<URI> referer) {
        if (referer.isEmpty()) {
            return request.kind() == FetchRequest.Kind.PAGE ? "none" : "same-origin";
        }
        URI from = referer.get();
        if (sameOrigin(from, request.uri())) {
            return "same-origin";
        }
        return site(from.getHost()).equals(site(request.uri().getHost())) ? "same-site" : "cross-site";
    }

    /** Chrome's {@code strict-origin-when-cross-origin}: cross-origin referers shrink to the origin. */
    static URI refererFor(URI referer, URI target) {
        return sameOrigin(referer, target) ? referer : URI.create(origin(referer) + "/");
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean defaultPort = uri.getPort() == -1
                || (scheme.equals("https") && uri.getPort() == 443)
                || (scheme.equals("http") && uri.getPort() == 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + uri.getPort());
    }

    /**
     * The registrable domain - close enough to the public suffix list for
     * {@code sec-fetch-site}: the last two labels, three under a country
     * second level ({@code example.co.uk}).
     */
    static String site(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        if (labels.length <= 2) {
            return String.join(".", labels);
        }
        int keep = labels[labels.length - 1].length() == 2
                && SECOND_LEVEL_LABELS.contains(labels[labels.length - 2]) ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(labels, labels.length - keep, labels.length));
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static void add(List<String[]> headers, String name, String value) {
        headers.add(new String[] {name, value});
    }

    private static boolean replace(List<String[]> headers, String name, String value) {
        int index = indexOf(headers, name);
        if (index < 0) {
            return false;
        }
        headers.get(index)[1] = value;
        return true;
    }

    private static int indexOf(List<String[]> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i)[0].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
