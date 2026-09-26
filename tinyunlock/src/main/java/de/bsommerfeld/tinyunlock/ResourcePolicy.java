package de.bsommerfeld.tinyunlock;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

/**
 * Loads what makes a page work and skips what only makes it look like one:
 * images, fonts, media, frames, prefetches and pings are cancelled. The page's
 * scripts, stylesheets and data requests go through - those are what a site's
 * visitor check runs on.
 */
final class ResourcePolicy extends CefRequestHandlerAdapter {

    private final CefResourceRequestHandler handler;

    ResourcePolicy() {
        this.handler = new CefResourceRequestHandlerAdapter() {
            @Override
            public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
                CefRequest.ResourceType type = request.getResourceType();
                if (type != null) {
                    switch (type) {
                        case RT_SUB_FRAME, RT_IMAGE, RT_FONT_RESOURCE, RT_OBJECT, RT_MEDIA, RT_PREFETCH,
                             RT_FAVICON, RT_PING, RT_CSP_REPORT, RT_PLUGIN_RESOURCE -> {
                            return true;
                        }
                        default -> {
                        }
                    }
                }
                return false;
            }
        };
    }

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
            CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
            BoolRef disableDefaultHandling) {
        return handler;
    }
}
