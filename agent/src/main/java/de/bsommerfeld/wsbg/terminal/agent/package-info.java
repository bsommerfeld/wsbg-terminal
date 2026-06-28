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
 *       Exposes {@code ask()} and {@code see()} (vision).
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService} — scan loop and
 *       cluster lifecycle orchestrator.
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.InvestigationCluster} — in-memory semantic
 *       cluster tracking one or more thematically related Reddit threads.
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.ReportBuilder} — context assembly and
 *       prompt construction for a cluster (threads, comments, image-vision, prior headlines).
 *   <li>{@link de.bsommerfeld.wsbg.terminal.agent.EditorialAgent} — turns a dirty cluster's
 *       report into published headlines via the editorial model.
 * </ol>
 *
 * <h2>Scan Cycle</h2>
 *
 * <p>The monitor runs two concurrent schedulers:
 * <ul>
 *   <li><b>Scanner</b> (every {@code update-interval-seconds}): calls
 *       {@code scraper.scanSubreddit()} per configured subreddit, then fills gaps by
 *       updating threads not covered by the subreddit scan. New or updated threads
 *       are queued to the analysis executor.
 *   <li><b>Analysis executor</b> (single thread): processes thread update batches via
 *       {@code processUpdates()} and routes each thread to its cluster. Assignment is
 *       pure in-memory bookkeeping — no model call.
 * </ul>
 *
 * <h2>Clustering (1:1 thread)</h2>
 *
 * <p>Since the feed-wide {@link de.bsommerfeld.wsbg.terminal.agent.SubjectRegistry}
 * became the cross-thread aggregation layer, clustering is a faithful 1:1 wrapper of a
 * single Reddit thread: the cluster ID is the seed thread's Reddit ID, a known thread
 * updates its own cluster, a new thread creates one. The old embedding/cosine-vs-centroid
 * routing was removed — it drove no decision anymore and cost one Ollama embedding call
 * per thread that contended with the editorial pipeline during every scan. (The embedding
 * model itself survives for {@link de.bsommerfeld.wsbg.terminal.agent.TickerResolver}
 * tier-2 subject→ticker matching.) Cross-thread consolidation now happens at the subject
 * level via {@code SubjectRegistry.mergeIdentities}.
 *
 * <h2>Session State</h2>
 *
 * <p>State lives in memory for the lifetime of the process —
 * {@code InvestigationCluster} state, published headlines in
 * {@link de.bsommerfeld.wsbg.terminal.db.AgentRepository}, and the Reddit data backing them.
 * A short-TTL on-disk snapshot ({@code reddit.snapshot-ttl-minutes}, default 60) restores all
 * of it on a quick restart so the cold-start scan isn't repeated; a snapshot older than the
 * TTL is discarded, which keeps the ghost-cluster problem (clusters built from posts that have
 * since vanished) bounded to that window.
 *
 * <h2>Headline Generation</h2>
 *
 * <p>Every cluster change marks the cluster dirty in
 * {@link de.bsommerfeld.wsbg.terminal.agent.ClusterRegistry}; the
 * {@link de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator} debounces and hands the dirty set
 * to {@link de.bsommerfeld.wsbg.terminal.agent.EditorialAgent}. For each cluster the agent reads
 * the {@link de.bsommerfeld.wsbg.terminal.agent.ReportBuilder} brief, resolves any named
 * instrument against Yahoo Finance (validated ticker + live market data + recent news), and
 * publishes one headline per instrument (or a ticker-less line) to {@code AgentRepository}. The
 * editorial policy is a 1:1 sentiment mirror: publish wherever there is something to say, which
 * is almost always. Published headlines broadcast via {@code AgentStreamEndEvent} with the
 * {@code ||PASSIVE||} prefix.
 */
package de.bsommerfeld.wsbg.terminal.agent;
