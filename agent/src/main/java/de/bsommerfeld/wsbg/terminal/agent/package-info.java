/**
 * Passive Market Intelligence Agent.
 *
 * <p>This package implements a continuous, background-running editorial desk that monitors
 * Reddit discussions, clusters semantically related threads, scores their market significance,
 * and emits structured financial headlines via the application event bus.
 *
 * <h2>Architecture Overview</h2>
 *
 * <p>The system is composed of five collaborating components:
 * <ol>
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.AgentBrain} — LLM gateway (Ollama/Gemma4).
 *       Exposes {@code ask()}, {@code see()} (vision), and {@code extractTickers()}.
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService} — scan loop and
 *       cluster lifecycle orchestrator.
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.InvestigationCluster} — in-memory semantic
 *       cluster tracking one or more thematically related Reddit threads.
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.ReportBuilder} — context assembly,
 *       delta-context diffing, and prompt construction.
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.SignificanceScorer} — heuristic score
 *       computing whether a cluster has enough signal to warrant AI evaluation.
 * </ol>
 *
 * <h2>Scan Cycle</h2>
 *
 * <p>The monitor runs two concurrent schedulers:
 * <ul>
 *   <li><b>Scanner</b> (every 30 s): calls {@code scraper.scanSubreddit()} per configured
 *       subreddit (fetching up to 50 threads per listing), then fills gaps by updating threads
 *       not covered by the subreddit scan. New or updated threads are queued to the analysis
 *       executor.
 *   <li><b>Analysis executor</b> (single thread): processes thread update batches via
 *       {@code processUpdates()}, embeds each thread using an Ollama embedding model, and
 *       routes it to the nearest existing cluster or creates a new one.
 * </ul>
 *
 * <h2>Semantic Clustering</h2>
 *
 * <p>Each incoming thread is embedded into a 768-dimensional vector using an Ollama embedding
 * model (e.g., {@code nomic-embed-text}). The vector is compared against all existing cluster
 * centroids using cosine similarity. If the best match exceeds a configured threshold (default
 * 0.72), the thread is added to that cluster; otherwise a new cluster is created.
 *
 * <p>Cluster centroids are updated via Exponential Moving Average (EMA) on each new thread,
 * ensuring centroids drift toward the most recent semantic focus of the cluster.
 *
 * <p>{@link de.bsommerfeld.wsbg.terminal.agent.InvestigationCluster} IDs are derived directly
 * from the initial seed thread's Reddit ID. This makes IDs deterministic across restarts,
 * enabling the database to restore cluster editorial history after a process restart.
 *
 * <h2>Significance Scoring and Evaluation Gate</h2>
 *
 * <p>{@link de.bsommerfeld.wsbg.terminal.agent.SignificanceScorer} computes a numeric score
 * per cluster based on weighted signals: thread count, total score, comment velocity, upvote
 * ratio, and recency. A cluster must exceed a configured absolute threshold before it is
 * offered to the AI for headline evaluation.
 *
 * <p>A two-stage gate prevents over-evaluation:
 * <ol>
 *   <li><b>Maturity gate</b>: single-thread clusters with fewer than 3 comments and less than
 *       2 minutes of age are suppressed as noise.
 *   <li><b>CHL delta gate</b>: once a cluster has been evaluated at least once
 *       ({@code lastEvaluatedAt != null}), re-evaluation requires the current significance to
 *       exceed the previous significance by at least {@code CHL_SIGNIFICANCE_DELTA}. This
 *       prevents re-reporting the same story on every scan cycle.
 * </ol>
 *
 * <h2>Cross-Restart State Persistence</h2>
 *
 * <p>Because {@code InvestigationCluster} objects are in-memory, all evaluation state
 * ({@code reportHistory}, {@code lastEvaluatedAt}, {@code significanceAtLastEvaluation}) is
 * lost on process restart. To preserve editorial continuity:
 * <ul>
 *   <li>Every accepted headline is persisted to SQLite via
 *       {@link de.bsommerfeld.wsbg.terminal.db.AgentRepository#saveHeadline}, storing
 *       cluster ID, headline text, and the full combined context at time of generation.
 *   <li>On the first evaluation of a cluster after restart (detected by empty
 *       {@code reportHistory}), the monitor queries
 *       {@link de.bsommerfeld.wsbg.terminal.db.AgentRepository#getHeadlinesByClusterId} to
 *       restore {@code reportHistory} and set {@code lastEvaluatedAt} and
 *       {@code significanceAtLastEvaluation} from the most recent persisted headline. This
 *       re-arms the CHL delta gate, preventing immediate re-evaluation of unchanged clusters.
 * </ul>
 *
 * <h2>Delta-Context Principle</h2>
 *
 * <p>On CHL re-evaluations (where {@code lastEvaluatedAt != null}), the prompt sent to the LLM
 * contains not just the full accumulated context but also an explicit
 * {@code === NEW SINCE LAST HEADLINE ===} section containing only the textual lines that were
 * not present in the context at the time of the previous headline ({@code inv.cachedContext}).
 *
 * <p>This "delta diffing" — implemented in
 * {@link de.bsommerfeld.wsbg.terminal.agent.ReportBuilder#buildDeltaContext} — forces the AI
 * to focus its verdict on what genuinely changed, reducing the probability of paraphrased
 * duplicate headlines from re-reading the same evidence.
 *
 * <h2>Headline Generation</h2>
 *
 * <p>If a cluster passes all gates, {@link de.bsommerfeld.wsbg.terminal.agent.ReportBuilder}
 * assembles a prompt containing cluster metadata, up to 3 source threads (best + 2 additional),
 * image vision analysis (if available), relevant comments, the cluster's editorial history
 * (restored from DB on restart), and the new delta context section. The AI must return one of
 * two structured verdicts:
 * <pre>
 *   VERDICT: REJECT
 *   VERDICT: ACCEPT
 *   REPORT: headline text (in user's language, max 15 words)
 * </pre>
 *
 * <p>Accepted headlines are saved to the database, broadcast via
 * {@link de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent} with the
 * {@code ||PASSIVE||} prefix, and trigger a ticker extraction pass on all candidate clusters.
 *
 * <h2>Ticker Extraction</h2>
 *
 * <p>After each headline evaluation batch, all candidate clusters with non-blank
 * {@code cachedContext} are submitted to ticker extraction. The extractor sends a free-form
 * JSON-array prompt to the same LLM (via {@code AgentBrain.ask()}), then parses the response
 * with a regex bracket-extractor tolerant of markdown fences and prose preamble. Extracted
 * tickers are persisted to {@code AgentRepository} and broadcast as a
 * {@link de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TickerSnapshotEvent}.
 *
 * <h2>Data Lifecycle</h2>
 *
 * <p>All agent data (headlines, ticker mentions) shares the same 24-hour TTL as Reddit thread
 * data. The hourly cleanup cycle calls both {@code RedditRepository.cleanupOldThreads()} and
 * {@code AgentRepository.cleanup()} in the same scan iteration, ensuring data coherence.
 */
package de.bsommerfeld.wsbg.terminal.agent;
