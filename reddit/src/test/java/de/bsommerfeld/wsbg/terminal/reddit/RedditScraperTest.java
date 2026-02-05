package de.bsommerfeld.wsbg.terminal.reddit;

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
        scraper = new RedditScraper(repository);
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
        var stats = new RedditScraper.ScrapeStats();
        assertEquals(0, stats.newThreads);
        assertEquals(0, stats.newUpvotes);
        assertEquals(0, stats.newComments);
        assertFalse(stats.hasUpdates());
    }

    @Test
    void scrapeStats_hasUpdates_shouldDetectNewThreads() {
        var stats = new RedditScraper.ScrapeStats();
        stats.newThreads = 1;
        assertTrue(stats.hasUpdates());
    }

    @Test
    void scrapeStats_add_shouldMergeStats() {
        var a = new RedditScraper.ScrapeStats();
        a.newThreads = 2;
        a.newUpvotes = 100;
        a.newComments = 50;
        a.scannedIds.add("t3_1");

        var b = new RedditScraper.ScrapeStats();
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
        var stats = new RedditScraper.ScrapeStats();
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
        var ctx = new RedditScraper.ThreadAnalysisContext();
        assertTrue(ctx.isEmpty());
    }

    @Test
    void threadAnalysisContext_shouldNotBeEmptyWithTitle() {
        var ctx = new RedditScraper.ThreadAnalysisContext();
        ctx.title = "GME to the moon";
        assertFalse(ctx.isEmpty());
    }

    @Test
    void threadAnalysisContext_shouldNotBeEmptyWithComments() {
        var ctx = new RedditScraper.ThreadAnalysisContext();
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

    // -- Reflection helpers --

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
