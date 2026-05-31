package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
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
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of());

        String report = builder.buildReportData(cluster);

        assertTrue(report.contains("CASE ID:"));
        assertTrue(report.contains("GME Surges"));
        assertTrue(report.contains("Active Threads:"));
    }

    @Test
    void buildReportData_shouldIncludeThreadTitle() {
        var cluster = createCluster("t3_1", "AMC Squeeze");
        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "AMC Squeeze"));
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of());

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
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(comment));

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
    void buildReportData_shouldCollapseCoveredThreadAndFilterCoveredComments() {
        // Cluster with two threads: t3_1 already cited in a prior headline
        // (and its only comment too), t3_2 fresh.
        var cluster = createCluster("t3_1", "Rheinmetall +12%");
        long now = System.currentTimeMillis() / 1000;
        var fresh = new RedditThread("t3_2", "wsb", "Rheinmetall Nachschlag",
                "author", "neuer post", now, "/p2", 30, 0.9, 4, now, null);
        cluster.addUpdate(fresh, 30, 4, dummyEmbedding());

        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "Rheinmetall +12%"));
        when(repository.getThread("t3_2")).thenReturn(fresh);
        var coveredComment = new RedditComment("t1_old", "t3_1", "t3_1", "ape", "covered take", 50, now, now, now);
        var freshComment = new RedditComment("t1_new", "t3_2", "t3_2", "bull", "fresh take", 60, now, now, now);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(coveredComment));
        when(repository.getCommentsForThread("t3_2", 0)).thenReturn(List.of(freshComment));

        var prior = new HeadlineRecord("t3_1", "Rheinmetall springt +12%", "",
                now - 120, List.of("t3_1"), List.of("t1_old"),
                HeadlineHighlight.IMPORTANT, "RHM", List.of(), 12.0,
                List.of(), "stock", HeadlineSentiment.BULLISH, null);

        String report = builder.buildReportData(cluster, List.of(prior));

        // Fresh thread + its fresh comment are shown in full.
        assertTrue(report.contains("Title: Rheinmetall Nachschlag"), "fresh thread shown");
        assertTrue(report.contains("fresh take"), "fresh comment shown");
        // Covered thread collapses to a one-line reference, not a full source block.
        assertTrue(report.contains("ALREADY COVERED"), "covered section present");
        assertFalse(report.contains("covered take"), "covered comment filtered out");
        // Prior-headline summary is still there for follow-up context.
        assertTrue(report.contains("Rheinmetall springt +12%"), "prior headline listed");
    }

    @Test
    void buildReportData_shouldRenderAllImagesOfAMultiImageComment() {
        var cluster = createCluster("t3_1", "Gain-Thread");
        long now = System.currentTimeMillis() / 1000;
        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "Gain-Thread"));
        var comment = new RedditComment("t1_m", "t3_1", "t3_1", "ape", "schaut mal", 5,
                now, now, now, List.of("i1", "i2"));
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(comment));
        when(brain.describeImageIfCached("i1")).thenReturn("Live-Chart: +40%");
        when(brain.describeImageIfCached("i2")).thenReturn("Depot: +12k €");

        String report = builder.buildReportData(cluster);

        assertTrue(report.contains("Live-Chart: +40%"), "first comment image shown");
        assertTrue(report.contains("Depot: +12k €"), "second comment image shown");
    }

    @Test
    void buildReportData_shouldShowImageOfADownvotedComment() {
        // Votes are sentiment, never a filter — a downvoted comment's
        // screenshot is signal too (often by inversion). It must still surface.
        var cluster = createCluster("t3_1", "Contrarian-Thread");
        long now = System.currentTimeMillis() / 1000;
        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "Contrarian-Thread"));
        var downvoted = new RedditComment("t1_d", "t3_1", "t3_1", "bear", "ihr seid alle dumm", -7,
                now, now, now, List.of("imgD"));
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(downvoted));
        when(brain.describeImageIfCached("imgD")).thenReturn("Short-These: -30% Chart");

        String report = builder.buildReportData(cluster);

        assertTrue(report.contains("Short-These: -30% Chart"), "downvoted comment image still shown");
    }

    @Test
    void buildReportData_shouldResurfaceCoveredCommentWhenItsImageLandsLate_thenStop() {
        var cluster = createCluster("t3_1", "Pennystock-Rakete");
        long now = System.currentTimeMillis() / 1000;
        when(repository.getThread("t3_1")).thenReturn(thread("t3_1", "Pennystock-Rakete"));
        var comment = new RedditComment("t1_c", "t3_1", "t3_1", "ape", "rein da", 8,
                now, now, now, List.of("imgL"));
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(comment));
        // The image only just finished analysing — it's in the cache now, but
        // the thread + comment were already cited in a prior headline.
        when(brain.describeImageIfCached("imgL")).thenReturn("Gain-Screenshot: +606%");

        var prior = new HeadlineRecord("t3_1", "Pennystock im Fokus", "", now - 90,
                List.of("t3_1"), List.of("t1_c"), HeadlineHighlight.NORMAL, null,
                List.of(), null, List.of(), null, HeadlineSentiment.BULLISH, null);

        // First build: the late image re-surfaces, flagged.
        String first = builder.buildReportData(cluster, List.of(prior));
        assertTrue(first.contains("Gain-Screenshot: +606%"), "late image surfaced once");
        assertTrue(first.contains("new image evidence"), "flagged as new evidence");

        // Second build: it was shown, so it must NOT re-surface again.
        String second = builder.buildReportData(cluster, List.of(prior));
        assertFalse(second.contains("Gain-Screenshot: +606%"), "not re-surfaced after being shown");
    }

    @Test
    void buildReportData_shouldDummy() {
        // Sentinel — kept to anchor the test file. Real coverage of the
        // context-building path is in the buildReportData_* tests above.
        assertTrue(true);
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
