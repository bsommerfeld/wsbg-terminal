package de.bsommerfeld.wsbg.terminal.data.market;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
// Note: WebSocket implementation to follow

@Singleton
public class MarketDataService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketDataService.class);
    private final ApplicationEventBus eventBus;
    private final HttpClient httpClient;
    private final GlobalConfig config; // Added

    @Inject
    public MarketDataService(ApplicationEventBus eventBus, GlobalConfig config) {
        this.eventBus = eventBus;
        this.config = config;
        // Attempt to load key
        if (config.getMarket() != null && config.getMarket().getApiKey() != null) {
            this.apiKey = config.getMarket().getApiKey();
        }
        this.httpClient = HttpClient.newHttpClient();
    }

    private static final String TWELVE_DATA_URL_TEMPLATE = "https://api.twelvedata.com/price?symbol=%s&apikey=%s";

    // Injected Config
    // private final GlobalConfig config; // This is in UI module, Data shouldn't
    // depend on UI.
    // Ideally Config should be in Core or a separate module.
    // For now, I will add a setApiKey method or inject a simple provider string.
    // Assuming we can pass it or use a system property/env var if not refactoring
    // Config to Core.
    // Let's assume we inject a "MarketDataProviderConfig" interface or similar if
    // we strictly follow architecture.
    // Given the constraints and the previous "GlobalConfig" being in UI, I will
    // rely on passing the key or a simpler injection.

    // User requested: "API Key is passed via the UI by the users (set in settings)"

    private String apiKey = "demo"; // Default

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public de.bsommerfeld.wsbg.terminal.core.domain.MarketTick getLatestQuote(String symbol) {
        String url = String.format(TWELVE_DATA_URL_TEMPLATE, symbol, apiKey);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            // Parse valid or error response
            String json = response.body();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            // Check for API-level error (TwelveData uses status="error" in body usually, or
            // HTTP codes)
            if (response.statusCode() != 200
                    || (root.has("status") && "error".equalsIgnoreCase(root.get("status").asText()))) {
                String errorMsg = root.has("message") ? root.get("message").asText() : "HTTP " + response.statusCode();
                // Optimize message for Agent
                if (errorMsg.contains("symbol_not_found")) {
                    errorMsg = "Symbol not found in market data (or not supported in Demo plan).";
                }
                throw new RuntimeException(errorMsg);
            }

            if (root.has("price")) {
                java.math.BigDecimal price = new java.math.BigDecimal(root.get("price").asText());
                // Create minimal Tick
                return new de.bsommerfeld.wsbg.terminal.core.domain.MarketTick(
                        symbol,
                        java.time.Instant.now(),
                        price,
                        0 // Volume not in simple price endpoint
                );
            } else {
                throw new RuntimeException("Invalid response format: " + json);
            }

        } catch (Exception e) {
            LOG.error("Error fetching quote for {}", symbol, e);
            throw new RuntimeException("Quote Fetch Error", e);
        }
    }
}
