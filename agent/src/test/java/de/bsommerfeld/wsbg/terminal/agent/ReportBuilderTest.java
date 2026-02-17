package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportBuilderTest {

    private RedditRepository repository;
    private AgentBrain brain;
    private ReportBuilder builder;

    @BeforeEach
    void setUp() {
        repository = mock(RedditRepository.class);
        brain = mock(AgentBrain.class);
        builder = new ReportBuilder(repository, brain);
    }

    @Test
    void buildReportData_shouldContainClusterMetadata() {
        var cluster = createCluster("t3_1", "GME Surges");

        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "GME Surges"));
        when(repository.getCommentsForThread("t3_1", 15)).thenReturn(List.of());

        String report = builder.buildReportData(cluster);

        assertTrue(report.contains("CASE ID:"));
        assertTrue(report.contains("GME Surges"));
        assertTrue(report.contains("Active Threads:"));
    }

    @Test
    void buildReportData_shouldIncludeThreadTitle() {
        var cluster = createCluster("t3_1", "AMC Squeeze");
        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "AMC Squeeze"));
        when(repository.getCommentsForThread("t3_1", 15)).thenReturn(List.of());

        String report = builder.buildReportData(cluster);

        assertTrue(report.contains("THREAD SOURCE"));
        assertTrue(report.contains("Title: AMC Squeeze"));
    }

    @Test
    void buildReportData_shouldIncludeComments() {
        var cluster = createCluster("t3_1", "Title");
        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "Title"));

        long now = System.currentTimeMillis() / 1000;
        var comment = new RedditComment("t1_1", "t3_1", "t3_1", "trader", "diamond hands", 42, now, now, now);
        when(repository.getCommentsForThread("t3_1", 15)).thenReturn(List.of(comment));

        String report = builder.buildReportData(cluster);

        assertTrue(report.contains("trader"));
        assertTrue(report.contains("diamond hands"));
        assertTrue(report.contains("Score: 42"));
    }

    @Test
    void buildReportData_shouldHandleNullThread() {
        var cluster = createCluster("t3_missing", "Missing");
        when(repository.getThread("t3_missing")).thenReturn(null);

        String report = builder.buildReportData(cluster);

        assertNotNull(report);
        assertTrue(report.contains("CASE ID:"));
    }

    @Test
    void buildCombinedContext_shouldMergeExistingWithNew() {
        var cluster = createCluster("t3_1", "Title");
        cluster.cachedContext = "Previous context data";

        String combined = builder.buildCombinedContext(cluster, "New report data");

        assertTrue(combined.contains("Previous context data"));
        assertTrue(combined.contains("New report data"));
        assertTrue(combined.contains("UPDATE"));
    }

    @Test
    void buildCombinedContext_shouldTrimOldContextBeyond4000Chars() {
        var cluster = createCluster("t3_1", "Title");
        cluster.cachedContext = "X".repeat(5000);

        String combined = builder.buildCombinedContext(cluster, "new");

        // Old context should be trimmed to last 4000 chars
        int oldPartLength = combined.indexOf("\n\n=== UPDATE");
        assertTrue(oldPartLength <= 4000);
    }

    @Test
    void buildCombinedContext_shouldHandleNullCachedContext() {
        var cluster = createCluster("t3_1", "Title");
        cluster.cachedContext = null;

        String combined = builder.buildCombinedContext(cluster, "report");

        assertNotNull(combined);
        assertTrue(combined.contains("report"));
    }

    @Test
    void buildHeadlinePrompt_shouldIncludeTopicFilterAndJargon() {
        String prompt = builder.buildHeadlinePrompt("history", "context", false, List.of("Gold", "Silber"));

        assertTrue(prompt.contains("Gold"));
        assertTrue(prompt.contains("Silber"));
        assertTrue(prompt.contains("Eselmetalle"));
    }

    @Test
    void buildHeadlinePrompt_shouldSkipTopicFilterWhenShowAll() {
        String prompt = builder.buildHeadlinePrompt("history", "context", true, List.of("GME"));

        assertTrue(prompt.contains("No topic restriction"));
    }

    @Test
    void buildHeadlinePrompt_shouldSkipTopicFilterWhenTopicsEmpty() {
        String prompt = builder.buildHeadlinePrompt("history", "context", false, List.of());

        assertTrue(prompt.contains("No topic restriction"));
    }

    @Test
    void isAccepted_shouldAcceptExplicitVerdict() {
        assertTrue(builder.isAccepted("VERDICT: ACCEPT\nREPORT: [HIGH] Gold rises 5%"));
    }

    @Test
    void isAccepted_shouldRejectMissingVerdict() {
        assertFalse(builder.isAccepted("VERDICT: REJECT"));
    }

    @Test
    void isAccepted_shouldRejectNoVerdict() {
        assertFalse(builder.isAccepted("REPORT: [LOW] Some headline"));
    }

    @Test
    void extractHeadline_shouldReturnHeadlineText() {
        String response = "Some preamble\nREPORT: [HIGH] GME surges 200% on retail momentum\nExtra text";

        String headline = builder.extractHeadline(response);

        assertFalse(headline.isEmpty());
        assertTrue(headline.contains("GME"));
    }

    @Test
    void extractHeadline_shouldReturnEmptyForMinus1() {
        String response = "REPORT: -1";

        String headline = builder.extractHeadline(response);
        assertEquals("", headline);
    }

    @Test
    void extractHeadline_shouldReturnEmptyWhenNoReportLine() {
        String response = "This is just some random text\nwithout a REPORT line";

        String headline = builder.extractHeadline(response);
        assertEquals("", headline);
    }

    @Test
    void extractHeadline_shouldReturnEmptyForEmptyReport() {
        String response = "REPORT: ";

        String headline = builder.extractHeadline(response);
        assertEquals("", headline);
    }

    private InvestigationCluster createCluster(String threadId, String title) {
        long now = System.currentTimeMillis() / 1000;
        var t = new RedditThread(threadId, "wsb", title, "author", "text",
                now, "/p", 100, 0.8, 5, now, null);
        return new InvestigationCluster(t, dummyEmbedding());
    }

    private RedditThread thread(String id, String title) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditThread(id, "wsb", title, "author", "content",
                now, "/r/wsb/" + id, 50, 0.9, 10, now, null);
    }

    private Embedding dummyEmbedding() {
        return Embedding.from(new float[768]);
    }
}
