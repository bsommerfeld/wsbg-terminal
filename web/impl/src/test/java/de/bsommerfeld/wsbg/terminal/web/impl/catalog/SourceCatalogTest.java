package de.bsommerfeld.wsbg.terminal.web.impl.catalog;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCatalogTest {

    private static List<CatalogRow> parse(String csv) throws Exception {
        return SourceCatalog.parse(new BufferedReader(new StringReader(csv)));
    }

    @Test
    void shippedCatalogLoadsWhole() {
        List<CatalogRow> rows = SourceCatalog.load();
        assertFalse(rows.isEmpty());
        CatalogRow bloomberg = rows.stream()
                .filter(r -> r.name().equals("bloomberg")).findFirst().orElseThrow();
        assertEquals("Bloomberg", bloomberg.publisher());
        assertEquals(List.of(FetchUtil.DIRECT, FetchUtil.BROWSER), bloomberg.modes());
        assertEquals(5, bloomberg.urls().size());
        assertEquals("US", bloomberg.origin().sphere());
        assertFalse(bloomberg.social());
    }

    @Test
    void parsesARowWithDefaults() throws Exception {
        List<CatalogRow> rows = parse(
                "# comment\n\n"
                + "board;Das Board;de;DE;DIRECT;5;10;true;;https://a.example/rss|https://b.example/rss\n");
        assertEquals(1, rows.size());
        CatalogRow r = rows.get(0);
        assertTrue(r.social());
        assertEquals(2, r.urls().size());
        assertEquals(5, r.interval().minMinutes());
        assertTrue(r.accept().contains("rss"), "empty accept falls back to the feed default");
    }

    @Test
    void malformedRowsFailTheLoadLoudly() {
        assertThrows(IllegalStateException.class, () -> parse("only;four;fields;here\n"));
        assertThrows(IllegalStateException.class,
                () -> parse("x;X;en;US;WARP;5;10;false;;https://a.example/\n"));
        assertThrows(IllegalStateException.class,
                () -> parse("x;X;en;US;DIRECT;1;10;false;;https://a.example/\n")); // below floor
        assertThrows(IllegalStateException.class,
                () -> parse("x;X;en;US;DIRECT;5;10;false;;ftp://a.example/\n"));
        assertThrows(IllegalStateException.class, () -> parse(
                "x;X;en;US;DIRECT;5;10;false;;https://a.example/\n"
                + "x;X;en;US;DIRECT;5;10;false;;https://b.example/\n")); // duplicate name
    }
}
