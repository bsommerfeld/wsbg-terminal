package de.bsommerfeld.tinyrss.markup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlTextTest {

    @Test
    void textOnOneLine() {
        assertEquals("Erster Absatz. Zweiter & letzter.",
                HtmlText.text("<p>Erster&nbsp;Absatz.</p><p>Zweiter &amp; <b>letzter</b>.</p>"));
        assertEquals("Titel Text", HtmlText.text("<h2>Titel</h2>Text<script>track()</script><style>p{}</style>"));
        assertEquals("a < b", HtmlText.text("a < b"));
        assertEquals("", HtmlText.text(null));
    }

    @Test
    void imagesWithoutPixelsResolved() {
        String html = """
                <img src="/bilder/a.jpg" alt="A"><img src="https://feeds.feedburner.com/~r/x/~4/y" width="1" height="1">
                <img src="data:image/gif;base64,R0lGOD" data-src="//cdn.example.com/b.webp">
                <img srcset="https://cdn.example.com/c-640.jpg 640w, https://cdn.example.com/c-1280.jpg 1280w">
                <img src="/bilder/a.jpg">""";
        assertEquals(List.of("https://example.com/bilder/a.jpg", "https://cdn.example.com/b.webp",
                        "https://cdn.example.com/c-640.jpg"),
                HtmlText.images(html, "https://example.com/feed/"));
    }
}
