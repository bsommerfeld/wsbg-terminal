package de.bsommerfeld.wsbg.terminal.instruments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The locally LEARNED German leg: the wallstreet-online name→ISIN memory
 * ({@code <app-data>/wso-isin.jsonl}, append-only, written by the price chain's
 * ISIN anchoring). Every instrument the room ever surfaced and WSO pinned is in
 * here — exactly the German small caps the SEC feed can't know. Lines look like
 * {@code {"q":"nvidia","isin":"US67066G1040","wkn":"918422","name":"NVIDIA Corp."}};
 * {@code ticker:SYM} alias keys carry the symbol.
 */
public final class WsoIsinSource implements CorpusSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    public WsoIsinSource(Path wsoIsinFile) {
        this.file = wsoIsinFile;
    }

    @Override
    public String name() {
        return "wso";
    }

    @Override
    public List<InstrumentEntry> fetch() throws Exception {
        if (file == null || !Files.exists(file)) return List.of();
        // One instrument per ISIN: the name entry establishes it, a ticker: alias
        // key contributes the symbol. Torn/garbled lines are skipped (same
        // discipline as the archive loader).
        Map<String, String[]> byIsin = new LinkedHashMap<>(); // isin -> [name, symbol]
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                JsonNode n = JSON.readTree(line);
                String isin = n.path("isin").asText("").trim();
                String name = n.path("name").asText("").trim();
                if (isin.isEmpty() || name.isEmpty()) continue;
                // The derivative gate the corpus was missing (2026-08-13): lines
                // written before the price chain's class filter existed anchor a
                // warrant/certificate under a company name, and this source then
                // typed them "EQUITY" — a Vontobel Optionsschein sat in the corpus
                // typegleich with Nvidia. Same name heuristic as the price chain's
                // own poison filter (WallstreetOnlineClient.looksLikeDerivative);
                // the two must stay in step.
                if (looksLikeDerivative(name)) continue;
                String[] slot = byIsin.computeIfAbsent(isin, k -> new String[]{name, null});
                String q = n.path("q").asText("");
                if (q.startsWith("ticker:")) slot[1] = q.substring("ticker:".length()).trim();
            } catch (Exception ignored) {
                // torn line — skip
            }
        }
        List<InstrumentEntry> out = new ArrayList<>(byIsin.size());
        for (Map.Entry<String, String[]> e : byIsin.entrySet()) {
            String isin = e.getKey();
            String name = e.getValue()[0];
            String symbol = e.getValue()[1] == null ? isin : e.getValue()[1];
            String country = isin.length() >= 2 ? isin.substring(0, 2) : "";
            out.add(new InstrumentEntry(symbol, name, isin, country, "EQUITY", "wso"));
        }
        return out;
    }

    /**
     * Derivative/certificate NAME shapes — the same heuristic the price chain's
     * WSO client uses as its poison filter ({@code WallstreetOnlineClient
     * .looksLikeDerivative} in {@code web-impl}; kept as a local twin because this
     * module cannot depend on {@code web-impl}). Format recognition, not a curated
     * instrument list.
     */
    static boolean looksLikeDerivative(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("mini future") || n.contains("faktor") || n.contains("knock")
                || n.contains("zertifikat") || n.contains("optionsschein") || n.contains("turbo")
                || n.contains("open end") || n.contains("open-end") || n.contains(" etp")
                || n.contains("hebel") || n.contains("discount") || n.contains("bonus")
                || n.contains("wave") || n.contains("endlos");
    }
}
