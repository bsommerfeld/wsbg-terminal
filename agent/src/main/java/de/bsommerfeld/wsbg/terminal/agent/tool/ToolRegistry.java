package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the set of tools the editorial agent can call.
 *
 * <p>
 * Alias-resolution is the single most important defence against small models:
 * they will often emit {@code get_cluster} when the tool is registered
 * as {@code getCluster}, or vice versa, or swap dashes for underscores. The
 * registry normalises every lookup to lowercase + stripped separators so all
 * three spellings resolve to the same {@link Tool}.
 */
@Deprecated // legacy agent tool-loop — replaced by the deterministic EditorialAgent pipeline; no longer wired
public final class ToolRegistry {

    private final Map<String, Tool> byCanonicalName = new LinkedHashMap<>();
    private final Map<String, Tool> byNormalised = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        byCanonicalName.put(tool.name(), tool);
        byNormalised.put(normalise(tool.name()), tool);
        return this;
    }

    public boolean has(String name) {
        return resolve(name) != null;
    }

    /**
     * Resolves the tool for the given name (with alias matching) and executes
     * it. Returns the tool's result, or an {@code "Error: unknown tool ..."}
     * string when the lookup fails. Never throws.
     */
    public String execute(String name, JsonNode args, ToolContext ctx) {
        Tool t = resolve(name);
        if (t == null) {
            return "Error: unknown tool '" + name + "'. Available tools: "
                    + String.join(", ", byCanonicalName.keySet());
        }
        try {
            String result = t.execute(args, ctx);
            return result == null ? "" : result;
        } catch (Exception e) {
            return "Error executing " + t.name() + ": " + e.getMessage();
        }
    }

    public List<ToolSpecification> specifications() {
        List<ToolSpecification> out = new ArrayList<>(byCanonicalName.size());
        for (Tool t : byCanonicalName.values())
            out.add(t.specification());
        return out;
    }

    public List<String> names() {
        return new ArrayList<>(byCanonicalName.keySet());
    }

    Tool resolve(String name) {
        if (name == null || name.isEmpty())
            return null;
        Tool direct = byCanonicalName.get(name);
        if (direct != null)
            return direct;
        return byNormalised.get(normalise(name));
    }

    private static String normalise(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == ' ')
                continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
