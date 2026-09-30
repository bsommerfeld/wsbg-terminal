package de.bsommerfeld.tinybrowser;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * What a hidden tab may load, and the one header it may change.
 *
 * <h3>Loads</h3>
 * A tab's page is a session anchor: the document and its scripts establish
 * the cookies and the origin the {@code fetch()} calls ride on. What merely
 * makes a page look like one - frames, images, fonts, media, pings - is
 * cancelled, as the terminal's hidden tabs on master did: every one of them
 * costs a renderer, the network and the browser's UI thread for nothing.
 *
 * <h3>User agent</h3>
 * A page's {@code fetch()} cannot set {@code User-Agent}. An API that wants
 * the application to name itself (SEC EDGAR's, Wikimedia's) gets the caller's
 * value through {@link #USER_AGENT_MARKER}, swapped in here.
 */
final class ResourcePolicy extends CefRequestHandlerAdapter {

    /** Carries a caller's user agent from the page to here; never leaves the browser. */
    static final String USER_AGENT_MARKER = "x-tinybrowser-user-agent";

    private final CefResourceRequestHandler handler = new CefResourceRequestHandlerAdapter() {
        @Override
        public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            CefRequest.ResourceType type = request.getResourceType();
            if (type != null) {
                switch (type) {
                    case RT_SUB_FRAME, RT_IMAGE, RT_FONT_RESOURCE, RT_OBJECT, RT_MEDIA, RT_PREFETCH,
                         RT_FAVICON, RT_PING, RT_CSP_REPORT, RT_PLUGIN_RESOURCE, RT_SHARED_WORKER,
                         RT_NAVIGATION_PRELOAD_SUB_FRAME -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            swapUserAgent(request);
            return false;
        }
    };

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
            CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
            BoolRef disableDefaultHandling) {
        return handler;
    }

    private static void swapUserAgent(CefRequest request) {
        String userAgent = request.getHeaderByName(USER_AGENT_MARKER);
        if (userAgent == null || userAgent.isEmpty()) {
            return;
        }
        Map<String, String> headers = new HashMap<>();
        request.getHeaderMap(headers);
        headers.keySet().removeIf(name -> name.equalsIgnoreCase(USER_AGENT_MARKER)
                || name.equalsIgnoreCase("user-agent"));
        headers.put("User-Agent", userAgent);
        request.setHeaderMap(headers);
    }
}
