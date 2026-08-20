package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compose contract's REAL enforcement seam: the schema sent as {@code format}
 * is honoured by the GGUF runner only, so {@link ComposeReplyParser#schemaViolations}
 * plus the one corrective retry in {@code EditorialAgent.composeUnit} are what hold
 * the closed vocabulary on the MLX default path — and {@link ChatGateway#isClientReject}
 * is what keeps a future HTTP 400 (Ollama PR #17232 would reject {@code format} that
 * way) from ripping a lane apart.
 */
class ComposeSchemaGuardTest {

    // ------------------------------------------------------------ schemaViolations

    @Test
    void conformingDraftHasNoViolations() {
        var draft = new HeadlineWriter.Draft("Zeile.", "BULLISH", "IMPORTANT", "HARD_CATALYST");
        assertEquals(List.of(), ComposeReplyParser.schemaViolations(draft));
    }

    @Test
    void caseAndWhitespaceAreNormalisedBeforeJudging() {
        // Downstream normalises these anyway (HighlightReconciler, fromString) —
        // a retry for a case slip would burn a model call for nothing.
        var draft = new HeadlineWriter.Draft("Zeile.", " bullish ", "normal", "none");
        assertEquals(List.of(), ComposeReplyParser.schemaViolations(draft));
    }

    @Test
    void inventedEnumValuesAreNamedIndividually() {
        var draft = new HeadlineWriter.Draft("Zeile.", "EUPHORIC", "RED", "MOON_CATALYST");
        List<String> v = ComposeReplyParser.schemaViolations(draft);
        assertEquals(3, v.size());
        assertTrue(v.get(0).contains("MOON_CATALYST"), "trigger violation should quote the value");
        assertTrue(v.get(1).contains("RED"));
        assertTrue(v.get(2).contains("EUPHORIC"));
    }

    @Test
    void parseCarriesViolationsForUsableDraft() {
        var pc = ComposeReplyParser.parse(
                "{\"headline\":\"Zeile.\",\"trigger\":\"MAYBE\",\"highlight\":\"NORMAL\","
                        + "\"sentiment\":\"BULLISH\",\"derivedFrom\":[],\"newsUsed\":[]}", false);
        assertEquals(1, pc.schemaViolations().size());
        assertTrue(pc.schemaViolations().get(0).contains("MAYBE"));
    }

    @Test
    void unusableReplyCarriesNoViolations() {
        // No draft -> nothing to validate; the whiff/retry path owns that case.
        var pc = ComposeReplyParser.parse("keine Ahnung", false);
        assertEquals(List.of(), pc.schemaViolations());
    }

    // ------------------------------------------------------------ isClientReject

    @Test
    void http4xxIsAClientReject() {
        assertTrue(ChatGateway.isClientReject(
                new dev.langchain4j.exception.InvalidRequestException("format not supported")));
        assertTrue(ChatGateway.isClientReject(new RuntimeException(
                new dev.langchain4j.exception.HttpException(400, "bad request"))));
    }

    @Test
    void serverErrorsAndConnectFailuresAreNotClientRejects() {
        assertFalse(ChatGateway.isClientReject(
                new dev.langchain4j.exception.HttpException(500, "boom")));
        assertFalse(ChatGateway.isClientReject(
                new RuntimeException(new java.net.ConnectException("down"))));
    }

    // ------------------------------------------------------------------
    // What counts as a VERDICT, and what only as "not now"
    // ------------------------------------------------------------------

    @Test
    void aRateLimitIsNotAVerdict() {
        // The case that only appears on a hosted provider: HTTP 429 is 4xx, and
        // the old blanket rule turned every one into a permanent empty reply -
        // the compose lane going quiet under exactly the load it was busiest
        // at. langchain4j already calls it retriable; we follow that.
        assertFalse(ChatGateway.isClientReject(
                new dev.langchain4j.exception.RateLimitException("429 Too Many Requests")));
        assertFalse(ChatGateway.isClientReject(new RuntimeException(
                new dev.langchain4j.exception.RateLimitException("rate limited"))));
    }

    @Test
    void aServerErrorIsNotAVerdictEither() {
        assertFalse(ChatGateway.isClientReject(
                new dev.langchain4j.exception.InternalServerException("503")));
        assertFalse(ChatGateway.isClientReject(
                new dev.langchain4j.exception.TimeoutException("gateway timeout")));
    }

    @Test
    void aBadRequestStaysAVerdict() {
        // Retrying an identical malformed body just 400s again, forever.
        assertTrue(ChatGateway.isClientReject(
                new dev.langchain4j.exception.InvalidRequestException("bad schema")));
        assertTrue(ChatGateway.isClientReject(
                new dev.langchain4j.exception.AuthenticationException("401")));
    }
}
