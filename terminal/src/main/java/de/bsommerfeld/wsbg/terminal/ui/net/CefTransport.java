package de.bsommerfeld.wsbg.terminal.ui.net;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.Transport;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;

import java.time.Duration;
import java.util.Map;

/**
 * The {@link FetchUtil#BROWSER} transport of the new world: adapts the CEF
 * joker ({@link CefWebFetcher} — hidden Chromium tab per origin, real session
 * and cookies) onto the {@code web/api} {@link Transport} contract. The
 * terminal contributes this into web/impl's transport multibinder at
 * bootstrap, because only the terminal owns the embedded browser; without it
 * the house fetcher simply skips every BROWSER mode.
 *
 * <p>Text-only by declaration: the JS fetch bridge cannot carry undecoded
 * bytes, and page-context POSTs stay the OpenWeb outlier's private door — so
 * binary and POST requests fall through to the DIRECT transport by contract,
 * which is exactly the old world's behaviour.
 */
public final class CefTransport implements Transport {

    private final CefWebFetcher joker;

    public CefTransport(CefWebFetcher joker) {
        this.joker = joker;
    }

    @Override
    public FetchUtil util() {
        return FetchUtil.BROWSER;
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout)
            throws Exception {
        return joker.fetch(url, headers, timeout);
    }
}
