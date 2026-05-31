package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsommerfeld.wsbg.terminal.agent.tool.PublishHeadlineTool;
import de.bsommerfeld.wsbg.terminal.agent.tool.ToolContext;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishHeadlineToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClusterRegistry registry;
    private AgentRepository agentRepository;
    private ToolContext ctx;
    private PublishHeadlineTool tool;

    @BeforeEach
    void setUp() {
        registry = new ClusterRegistry();
        agentRepository = new AgentRepository();
        ApplicationEventBus bus = new ApplicationEventBus();
        I18nService i18n = mock(I18nService.class);
        when(i18n.get(anyString())).thenReturn("EILMELDUNG");

        ctx = new ToolContext(registry, agentRepository, null, null, bus, i18n, null);
        tool = new PublishHeadlineTool();

        long now = System.currentTimeMillis() / 1000;
        RedditThread t = new RedditThread("t3_a", "wsb", "Title", "u", "b",
                now, "/p", 5, 0.9, 1, now, null);
        registry.add(new InvestigationCluster(t, Embedding.from(new float[] { 0.1f, 0.2f })));
    }

    private ObjectNode args(String clusterId, String headline) {
        ObjectNode o = JSON.createObjectNode();
        o.put("clusterId", clusterId);
        o.put("headline", headline);
        return o;
    }

    @Test
    void publish_writesToRepoAndMarksThisRunOnHappyPath() {
        String result = tool.execute(args("t3_a", "Micron rallye after squeeze"), ctx);
        assertTrue(result.startsWith("Published"), result);
        // Per-run dedup key is (clusterId, ticker) — no ticker means
        // the cluster's „no-ticker" slot is now occupied.
        assertTrue(ctx.publishedThisRun().contains("t3_a|_no_ticker"));
        assertEquals(1, agentRepository.getHeadlinesByClusterId("t3_a").size());
    }

    @Test
    void publish_allowsSecondHeadlineOnSameClusterWithDifferentTicker() {
        ctx.recordValidatedTicker("SNOW");
        ctx.recordValidatedTicker("MU");

        ObjectNode firstArgs = args("t3_a", "Snowflake gibt 30% wieder ab");
        firstArgs.put("tickerSymbol", "SNOW");
        String first = tool.execute(firstArgs, ctx);
        assertTrue(first.startsWith("Published"), first);

        ObjectNode secondArgs = args("t3_a", "Micron Gamma Squeeze treibt Calls");
        secondArgs.put("tickerSymbol", "MU");
        String second = tool.execute(secondArgs, ctx);
        assertTrue(second.startsWith("Published"), second);

        // Both headlines persisted on the same cluster.
        assertEquals(2, agentRepository.getHeadlinesByClusterId("t3_a").size());
        assertTrue(ctx.publishedThisRun().contains("t3_a|SNOW"));
        assertTrue(ctx.publishedThisRun().contains("t3_a|MU"));
    }

    @Test
    void publish_blocksSameTickerTwiceInSameRun() {
        ctx.recordValidatedTicker("SNOW");
        ObjectNode firstArgs = args("t3_a", "Snowflake first take");
        firstArgs.put("tickerSymbol", "SNOW");
        tool.execute(firstArgs, ctx);

        ObjectNode secondArgs = args("t3_a", "Snowflake another angle");
        secondArgs.put("tickerSymbol", "SNOW");
        String second = tool.execute(secondArgs, ctx);

        assertTrue(second.startsWith("Error"));
        assertTrue(second.contains("ticker SNOW"));
    }

    @Test
    void publish_rejectsEmptyHeadline() {
        String result = tool.execute(args("t3_a", "  "), ctx);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("headline"));
    }

    @Test
    void publish_rejectsUnknownCluster() {
        String result = tool.execute(args("t3_missing", "Anything"), ctx);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("t3_missing"));
    }

    @Test
    void publish_rejectsHeadlineOverWordCap() {
        String longHeadline = "one two three four five six seven eight nine ten "
                + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twentyone";
        String result = tool.execute(args("t3_a", longHeadline), ctx);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("21 words"));
    }

    @Test
    void publish_dedupesWithinSameRun() {
        tool.execute(args("t3_a", "First headline ok"), ctx);
        String second = tool.execute(args("t3_a", "Another go"), ctx);
        assertTrue(second.startsWith("Error"));
        assertTrue(second.contains("already published"));
    }

    @Test
    void publish_rejectsWhenRecentHeadlineExistsInRepo() {
        agentRepository.saveHeadline("t3_a", "Earlier headline", "");
        String result = tool.execute(args("t3_a", "Fresh attempt"), ctx);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("cooldown"));
    }

    @Test
    void publish_dropsTickerWhenNotValidatedByLookup() {
        // No ctx.recordValidatedTicker — the agent skipped lookupTicker.
        ObjectNode payload = args("t3_a", "Snowflake gibt 30% wieder ab");
        payload.put("tickerSymbol", "SNOW");

        String result = tool.execute(payload, ctx);

        assertTrue(result.startsWith("Published"), result);
        // Headline persists, but with the bogus ticker stripped.
        var records = agentRepository.getHeadlinesByClusterId("t3_a");
        assertEquals(1, records.size());
        assertNull(records.get(0).tickerSymbol());
    }

    @Test
    void publish_acceptsTickerWhenValidated() {
        ctx.recordValidatedTicker("SNOW");
        ObjectNode payload = args("t3_a", "Snowflake gibt 30% wieder ab");
        payload.put("tickerSymbol", "SNOW");

        tool.execute(payload, ctx);

        var records = agentRepository.getHeadlinesByClusterId("t3_a");
        assertEquals("SNOW", records.get(0).tickerSymbol());
    }
}
