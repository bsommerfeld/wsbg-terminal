package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import de.bsommerfeld.wsbg.terminal.agent.tool.Tool;
import de.bsommerfeld.wsbg.terminal.agent.tool.ToolContext;
import de.bsommerfeld.wsbg.terminal.agent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private static Tool stubTool(String name, String reply) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public ToolSpecification specification() {
                return ToolSpecification.builder()
                        .name(name)
                        .description("test")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }
            @Override public String execute(JsonNode args, ToolContext ctx) {
                return reply;
            }
        };
    }

    private static JsonNode emptyArgs() {
        return JsonNodeFactory.instance.objectNode();
    }

    @Test
    void execute_dispatchesByCanonicalName() {
        ToolRegistry r = new ToolRegistry().register(stubTool("publishHeadline", "ok"));
        assertEquals("ok", r.execute("publishHeadline", emptyArgs(), null));
    }

    @Test
    void execute_resolvesSnakeCaseAlias() {
        ToolRegistry r = new ToolRegistry().register(stubTool("publishHeadline", "ok"));
        assertEquals("ok", r.execute("publish_headline", emptyArgs(), null));
    }

    @Test
    void execute_resolvesKebabCaseAlias() {
        ToolRegistry r = new ToolRegistry().register(stubTool("publishHeadline", "ok"));
        assertEquals("ok", r.execute("publish-headline", emptyArgs(), null));
    }

    @Test
    void execute_resolvesUppercaseSpelling() {
        ToolRegistry r = new ToolRegistry().register(stubTool("publishHeadline", "ok"));
        assertEquals("ok", r.execute("PUBLISH_HEADLINE", emptyArgs(), null));
    }

    @Test
    void execute_returnsErrorOnUnknownTool() {
        ToolRegistry r = new ToolRegistry().register(stubTool("publishHeadline", "ok"));
        String result = r.execute("doesNotExist", emptyArgs(), null);
        assertTrue(result.startsWith("Error: unknown tool"));
        assertTrue(result.contains("publishHeadline"));
    }

    @Test
    void execute_wrapsExceptionsInErrorString() {
        Tool throwing = new Tool() {
            @Override public String name() { return "boom"; }
            @Override public ToolSpecification specification() {
                return ToolSpecification.builder()
                        .name("boom").description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }
            @Override public String execute(JsonNode a, ToolContext c) {
                throw new RuntimeException("kaboom");
            }
        };
        ToolRegistry r = new ToolRegistry().register(throwing);
        String result = r.execute("boom", emptyArgs(), null);
        assertTrue(result.startsWith("Error executing boom"));
        assertTrue(result.contains("kaboom"));
    }

    @Test
    void has_returnsFalseForUnknownAndTrueForAlias() {
        ToolRegistry r = new ToolRegistry().register(stubTool("getCluster", "x"));
        assertFalse(r.has("unknownTool"));
        assertTrue(r.has("get_cluster"));
        assertTrue(r.has("GetCluster"));
    }

    @Test
    void specifications_listsAllToolsInRegistrationOrder() {
        ToolRegistry r = new ToolRegistry()
                .register(stubTool("a", ""))
                .register(stubTool("b", ""))
                .register(stubTool("c", ""));
        var specs = r.specifications();
        assertEquals(3, specs.size());
        assertEquals("a", specs.get(0).name());
        assertEquals("b", specs.get(1).name());
        assertEquals("c", specs.get(2).name());
    }
}
