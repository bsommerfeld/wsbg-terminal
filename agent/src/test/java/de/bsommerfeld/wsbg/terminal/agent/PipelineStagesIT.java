package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine.AssignOutcome;
import de.bsommerfeld.wsbg.terminal.agent.EditorialAgent.UnitDraft;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two model-facing editorial stages we couldn't unit-test, exercised against
 * the REAL model over synthetic threads ({@link SyntheticThreads}): subject
 * extraction (does gemma4 name the subjects the room discusses?) and per-unit
 * compose (does it write a real, on-topic headline?). Yahoo may be rate-limited —
 * that only strips enrichment; the extracted names + the room-evidence headline
 * still come through.
 *
 * <p>Auto-runs locally when Ollama is available; skips in CI.
 */
@Tag("integration")
@EnabledIf("de.bsommerfeld.wsbg.terminal.agent.OllamaAvailability#available")
class PipelineStagesIT {

    private static AgentBrain brain;
    private static ClusterEngine engine;
    private static ClusterRegistry registry;
    private static RedditRepository redditRepo;
    private static AgentRepository agentRepo;
    private static EditorialAgent editorial;

    @BeforeAll
    static void up() {
        OllamaAvailability.ensureOllama();
        GlobalConfig config = new GlobalConfig();
        ApplicationEventBus bus = new ApplicationEventBus();
        redditRepo = new RedditRepository();
        agentRepo = new AgentRepository();
        LlmGate gate = new LlmGate();
        brain = new AgentBrain(config, bus, new OllamaServerManager(), gate);
        registry = new ClusterRegistry();
        engine = new ClusterEngine(registry);
        editorial = new EditorialAgent(brain, gate, registry, agentRepo, redditRepo, bus,
                new I18nService(config), new YahooFinanceClient(config),
                new SubjectRegistry(), config);
    }

    @Test
    void extractionNamesTheRoomsSubjects() {
        SyntheticThreads.Synthetic syn = SyntheticThreads.allInPoll();
        redditRepo.saveThread(syn.thread()).join();
        for (RedditComment c : syn.comments()) redditRepo.saveComment(c).join();

        AssignOutcome out = engine.assign(syn.thread(), 0, 0, "");
        List<ResolvedSubject> resolved = editorial.attributeCluster(out.clusterId());

        Set<String> names = resolved.stream()
                .map(r -> r.query().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        System.out.println("[EXTRACT-IT] extracted: " + names);
        assertTrue(resolved.size() >= 3, "should name several subjects, got: " + names);
        assertTrue(names.stream().anyMatch(n -> n.contains("nvidia")),
                "NVIDIA was in the comments and should be extracted: " + names);
    }

    @Test
    void composeWritesAnOnTopicHeadline() {
        long now = System.currentTimeMillis() / 1000;
        SubjectRegistry reg = new SubjectRegistry();
        SubjectUnit unit = reg.findOrCreate("name:nvidia", "NVIDIA");
        unit.addEvidence(new SubjectUnit.EvidenceRef("t3_x", "t1_a",
                "All in NVIDIA, 100er Hebel, dieses Mal wirklich chef", "reddit", now));
        unit.addEvidence(new SubjectUnit.EvidenceRef("t3_x", "t1_b",
                "NVIDIA ist der einzige Pick für die KI-Zukunft", "reddit", now));

        UnitDraft draft = editorial.composeUnit(unit);
        assertNotNull(draft.draft(), "compose should produce a headline; raw=" + draft.raw());
        String headline = draft.draft().headline();
        System.out.println("[COMPOSE-IT] " + headline);
        assertFalse(headline.isBlank(), "headline must not be blank");
        assertTrue(headline.toLowerCase(Locale.ROOT).contains("nvidia"),
                "headline should name the subject: " + headline);
    }

    /**
     * The full PROD orchestration after the #2 cutover: a live cluster goes
     * through {@code runUnitTick} (attribute → merge → compose-per-unit →
     * publishUnit) and a real headline lands in the repository — the same path
     * {@code AgentCoordinator} now drives. End-to-end over the real model.
     */
    @Test
    void runUnitTickPublishesHeadlinesEndToEnd() {
        SyntheticThreads.Synthetic syn = SyntheticThreads.allInPoll();
        redditRepo.saveThread(syn.thread()).join();
        for (RedditComment c : syn.comments()) redditRepo.saveComment(c).join();

        AssignOutcome out = engine.assign(syn.thread(), 0, 0, "");
        int before = agentRepo.getRecentHeadlines().size();

        editorial.runUnitTick(Set.of(out.clusterId()));

        List<HeadlineRecord> after = agentRepo.getRecentHeadlines();
        System.out.println("[UNITTICK-IT] published " + (after.size() - before) + " headline(s): "
                + after.stream().map(HeadlineRecord::headline).toList());
        assertTrue(after.size() > before,
                "runUnitTick should publish at least one headline for a live cluster");
    }
}
