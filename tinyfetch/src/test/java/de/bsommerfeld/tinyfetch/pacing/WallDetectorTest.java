package de.bsommerfeld.tinyfetch.pacing;

import de.bsommerfeld.tinyfetch.api.Wall;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WallDetectorTest {

    private static final Function<String, Optional<String>> HTML =
            headers(Map.of("content-type", "text/html; charset=utf-8"));
    private static final Function<String, Optional<String>> JSON =
            headers(Map.of("content-type", "application/json"));

    @Test
    void plainAnswersAreNoWall() {
        assertEquals(Wall.NONE, WallDetector.classify(200, JSON, bytes("{\"ok\":true}")));
        assertEquals(Wall.NONE, WallDetector.classify(404, HTML, bytes("<h1>not found</h1>")));
        assertEquals(Wall.NONE, WallDetector.classify(503, HTML, bytes("maintenance")));
    }

    @Test
    void redditCaptchaWithStatus200IsAChallenge() {
        String page = "<html><head><title>Reddit - Prove your humanity</title></head>"
                + "<script src=\"https://www.google.com/recaptcha/api.js\"></script>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(200, HTML, bytes(page)));
    }

    @Test
    void redditNetworkBlockIsAChallengeNotAPlainRefusal() {
        String page = "<body>You've been blocked by network security. If you think ...</body>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(403, HTML, bytes(page)));
    }

    @Test
    void redditBlockTextBehindHugeInlineCssIsFound() {
        // The real page's layout: 189 KB of styles, the notice at the very end.
        String page = "<body class=theme-beta><div><style>" + ".a{color:red}".repeat(14_500) + "</style>"
                + "<p>You've been blocked by network security.</p></div></body>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(403, HTML, bytes(page)));
    }

    @Test
    void cloudflareInterstitialIsAChallenge() {
        String page = "<html><head><title>Just a moment...</title></head>"
                + "<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1\"></script>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(403, HTML, bytes(page)));
    }

    @Test
    void redditsOlderNetworkBlockIsAChallenge() {
        String page = "<h1>whoa there, pardner!</h1><p>Your request has been blocked due to a network policy.</p>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(403, HTML, bytes(page)));
    }

    @Test
    void googleSorryPageIsAChallengeNotAThrottleInAnyLanguage() {
        // Excerpt of the real German page (measured 2026-09-30), served with 429.
        String page = "Unsere Systeme haben ungewöhnlichen Datenverkehr aus Ihrem Computernetzwerk festgestellt."
                + " <a href=\"//support.google.com/websearch/answer/86640\">Weitere Informationen</a>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(429, HTML, bytes(page)));
    }

    @Test
    void challengeHeadersDecideForAnyContentType() {
        assertEquals(Wall.CHALLENGE, WallDetector.classify(403,
                headers(Map.of("content-type", "application/json", "cf-mitigated", "challenge")), bytes("{}")));
        assertEquals(Wall.CHALLENGE, WallDetector.classify(405,
                headers(Map.of("x-amzn-waf-action", "captcha")), bytes("")));
        assertEquals(Wall.CHALLENGE, WallDetector.classify(429,
                headers(Map.of("x-vercel-mitigated", " Challenge ")), bytes("")));
    }

    @Test
    void aMitigationThatIsNoChallengeStaysARefusal() {
        assertEquals(Wall.FORBIDDEN, WallDetector.classify(403,
                headers(Map.of("x-vercel-mitigated", "deny")), bytes("")));
    }

    @Test
    void cloudflaresBotScriptOnANormalPageIsNoWall() {
        // fool.com and businessinsider.de carry it on every page (measured 2026-09-30).
        String page = "<title>Stock Market News</title><script>a.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'";
        assertEquals(Wall.NONE, WallDetector.classify(200, HTML, bytes(page)));
    }

    @Test
    void perimeterXBlockPageIsAChallenge() {
        String page = "<title>Bloomberg - Are you a robot?</title><style>#px-captcha { width: 320px; }</style>"
                + "<script src=\"https://captcha.px-cloud.net/PXabc/captcha.js\"></script>";
        assertEquals(Wall.CHALLENGE, WallDetector.classify(403, HTML, bytes(page)));
    }

    @Test
    void perimeterXsHiddenCaptchaSlotOnANormalPageIsNoWall() {
        // Seeking Alpha keeps it in every page (measured 2026-09-30).
        String page = "<style>.px-captcha-visible{display:flex}</style>"
                + "<div id=\"px-captcha-wrapper\"><div id=\"px-captcha\"></div></div><h1>Market News</h1>";
        assertEquals(Wall.NONE, WallDetector.classify(200, HTML, bytes(page)));
    }

    @Test
    void aLoginFormsCaptchaWidgetIsNoWall() {
        String page = "<form id=\"login\"><div class=\"g-recaptcha\" data-sitekey=\"x\"></div></form>";
        assertEquals(Wall.NONE, WallDetector.classify(200, HTML, bytes(page)));
    }

    @Test
    void challengeMarkersInJsonAreData() {
        assertEquals(Wall.NONE, WallDetector.classify(200, JSON, bytes("{\"title\":\"prove your humanity\"}")));
    }

    @Test
    void throttlingStatuses() {
        assertEquals(Wall.THROTTLED, WallDetector.classify(429, JSON, bytes("")));
        assertEquals(Wall.THROTTLED, WallDetector.classify(503,
                headers(Map.of("retry-after", "30")), bytes("")));
    }

    @Test
    void bare403IsForbidden() {
        assertEquals(Wall.FORBIDDEN, WallDetector.classify(403, JSON, bytes("{\"reason\":\"private\"}")));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Function<String, Optional<String>> headers(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }
}
