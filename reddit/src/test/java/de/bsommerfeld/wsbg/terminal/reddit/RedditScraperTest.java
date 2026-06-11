package de.bsommerfeld.wsbg.terminal.reddit;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RedditScraper's utility methods and inner types.
 * HTTP-dependent methods (scanSubreddit, fetchThreadContext) are not tested
 * here since they require live Reddit endpoints.
 */
class RedditScraperTest {

    private RedditRepository repository;
    private RedditScraper scraper;

    @BeforeEach
    void setUp() {
        repository = mock(RedditRepository.class);
        scraper = new RedditScraper(repository, new GlobalConfig());
    }

    // -- normalizePermalink --

    @Test
    void normalizePermalink_shouldStripTrailingSlash() throws Exception {
        assertEquals("/r/wsb/comments/abc", invokeNormalize("/r/wsb/comments/abc/"));
    }

    @Test
    void normalizePermalink_shouldAddLeadingSlash() throws Exception {
        assertEquals("/r/wsb/comments/abc", invokeNormalize("r/wsb/comments/abc"));
    }

    @Test
    void normalizePermalink_shouldHandleAlreadyNormalized() throws Exception {
        assertEquals("/r/wsb/comments/abc", invokeNormalize("/r/wsb/comments/abc"));
    }

    // -- isImageUrl --

    @Test
    void isImageUrl_shouldRecognizeJpg() throws Exception {
        assertTrue(invokeIsImageUrl("https://i.redd.it/photo.jpg"));
    }

    @Test
    void isImageUrl_shouldRecognizePng() throws Exception {
        assertTrue(invokeIsImageUrl("https://i.redd.it/chart.png"));
    }

    @Test
    void isImageUrl_shouldRecognizeWebp() throws Exception {
        assertTrue(invokeIsImageUrl("https://i.imgur.com/img.webp"));
    }

    @Test
    void isImageUrl_shouldRecognizeGif() throws Exception {
        assertTrue(invokeIsImageUrl("https://i.imgur.com/meme.gif"));
    }

    @Test
    void isImageUrl_shouldRecognizeUrlsWithQueryParams() throws Exception {
        assertTrue(invokeIsImageUrl("https://cdn.example.com/image.jpg?width=640"));
    }

    @Test
    void isImageUrl_shouldRejectNonImageUrl() throws Exception {
        assertFalse(invokeIsImageUrl("https://www.reddit.com/r/wsb"));
    }

    @Test
    void isImageUrl_shouldReturnFalseForNull() throws Exception {
        assertFalse(invokeIsImageUrl(null));
    }

    // -- isRealAuthor --

    @Test
    void isRealAuthor_shouldAcceptRealUsername() throws Exception {
        assertTrue(invokeIsRealAuthor("DeepFuckingValue"));
    }

    @Test
    void isRealAuthor_shouldRejectAnon() throws Exception {
        assertFalse(invokeIsRealAuthor("anon"));
    }

    @Test
    void isRealAuthor_shouldRejectDeleted() throws Exception {
        assertFalse(invokeIsRealAuthor("[deleted]"));
    }

    @Test
    void isRealAuthor_shouldRejectUnknown() throws Exception {
        assertFalse(invokeIsRealAuthor("unknown"));
    }

    // -- unescapeHtml --

    @Test
    void unescapeHtml_shouldDecodeAmpersand() throws Exception {
        assertEquals("a&b", invokeUnescapeHtml("a&amp;b"));
    }

    @Test
    void unescapeHtml_shouldDecodeMultipleEntities() throws Exception {
        assertEquals("<tag attr=\"val\">", invokeUnescapeHtml("&lt;tag attr=&quot;val&quot;&gt;"));
    }

    @Test
    void unescapeHtml_shouldReturnNullForNull() throws Exception {
        assertNull(invokeUnescapeHtml(null));
    }

    // -- stripTrailingPunctuation --

    @Test
    void stripTrailingPunctuation_shouldRemoveParenthesis() throws Exception {
        assertEquals("https://example.com", invokeStripPunctuation("https://example.com)"));
    }

    @Test
    void stripTrailingPunctuation_shouldRemoveMultiplePunctuation() throws Exception {
        assertEquals("https://example.com", invokeStripPunctuation("https://example.com)."));
    }

    @Test
    void stripTrailingPunctuation_shouldLeaveCleanUrlAlone() throws Exception {
        assertEquals("https://example.com/path", invokeStripPunctuation("https://example.com/path"));
    }

    // -- ScrapeStats --

    @Test
    void scrapeStats_defaultsShouldBeZero() {
        var stats = new ScrapeStats();
        assertEquals(0, stats.newThreads);
        assertEquals(0, stats.newUpvotes);
        assertEquals(0, stats.newComments);
        assertFalse(stats.hasUpdates());
    }

    @Test
    void scrapeStats_hasUpdates_shouldDetectNewThreads() {
        var stats = new ScrapeStats();
        stats.newThreads = 1;
        assertTrue(stats.hasUpdates());
    }

    @Test
    void scrapeStats_add_shouldMergeStats() {
        var a = new ScrapeStats();
        a.newThreads = 2;
        a.newUpvotes = 100;
        a.newComments = 50;
        a.scannedIds.add("t3_1");

        var b = new ScrapeStats();
        b.newThreads = 1;
        b.newUpvotes = 50;
        b.newComments = 25;
        b.scannedIds.add("t3_2");

        a.add(b);

        assertEquals(3, a.newThreads);
        assertEquals(150, a.newUpvotes);
        assertEquals(75, a.newComments);
        assertTrue(a.scannedIds.contains("t3_1"));
        assertTrue(a.scannedIds.contains("t3_2"));
    }

    @Test
    void scrapeStats_toString_shouldBeReadable() {
        var stats = new ScrapeStats();
        stats.newThreads = 5;
        stats.newUpvotes = 200;
        stats.newComments = 30;

        String str = stats.toString();
        assertTrue(str.contains("5"));
        assertTrue(str.contains("200"));
        assertTrue(str.contains("30"));
    }

    // -- ThreadAnalysisContext --

    @Test
    void threadAnalysisContext_shouldBeEmptyByDefault() {
        var ctx = new ThreadAnalysisContext();
        assertTrue(ctx.isEmpty());
    }

    @Test
    void threadAnalysisContext_shouldNotBeEmptyWithTitle() {
        var ctx = new ThreadAnalysisContext();
        ctx.title = "GME to the moon";
        assertFalse(ctx.isEmpty());
    }

    @Test
    void threadAnalysisContext_shouldNotBeEmptyWithComments() {
        var ctx = new ThreadAnalysisContext();
        ctx.comments.add("Some comment");
        assertFalse(ctx.isEmpty());
    }

    // -- fetchThreadContext edge case --

    @Test
    void fetchThreadContext_shouldReturnEmptyContextForNullPermalink() {
        var ctx = scraper.fetchThreadContext(null);
        assertNotNull(ctx);
        assertTrue(ctx.isEmpty());
    }

    @Test
    void fetchThreadContext_shouldReturnEmptyContextForEmptyPermalink() {
        var ctx = scraper.fetchThreadContext("");
        assertNotNull(ctx);
        assertTrue(ctx.isEmpty());
    }

    // -- updateThreadsBatch edge case --

    @Test
    void updateThreadsBatch_shouldReturnEmptyStatsForNullList() {
        var stats = scraper.updateThreadsBatch(null);
        assertFalse(stats.hasUpdates());
    }

    @Test
    void updateThreadsBatch_shouldReturnEmptyStatsForEmptyList() {
        var stats = scraper.updateThreadsBatch(List.of());
        assertFalse(stats.hasUpdates());
    }

    // -- resolveImageUrls --

    @Test
    void resolveImageUrls_shouldResolveInlineBodyImages() throws Exception {
        // Image embedded mid-text via the rich-text editor: media_metadata
        // present, is_gallery false, selftext references the media_id.
        String json = """
            {
              "is_gallery": false,
              "selftext": "Look at this ![img](abc1 \\"chart\\") and more text",
              "media_metadata": {
                "abc1": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/abc1.png?width=640&amp;auto=webp"}}
              }
            }
            """;
        assertEquals(
                List.of("https://preview.redd.it/abc1.png?width=640&auto=webp"),
                invokeResolveImageUrls(json));
    }

    @Test
    void resolveImageUrls_shouldResolveInlineImagesInTextOrderDeduped() throws Exception {
        String json = """
            {
              "selftext": "![img](a) middle ![gif](b) end ![img](a)",
              "media_metadata": {
                "a": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/a.jpg"}},
                "b": {"status":"valid","e":"AnimatedImage","s":{"u":"https://preview.redd.it/b.gif"}}
              }
            }
            """;
        assertEquals(
                List.of("https://preview.redd.it/a.jpg", "https://preview.redd.it/b.gif"),
                invokeResolveImageUrls(json));
    }

    @Test
    void resolveImageUrls_shouldInheritCrosspostParentGallery() throws Exception {
        // The wrapper carries no media; the original post in
        // crosspost_parent_list holds the gallery.
        String json = """
            {
              "selftext": "",
              "url": "https://www.reddit.com/r/other/comments/x/",
              "crosspost_parent": "t3_x",
              "crosspost_parent_list": [{
                "is_gallery": true,
                "gallery_data": {"items":[{"media_id":"g1"},{"media_id":"g2"}]},
                "media_metadata": {
                  "g1": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/g1.png"}},
                  "g2": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/g2.png"}}
                }
              }]
            }
            """;
        assertEquals(
                List.of("https://preview.redd.it/g1.png", "https://preview.redd.it/g2.png"),
                invokeResolveImageUrls(json));
    }

    @Test
    void resolveImageUrls_shouldInheritCrosspostParentInlineBody() throws Exception {
        String json = """
            {
              "selftext": "",
              "crosspost_parent_list": [{
                "selftext": "see ![img](p1)",
                "media_metadata": {
                  "p1": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/p1.png"}}
                }
              }]
            }
            """;
        assertEquals(
                List.of("https://preview.redd.it/p1.png"),
                invokeResolveImageUrls(json));
    }

    @Test
    void resolveImageUrls_shouldSkipInvalidAndNonImageInlineEntries() throws Exception {
        String json = """
            {
              "selftext": "![v](vid) ![ok](img) ![gone](missing)",
              "media_metadata": {
                "vid": {"status":"valid","e":"RedditVideo","s":{"u":"https://v.redd.it/vid"}},
                "img": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/img.png"}}
              }
            }
            """;
        assertEquals(
                List.of("https://preview.redd.it/img.png"),
                invokeResolveImageUrls(json));
    }

    @Test
    void resolveImageUrls_shouldStillResolvePlainGallery() throws Exception {
        String json = """
            {
              "is_gallery": true,
              "gallery_data": {"items":[{"media_id":"m1"}]},
              "media_metadata": {
                "m1": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/m1.png"}}
              }
            }
            """;
        assertEquals(List.of("https://preview.redd.it/m1.png"), invokeResolveImageUrls(json));
    }

    @Test
    void resolveImageUrls_shouldReturnEmptyForPlainTextPost() throws Exception {
        String json = """
            { "is_gallery": false, "selftext": "just words, no images", "url": "https://www.reddit.com/r/x/comments/y/" }
            """;
        assertTrue(invokeResolveImageUrls(json).isEmpty());
    }

    // -- crosspost body-text fallback --

    @Test
    void parseThread_shouldUseCrosspostParentSelftext() throws Exception {
        // The wrapper's own selftext is empty; the body lives on the parent.
        String json = """
            {
              "title": "Micron DD",
              "selftext": "",
              "url": "https://www.reddit.com/r/stocks/comments/x/",
              "crosspost_parent_list": [{ "selftext": "Original DD body about MU financials" }]
            }
            """;
        RedditThread thread = invokeParseThread(json, "t3_abc", "wallstreetbetsGER");
        assertEquals("Original DD body about MU financials", thread.textContent());
    }

    @Test
    void parseThread_shouldFallBackToLinkWhenCrosspostParentTextEmpty() throws Exception {
        // Crosspost of a pure image/link post: parent has no selftext either,
        // so the link fallback still applies.
        String json = """
            {
              "title": "x",
              "selftext": "",
              "url_overridden_by_dest": "https://i.redd.it/pic.png",
              "crosspost_parent_list": [{ "selftext": "" }]
            }
            """;
        RedditThread thread = invokeParseThread(json, "t3_abc", "x");
        assertEquals("[Link: https://i.redd.it/pic.png]", thread.textContent());
    }

    @Test
    void parseThreadData_shouldUseCrosspostParentSelftext() throws Exception {
        String json = """
            { "data": { "children": [ { "data": {
              "name": "t3_abc",
              "title": "Micron DD",
              "selftext": "",
              "crosspost_parent_list": [{ "selftext": "Original DD body" }]
            } } ] } }
            """;
        ThreadAnalysisContext ctx = invokeParseThreadData(json);
        assertEquals("Original DD body", ctx.selftext);
    }

    // -- extractImages (comment inline media) --

    @Test
    void extractImages_shouldResolveCommentInlineMedia() throws Exception {
        String body = "this is fire ![img](c1) buy now";
        String json = """
            { "media_metadata": {
                "c1": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/c1.png"}}
            }}
            """;
        var result = invokeExtractImages(body, json);
        assertEquals(List.of("https://preview.redd.it/c1.png"), result.images());
        assertEquals("this is fire [Image] buy now", result.text());
    }

    @Test
    void extractImages_shouldHandleBothRawUrlAndInlineMediaInComment() throws Exception {
        String body = "chart https://i.redd.it/raw.png and inline ![img](c2)";
        String json = """
            { "media_metadata": {
                "c2": {"status":"valid","e":"AnimatedImage","s":{"u":"https://preview.redd.it/c2.gif"}}
            }}
            """;
        var result = invokeExtractImages(body, json);
        assertEquals(
                List.of("https://i.redd.it/raw.png", "https://preview.redd.it/c2.gif"),
                result.images());
        assertTrue(result.text().contains("[Image]"));
    }

    @Test
    void extractImages_shouldLeaveUnresolvableInlineRefUntouched() throws Exception {
        String body = "see ![img](missing) here";
        String json = "{ \"media_metadata\": { \"other\": {\"status\":\"valid\",\"e\":\"Image\",\"s\":{\"u\":\"https://x/o.png\"}} } }";
        var result = invokeExtractImages(body, json);
        assertTrue(result.images().isEmpty());
        assertEquals("see ![img](missing) here", result.text());
    }

    @Test
    void extractImages_shouldNoOpWithoutMediaMetadata() throws Exception {
        String body = "plain comment, no media";
        var result = invokeExtractImages(body, "{}");
        assertTrue(result.images().isEmpty());
        assertEquals("plain comment, no media", result.text());
    }

    // -- Reflection helpers --

    private RedditThread invokeParseThread(String json, String id, String subredditDefault)
            throws Exception {
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        Method m = RedditScraper.class.getDeclaredMethod("parseThread",
                com.fasterxml.jackson.databind.JsonNode.class, String.class, String.class);
        m.setAccessible(true);
        return (RedditThread) m.invoke(scraper, data, id, subredditDefault);
    }

    private ThreadAnalysisContext invokeParseThreadData(String json) throws Exception {
        var listing = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        var ctx = new ThreadAnalysisContext();
        Method m = RedditScraper.class.getDeclaredMethod("parseThreadData",
                com.fasterxml.jackson.databind.JsonNode.class, ThreadAnalysisContext.class);
        m.setAccessible(true);
        m.invoke(scraper, listing, ctx);
        return ctx;
    }

    private RedditScraper.ImageExtractionResult invokeExtractImages(String body, String json)
            throws Exception {
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        var ctx = new ThreadAnalysisContext();
        Method m = RedditScraper.class.getDeclaredMethod("extractImages",
                String.class, com.fasterxml.jackson.databind.JsonNode.class,
                ThreadAnalysisContext.class);
        m.setAccessible(true);
        return (RedditScraper.ImageExtractionResult) m.invoke(scraper, body, data, ctx);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeResolveImageUrls(String json) throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        Method m = RedditScraper.class.getDeclaredMethod("resolveImageUrls",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(scraper, node);
    }

    private String invokeNormalize(String permalink) throws Exception {
        Method m = RedditScraper.class.getDeclaredMethod("normalizePermalink", String.class);
        m.setAccessible(true);
        return (String) m.invoke(scraper, permalink);
    }

    private boolean invokeIsImageUrl(String url) throws Exception {
        Method m = RedditScraper.class.getDeclaredMethod("isImageUrl", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(scraper, url);
    }

    private boolean invokeIsRealAuthor(String author) throws Exception {
        Method m = RedditScraper.class.getDeclaredMethod("isRealAuthor", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(scraper, author);
    }

    private String invokeUnescapeHtml(String url) throws Exception {
        Method m = RedditScraper.class.getDeclaredMethod("unescapeHtml", String.class);
        m.setAccessible(true);
        return (String) m.invoke(scraper, url);
    }

    private String invokeStripPunctuation(String url) throws Exception {
        Method m = RedditScraper.class.getDeclaredMethod("stripTrailingPunctuation", String.class);
        m.setAccessible(true);
        return (String) m.invoke(scraper, url);
    }
}
