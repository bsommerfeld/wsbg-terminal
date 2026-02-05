package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.service.UserMessage;

/**
 * AI Service for extracting financial instrument mentions from text.
 * Uses LangChain4j structured outputs (JSON Schema) to guarantee
 * a well-typed {@link TickerExtractionResult} response.
 */
interface TickerExtractor {
    @UserMessage("{{it}}")
    TickerExtractionResult extract(String prompt);
}
