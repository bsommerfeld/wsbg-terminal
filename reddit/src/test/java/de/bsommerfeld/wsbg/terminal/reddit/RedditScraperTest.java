package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the JSON path's utility methods, media/thread mapping, and inner
 * types. After the SRP decomposition these behaviours live in dedicated
 * collaborators ({@link RedditText}, {@link RedditMediaExtractor},
 * {@link RedditThreadMapper}); the orchestrator {@link RedditScraper} is exercised
 * only for its public edge cases (HTTP-dependent scans need live endpoints).
 */
class RedditScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RedditRepository repository;
    private RedditScraper scraper;
    private RedditMediaExtractor media;
    private RedditThreadMapper threadMapper;

    @BeforeEach
    void setUp() {
        repository = mock(RedditRepository.class);
        scraper = new RedditScraper(repository, new GlobalConfig());
        media = new RedditMediaExtractor();
        threadMapper = new RedditThreadMapper(repository, media);
    }

    // -- normalizePermalink --

    @Test
    void normalizePermalink_shouldStripTrailingSlash() {
        assertEquals("/r/wsb/comments/abc", RedditText.normalizePermalink("/r/wsb/comments/abc/"));
    }

    @Test
    void normalizePermalink_shouldAddLeadingSlash() {
        assertEquals("/r/wsb/comments/abc", RedditText.normalizePermalink("r/wsb/comments/abc"));
    }

    @Test
    void normalizePermalink_shouldHandleAlreadyNormalized() {
        assertEquals("/r/wsb/comments/abc", RedditText.normalizePermalink("/r/wsb/comments/abc"));
    }

    // -- isImageUrl --

    @Test
    void isImageUrl_shouldRecognizeJpg() {
        assertTrue(RedditText.isImageUrl("https://i.redd.it/photo.jpg"));
    }

    @Test
    void isImageUrl_shouldRecognizePng() {
        assertTrue(RedditText.isImageUrl("https://i.redd.it/chart.png"));
    }

    @Test
    void isImageUrl_shouldRecognizeWebp() {
        assertTrue(RedditText.isImageUrl("https://i.imgur.com/img.webp"));
    }

    @Test
    void isImageUrl_shouldRecognizeGif() {
        assertTrue(RedditText.isImageUrl("https://i.imgur.com/meme.gif"));
    }

    @Test
    void isImageUrl_shouldRecognizeUrlsWithQueryParams() {
        assertTrue(RedditText.isImageUrl("https://cdn.example.com/image.jpg?width=640"));
    }

    @Test
    void isImageUrl_shouldRejectNonImageUrl() {
        assertFalse(RedditText.isImageUrl("https://www.reddit.com/r/wsb"));
    }

    @Test
    void isImageUrl_shouldReturnFalseForNull() {
        assertFalse(RedditText.isImageUrl(null));
    }

    // -- isRealAuthor --

    @Test
    void isRealAuthor_shouldAcceptRealUsername() {
        assertTrue(RedditText.isRealAuthor("DeepFuckingValue"));
    }

    @Test
    void isRealAuthor_shouldRejectAnon() {
        assertFalse(RedditText.isRealAuthor("anon"));
    }

    @Test
    void isRealAuthor_shouldRejectDeleted() {
        assertFalse(RedditText.isRealAuthor("[deleted]"));
    }

    @Test
    void isRealAuthor_shouldRejectUnknown() {
        assertFalse(RedditText.isRealAuthor("unknown"));
    }

    // -- unescapeHtml --

    @Test
    void unescapeHtml_shouldDecodeAmpersand() {
        assertEquals("a&b", RedditText.unescapeHtml("a&amp;b"));
    }

    @Test
    void unescapeHtml_shouldDecodeMultipleEntities() {
        assertEquals("<tag attr=\"val\">", RedditText.unescapeHtml("&lt;tag attr=&quot;val&quot;&gt;"));
    }

    @Test
    void unescapeHtml_shouldReturnNullForNull() {
        assertNull(RedditText.unescapeHtml(null));
    }

    // -- stripTrailingPunctuation --

    @Test
    void stripTrailingPunctuation_shouldRemoveParenthesis() {
        assertEquals("https://example.com", RedditText.stripTrailingPunctuation("https://example.com)"));
    }

    @Test
    void stripTrailingPunctuation_shouldRemoveMultiplePunctuation() {
        assertEquals("https://example.com", RedditText.stripTrailingPunctuation("https://example.com)."));
    }

    @Test
    void stripTrailingPunctuation_shouldLeaveCleanUrlAlone() {
        assertEquals("https://example.com/path", RedditText.stripTrailingPunctuation("https://example.com/path"));
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
                media.resolveImageUrls(MAPPER.readTree(json)));
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
                media.resolveImageUrls(MAPPER.readTree(json)));
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
                media.resolveImageUrls(MAPPER.readTree(json)));
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
                media.resolveImageUrls(MAPPER.readTree(json)));
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
                media.resolveImageUrls(MAPPER.readTree(json)));
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
        assertEquals(List.of("https://preview.redd.it/m1.png"), media.resolveImageUrls(MAPPER.readTree(json)));
    }

    @Test
    void resolveImageUrls_shouldReturnEmptyForPlainTextPost() throws Exception {
        String json = """
            { "is_gallery": false, "selftext": "just words, no images", "url": "https://www.reddit.com/r/x/comments/y/" }
            """;
        assertTrue(media.resolveImageUrls(MAPPER.readTree(json)).isEmpty());
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
        RedditThread thread = threadMapper.parseThread(MAPPER.readTree(json), "t3_abc", "wallstreetbetsGER");
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
        RedditThread thread = threadMapper.parseThread(MAPPER.readTree(json), "t3_abc", "x");
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
        var ctx = new ThreadAnalysisContext();
        threadMapper.parseThreadData(MAPPER.readTree(json), ctx);
        assertEquals("Original DD body", ctx.selftext);
    }

    // -- extractCommentImages (comment inline media) --

    @Test
    void extractImages_shouldResolveCommentInlineMedia() throws Exception {
        String body = "this is fire ![img](c1) buy now";
        String json = """
            { "media_metadata": {
                "c1": {"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/c1.png"}}
            }}
            """;
        var result = extractCommentImages(body, json);
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
        var result = extractCommentImages(body, json);
        assertEquals(
                List.of("https://i.redd.it/raw.png", "https://preview.redd.it/c2.gif"),
                result.images());
        assertTrue(result.text().contains("[Image]"));
    }

    @Test
    void extractImages_shouldLeaveUnresolvableInlineRefUntouched() throws Exception {
        String body = "see ![img](missing) here";
        String json = "{ \"media_metadata\": { \"other\": {\"status\":\"valid\",\"e\":\"Image\",\"s\":{\"u\":\"https://x/o.png\"}} } }";
        var result = extractCommentImages(body, json);
        assertTrue(result.images().isEmpty());
        assertEquals("see ![img](missing) here", result.text());
    }

    @Test
    void extractImages_shouldNoOpWithoutMediaMetadata() throws Exception {
        String body = "plain comment, no media";
        var result = extractCommentImages(body, "{}");
        assertTrue(result.images().isEmpty());
        assertEquals("plain comment, no media", result.text());
    }

    private RedditMediaExtractor.ImageExtractionResult extractCommentImages(String body, String json)
            throws Exception {
        JsonNode data = MAPPER.readTree(json);
        return media.extractCommentImages(body, data, new ThreadAnalysisContext());
    }
}
