package de.bsommerfeld.wsbg.terminal.agent.tool;

import de.bsommerfeld.wsbg.terminal.agent.AgentBrain;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRegistry;
import de.bsommerfeld.wsbg.terminal.agent.ReportBuilder;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Carrier for everything a {@link Tool} might need at execute time. One
 * instance is constructed per agent run; per-run state (e.g. published
 * cluster IDs for dedup) lives on the instance.
 */
@Deprecated // legacy agent tool-loop — replaced by the deterministic EditorialAgent pipeline; no longer wired
public final class ToolContext {

    private final ClusterRegistry clusterRegistry;
    private final AgentRepository agentRepository;
    private final AgentBrain brain;
    private final ReportBuilder reportBuilder;
    private final ApplicationEventBus eventBus;
    private final I18nService i18n;
    private final YahooFinanceClient yahooFinance;

    /** Cluster IDs the agent has already published a headline for in this run. */
    private final Set<String> publishedThisRun = new HashSet<>();

    /**
     * Ticker symbols the agent has resolved through {@code lookupTicker}
     * in this run. {@code publishHeadline} accepts a ticker only when it
     * appears here — Yahoo Finance is the single source of truth, so a
     * symbol the model invented gets dropped on the floor instead of
     * surfacing as a bogus label in the UI. Stored upper-cased so the
     * lookup is case-insensitive (Yahoo returns canonical casing, but
     * the agent sometimes mangles it).
     */
    private final Set<String> validatedTickersThisRun = new HashSet<>();

    /** Becomes true once a {@code done} tool fires; loops check this. */
    private volatile boolean doneSignalled = false;

    /** Optional human-readable reason emitted by the {@code done} tool. */
    private volatile String doneReason = "";

    public ToolContext(ClusterRegistry clusterRegistry, AgentRepository agentRepository,
            AgentBrain brain, ReportBuilder reportBuilder,
            ApplicationEventBus eventBus, I18nService i18n,
            YahooFinanceClient yahooFinance) {
        this.clusterRegistry = clusterRegistry;
        this.agentRepository = agentRepository;
        this.brain = brain;
        this.reportBuilder = reportBuilder;
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.yahooFinance = yahooFinance;
    }

    public ClusterRegistry clusterRegistry() {
        return clusterRegistry;
    }

    public AgentRepository agentRepository() {
        return agentRepository;
    }

    public AgentBrain brain() {
        return brain;
    }

    public ReportBuilder reportBuilder() {
        return reportBuilder;
    }

    public ApplicationEventBus eventBus() {
        return eventBus;
    }

    public I18nService i18n() {
        return i18n;
    }

    public Set<String> publishedThisRun() {
        return publishedThisRun;
    }

    public YahooFinanceClient yahooFinance() {
        return yahooFinance;
    }

    /**
     * Records a Yahoo-validated symbol so subsequent
     * {@code publishHeadline} calls in this run accept it. Stores both
     * the full Yahoo form ({@code RHM.DE}) and the base
     * ({@code RHM}) — the agent may write either into the headline
     * metadata, and both are equally trustworthy once Yahoo confirmed
     * the listing exists.
     */
    public void recordValidatedTicker(String symbol) {
        if (symbol == null) return;
        String s = symbol.trim();
        if (s.isEmpty()) return;
        validatedTickersThisRun.add(s.toUpperCase(Locale.ROOT));
        int dot = s.indexOf('.');
        if (dot > 0) {
            validatedTickersThisRun.add(s.substring(0, dot).toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Returns whether the given symbol was validated this run.
     * Case-insensitive: the agent's casing may differ from Yahoo's.
     */
    public boolean isTickerValidated(String symbol) {
        if (symbol == null) return false;
        String s = symbol.trim();
        if (s.isEmpty()) return false;
        return validatedTickersThisRun.contains(s.toUpperCase(Locale.ROOT));
    }

    /** Visible for tests only. */
    Set<String> validatedTickersThisRun() {
        return validatedTickersThisRun;
    }

    public boolean isDone() {
        return doneSignalled;
    }

    public void signalDone(String reason) {
        this.doneSignalled = true;
        this.doneReason = reason == null ? "" : reason;
    }

    public String doneReason() {
        return doneReason;
    }
}
