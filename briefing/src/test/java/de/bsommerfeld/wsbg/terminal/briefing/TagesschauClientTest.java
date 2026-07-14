package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tagesschau api2u parser, network-free — item shape pinned by live probe
 * 2026-07-13: {@code news[]} with title/topline/firstSentence, ISO date WITH
 * offset, nullable ressort, breakingNews flag, and {@code type} that must be
 * "story" (other feeds carry "video" items).
 */
class TagesschauClientTest {

    private static final String FIXTURE = """
            {"news":[
              {"title":"Trump kündigt neue Seeblockade gegen Iran an",
               "topline":"Krieg im Nahen Osten",
               "firstSentence":"Nach neuen gegenseitigen Angriffen kündigt US-Präsident Trump eine neue Seeblockade an.",
               "date":"2026-07-13T20:56:13.464+02:00",
               "ressort":"ausland","breakingNews":false,"type":"story"},
              {"title":"DAX startet robust in die neue Woche",
               "topline":"Plus trotz Iran-Eskalation",
               "firstSentence":"Der DAX hält sich.",
               "date":"2026-07-13T18:51:00.000+02:00",
               "ressort":"wirtschaft","breakingNews":true,"type":"story"},
              {"title":"Ein Video","date":"2026-07-13T12:00:00.000+02:00","type":"video"},
              {"title":"Ohne Ressort","date":"2026-07-13T10:00:00.000+02:00","type":"story"}
            ],"type":"homepage"}""";

    @Test
    void parsesStoriesWithAllFields() {
        List<TagesschauClient.Article> articles = TagesschauClient.parse(FIXTURE);
        assertEquals(3, articles.size(), "video items must be filtered");

        TagesschauClient.Article first = articles.get(0);
        assertEquals("Trump kündigt neue Seeblockade gegen Iran an", first.title());
        assertEquals("Krieg im Nahen Osten", first.topline());
        assertEquals("ausland", first.ressort());
        assertEquals(Instant.parse("2026-07-13T18:56:13.464Z"), first.publishedAt());

        assertTrue(articles.get(1).breaking());
        assertNull(articles.get(2).topline(), "absent topline reads null, never empty");
        assertNull(articles.get(2).ressort());
    }

    @Test
    void garbageAndEmptyInputsParseToEmpty() {
        assertTrue(TagesschauClient.parse(null).isEmpty());
        assertTrue(TagesschauClient.parse("").isEmpty());
        assertTrue(TagesschauClient.parse("<html>wall</html>").isEmpty());
        assertTrue(TagesschauClient.parse("{\"news\":[]}").isEmpty());
    }
}
