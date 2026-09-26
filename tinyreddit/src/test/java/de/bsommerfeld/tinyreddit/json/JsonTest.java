package de.bsommerfeld.tinyreddit.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void readsNestedStructures() {
        JsonNode root = Json.parse("""
                {"data": {"children": [{"kind": "t3", "data": {"score": 42, "ratio": 0.93, "self": true}}]},
                 "empty": [], "none": null}
                """);
        JsonNode post = root.path("data").path("children").at(0).path("data");
        assertEquals(42, post.path("score").asInt(0));
        assertEquals(0.93, post.path("ratio").asDouble(0));
        assertTrue(post.path("self").asBoolean(false));
        assertTrue(root.path("empty").isArray());
        assertEquals(0, root.path("empty").size());
        assertTrue(root.path("none").isNull());
        assertTrue(root.has("none"));
        assertFalse(root.path("none").isPresent());
    }

    @Test
    void navigationOnMissingNeverThrows() {
        JsonNode root = Json.parse("{\"a\": 1}");
        JsonNode deep = root.path("x").path("y").at(3).path("z");
        assertTrue(deep.isMissing());
        assertEquals("fallback", deep.asText("fallback"));
        assertEquals(7, deep.asInt(7));
        assertEquals(0, root.path("a").elements().size());
    }

    @Test
    void decodesEscapesAndSurrogatePairs() {
        JsonNode text = Json.parse("\"Kurs \\u00fcber 100 \\ud83d\\ude80\\n\\\"ok\\\" \\/ \\\\\"");
        assertEquals("Kurs über 100 🚀\n\"ok\" / \\", text.asText(null));
    }

    @Test
    void keepsNumbersExact() {
        JsonNode number = Json.parse("1782345600.0");
        assertEquals(1_782_345_600L, number.asLong(0));
        assertEquals(-12.5e2, Json.parse("-12.5e2").asDouble(0));
        assertEquals(1234, Json.parse("\"1234\"").asInt(0), "quoted numbers read as numbers");
    }

    @Test
    void keepsMemberOrder() {
        JsonNode root = Json.parse("{\"b\": 1, \"a\": 2, \"c\": 3}");
        assertEquals("[b, a, c]", root.members().keySet().toString());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(JsonException.class, () -> Json.parse("[1, 2"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": 1} trailing"));
        assertThrows(JsonException.class, () -> Json.parse("\"line\nbreak\""));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("<html>blocked</html>"));
    }

    @Test
    void refusesAbsurdNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        assertThrows(JsonException.class, () -> Json.parse(deep));
        String fine = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        assertTrue(Json.parse(fine).isArray());
    }
}
