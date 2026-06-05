package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FnFeedTest {

    @Test
    void modelsAllOneHundredThirtyFourFeeds() {
        assertEquals(134, FnFeed.values().length);
    }

    @Test
    void everySlugIsRssPrefixedAndUnique() {
        Set<String> slugs = new HashSet<>();
        for (FnFeed feed : FnFeed.values()) {
            assertTrue(feed.slug().startsWith("rss-"), feed + " slug must start with rss-");
            assertTrue(slugs.add(feed.slug()), "duplicate slug: " + feed.slug());
        }
    }

    @Test
    void urlIsBaseplusSlugWithTrailingSlash() {
        assertEquals("https://www.finanznachrichten.de/rss-dax-40-nachrichten-1/",
                FnFeed.DAX_40_NACHRICHTEN_1.url());
    }

    @Test
    void labelDropsRssPrefixAndTrailingId() {
        assertEquals("Dax 40 Nachrichten", FnFeed.DAX_40_NACHRICHTEN_1.label());
        assertEquals("News", FnFeed.NEWS.label());
        assertFalse(FnFeed.AKTIEN_NACHRICHTEN.label().startsWith("rss-"));
        for (FnFeed feed : FnFeed.values()) {
            assertFalse(feed.label().isBlank(), feed + " must have a non-blank label");
        }
    }

    @Test
    void categoriesAreAssignedAsExpected() {
        assertEquals(FnCategory.NEWS, FnFeed.AKTIEN_NACHRICHTEN.category());
        assertEquals(FnCategory.INDEX, FnFeed.DAX_40_NACHRICHTEN_1.category());
        assertEquals(FnCategory.BRANCHE, FnFeed.BRANCHE_PHARMA_40.category());
        assertEquals(FnCategory.EMPFEHLUNG, FnFeed.AKTIEN_EMPFEHLUNGEN_KAUFEN.category());
        assertEquals(FnCategory.ANALYSE, FnFeed.CHARTANALYSEN.category());
    }

    @Test
    void ofCategoryReturnsOnlyThatBucket() {
        var branche = FnFeed.of(FnCategory.BRANCHE);
        assertFalse(branche.isEmpty());
        assertTrue(branche.stream().allMatch(f -> f.category() == FnCategory.BRANCHE));
        assertTrue(branche.contains(FnFeed.BRANCHE_PHARMA_40));

        // every feed is reachable through exactly one category bucket
        int sum = Arrays.stream(FnCategory.values()).mapToInt(c -> FnFeed.of(c).size()).sum();
        assertEquals(FnFeed.values().length, sum);
    }

    @Test
    void bySlugRoundTrips() {
        assertEquals(FnFeed.NEWS, FnFeed.bySlug("rss-news").orElseThrow());
        assertTrue(FnFeed.bySlug("rss-does-not-exist").isEmpty());
    }
}
