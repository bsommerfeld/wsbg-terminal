package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The new two-producer wiring of the production tick, exercised WITHOUT Ollama via
 * a fake {@link ChatModel}: a dirty cluster must yield the cluster's own THEME
 * headline (the thread narrative), keyed by the cluster id, on top of (and
 * independent from) the per-subject unit headlines.
 *
 * <p>The fake model returns NO subjects for the extraction prompt — that keeps
 * Yahoo and the unit producer out of the picture so the assertion isolates the
 * theme producer. The full both-producers-at-once path is covered live in
 * {@code PipelineStagesIT}.
 */
class EditorialThemeTickTest {

    /** Branch the fake on a phrase unique to the theme system prompt. */
    private static final String THEME_REPLY =
            "{\"headline\": \"Waffenstillstand-Thread: Raum jubelt, dazu Rheinmetall-Chart −8% gepostet\","
            + " \"sentiment\": \"MIXED\", \"highlight\": \"NORMAL\", \"sourceThreadIds\": [\"t3_x\"]}";

    @Test
    void runUnitTickPublishesAClusterThemeHeadlineUnderClusterId() {
        GlobalConfig config = new GlobalConfig();
        RedditRepository redditRepo = new RedditRepository();
        AgentRepository agentRepo = new AgentRepository();
        ClusterRegistry clusterRegistry = new ClusterRegistry();
        SubjectRegistry subjectRegistry = new SubjectRegistry();
        ApplicationEventBus bus = new ApplicationEventBus();

        // extraction prompt → no subjects; theme prompt → a co-occurrence line.
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest req = inv.getArgument(0);
            String sys = req.messages().stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> ((SystemMessage) m).text())
                    .findFirst().orElse("");
            String reply = sys.contains("THIS thread") ? THEME_REPLY : "{\"subjects\": []}";
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        });

        AgentBrain brain = mock(AgentBrain.class);
        when(brain.getAgentModel()).thenReturn(model);
        when(brain.getUserLanguage()).thenReturn(UserLanguage.of("de"));
        when(brain.contextTokens()).thenReturn(8192);
        when(brain.describeImageIfCached(anyString())).thenReturn("");

        EditorialAgent editorial = new EditorialAgent(brain, clusterRegistry, agentRepo, redditRepo,
                bus, new I18nService(config), mock(YahooFinanceClient.class),
                new FakeEmbeddingService(), subjectRegistry, config);

        long now = System.currentTimeMillis() / 1000;
        RedditThread thread = new RedditThread("t3_x", "wallstreetbetsGER",
                "WAFFENSTILLSTAND", "[user]", "Endlich Frieden, Leute", now,
                "/r/wallstreetbetsGER/comments/t3_x", 12, 0.8, 1);
        redditRepo.saveThread(thread).join();
        redditRepo.saveComment(new RedditComment("t1_c", "t3_x", "t3_x", "ape",
                "Rheinmetall wird bluten", 3, now, now, now)).join();

        // One cluster == one thread.
        new ClusterEngine(clusterRegistry, new FakeEmbeddingService()).assign(thread, 0, 0, "");

        editorial.runUnitTick(Set.of("t3_x"));

        // The THEME headline is archived under the CLUSTER id (= thread id).
        List<HeadlineRecord> themeLines = agentRepo.getHeadlinesByClusterId("t3_x");
        assertEquals(1, themeLines.size(), "the dirty cluster produced exactly one theme headline");
        assertTrue(themeLines.get(0).headline().contains("Waffenstillstand-Thread"),
                "the published line is the cluster-theme narrative");
        // No subjects were extracted → no unit headlines; the theme is the only line.
        assertEquals(1, agentRepo.getRecentHeadlines().size(),
                "with no subjects, the theme producer is the only one that fires");
    }
}
