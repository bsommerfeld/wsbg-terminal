package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live smoke test for {@link FnRssClient} against the real finanznachrichten.de
 * feeds. Tagged {@code integration} so it stays out of the default build. Run with:
 *
 * <pre>mvn test -pl finanznachrichten -Dtest=FnRssClientIT -Dtest.excludedGroups=</pre>
 *
 * One network call: fetches the general "Aktien-Nachrichten" feed and asserts it
 * parses into populated items with absolute links back to the article (the
 * "active link" the feed's terms of use require).
 */
@Tag("integration")
class FnRssClientIT {

    @Test
    void fetchesAndParsesLiveAktienNachrichtenFeed() {
        FnRssClient client = new FnRssClient();
        List<RawNewsItem> items = client.fetch(FnFeed.AKTIEN_NACHRICHTEN);

        assertFalse(items.isEmpty(), "live feed should yield at least one item");
        RawNewsItem first = items.getFirst();
        assertNotNull(first.title());
        assertFalse(first.title().isBlank());
        assertTrue(first.link().startsWith("https://www.finanznachrichten.de/"),
                "each item must carry an active link back to the article");
        assertEquals("finanznachrichten", first.publisher());
    }
}
