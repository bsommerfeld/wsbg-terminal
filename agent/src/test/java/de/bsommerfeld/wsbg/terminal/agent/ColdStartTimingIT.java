package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Times RSS cold-start to first cluster. After decoupling comment ingestion from
 * the listing scan, clustering should no longer wait out the ~11-minute serial
 * comment backfill — first clusters must form within ~2 minutes.
 *
 * <pre>COLDSTART_TIMING=true mvn test -pl agent -Dtest=ColdStartTimingIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "COLDSTART_TIMING", matches = "true")
class ColdStartTimingIT {

    @Test
    void firstClusterFormsWithinTwoMinutes() throws Exception {
        Path appDir = StorageUtils.getSnapshotsDir();
        List<Path> parked = park(appDir);
        try {
            GlobalConfig config = new GlobalConfig();
            ApplicationEventBus bus = new ApplicationEventBus();
            RedditRepository redditRepo = new RedditRepository();
            AgentRepository agentRepo = new AgentRepository();
            AgentBrain brain = new AgentBrain(config, bus, new OllamaServerManager(), new LlmGate());
            ClusterRegistry registry = new ClusterRegistry();
            RssRedditScraper rss = new RssRedditScraper(redditRepo, config, bus);

            ClusterEngine clusterEngine = new ClusterEngine(registry);

            long start = System.currentTimeMillis();
            new PassiveMonitorService(rss, brain, bus, redditRepo, agentRepo,
                    new RedditSnapshotStore(), new AgentSnapshotStore(), registry,
                    new SubjectRegistry(), clusterEngine, config);

            long deadline = start + 120_000;
            while (System.currentTimeMillis() < deadline && registry.isEmpty()) {
                Thread.sleep(2000);
            }
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            System.out.println("[COLDSTART] first cluster after " + elapsed + "s; clusters="
                    + registry.size() + ", threads in repo=" + redditRepo.getAllThreads().size());
            assertTrue(!registry.isEmpty(),
                    "no cluster within 120s — comment ingestion still blocks the scan");
            assertTrue(elapsed < 120, "cold start still too slow: " + elapsed + "s");
        } finally {
            unpark(parked);
        }
    }

    private static List<Path> park(Path appDir) throws Exception {
        List<Path> parked = new ArrayList<>();
        for (String n : List.of("reddit-snapshot.json", "agent-snapshot.json")) {
            Path f = appDir.resolve(n);
            if (Files.exists(f)) { Files.move(f, appDir.resolve(n + ".cst-bak")); parked.add(f); }
        }
        return parked;
    }

    private static void unpark(List<Path> parked) throws Exception {
        for (Path f : parked) {
            Files.deleteIfExists(f);
            Path bak = f.resolveSibling(f.getFileName() + ".cst-bak");
            if (Files.exists(bak)) Files.move(bak, f);
        }
    }
}
