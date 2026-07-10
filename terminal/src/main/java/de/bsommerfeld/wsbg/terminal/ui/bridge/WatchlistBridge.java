package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.WatchlistService;
import de.bsommerfeld.wsbg.terminal.agent.event.WatchlistChangedEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI watchlist's socket backend. Inbound messages have shape
 * {@code {type:"watchlist", payload:{command, …}}}:
 * <ul>
 *   <li>{@code {command:"add", name:"…"}} — watch a subject (an existing unit's
 *       name/ticker or a future name that maps once the feed produces it);</li>
 *   <li>{@code {command:"remove", id:"wl-…"}} — drop an entry (dossier included);</li>
 *   <li>{@code {command:"list"}} — request the current state;</li>
 *   <li>{@code {command:"subjects"}} — request add-suggestions (live subject units),
 *       answered with one {@code watchlist-subjects} broadcast.</li>
 * </ul>
 * Outbound: one {@code watchlist} broadcast ({@code {entries:[…]}}) on client open,
 * after every mutating command, and on every {@link WatchlistChangedEvent} the
 * service posts (mapping made, dossier revision landed).
 */
@Singleton
public final class WatchlistBridge {

    private static final Logger LOG = LoggerFactory.getLogger(WatchlistBridge.class);

    private final WatchlistService service;
    private final PushHub hub;

    @Inject
    public WatchlistBridge(WatchlistService service, PushHub hub, ApplicationEventBus eventBus) {
        this.service = service;
        this.hub = hub;
        hub.on("watchlist", this::onCommand);
        hub.onClientOpen(this::push);
        eventBus.register(this);
    }

    @Subscribe
    public void onWatchlistChanged(WatchlistChangedEvent event) {
        push();
    }

    private void onCommand(Map<String, Object> payload) {
        try {
            String cmd = Payloads.str(payload.get("command"));
            switch (cmd == null ? "" : cmd) {
                case "add" -> {
                    service.add(Payloads.str(payload.get("name")));
                    push();
                }
                case "remove" -> {
                    service.remove(Payloads.str(payload.get("id")));
                    push();
                }
                case "list" -> push();
                case "subjects" -> hub.broadcastSafe("watchlist-subjects", this::subjectsPayload);
                default -> LOG.debug("watchlist: ignoring unknown command '{}'", cmd);
            }
        } catch (Exception e) {
            LOG.warn("watchlist command failed: {}", e.getMessage());
        }
    }

    private void push() {
        hub.broadcastSafe("watchlist", this::listPayload);
    }

    private Map<String, Object> listPayload() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (WatchlistService.EntryView e : service.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("name", e.name());
            m.put("tldr", e.tldr());
            m.put("report", e.report());
            m.put("updatedAt", e.updatedAtEpoch());
            m.put("mapped", e.mapped());
            if (e.ticker() != null) m.put("ticker", e.ticker());
            if (e.canonicalName() != null) m.put("canonicalName", e.canonicalName());
            // Price block: the same snapshot JSON shape the wire rows use (spark,
            // staleness flag, venue), plus the L&S daily-close history for the
            // expanded chart and the multi-day trend figures.
            Map<String, Object> snap = HeadlineJson.snapshotJson(e.snapshot());
            if (snap != null) {
                if (e.snapshot().dailyCloses().size() >= 2) {
                    snap.put("dailyCloses", e.snapshot().dailyCloses());
                }
                putFinite(snap, "change5d", e.snapshot().changeOverTradingDays(5));
                putFinite(snap, "change1m", e.snapshot().changeOverTradingDays(21));
                m.put("snapshot", snap);
            }
            // "Seit erster Erwähnung": the unit's price anchor — story memory that
            // survives every prune, verified prices on both ends.
            if (e.firstPrice() != null && e.firstPrice() > 0 && e.firstPriceAtEpoch() != null
                    && e.snapshot() != null && e.snapshot().hasPrice()) {
                Map<String, Object> anchor = new LinkedHashMap<>();
                anchor.put("price", e.firstPrice());
                anchor.put("atEpoch", e.firstPriceAtEpoch());
                anchor.put("percent",
                        (e.snapshot().price() - e.firstPrice()) / e.firstPrice() * 100.0);
                m.put("sinceFirst", anchor);
            }
            if (!e.news().isEmpty()) {
                List<Map<String, Object>> news = new ArrayList<>();
                for (var n : e.news()) {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    nm.put("title", n.title());
                    nm.put("publisher", n.publisher());
                    nm.put("url", n.link());
                    nm.put("publishedAt", n.publishedAt() == null ? null
                            : n.publishedAt().getEpochSecond());
                    news.add(nm);
                }
                m.put("news", news);
            }
            if (!e.recentHeadlines().isEmpty()) {
                List<Map<String, Object>> lines = new ArrayList<>();
                for (var h : e.recentHeadlines()) {
                    Map<String, Object> hm = new LinkedHashMap<>();
                    hm.put("text", h.text());
                    hm.put("atEpoch", h.atEpoch());
                    if (h.sentiment() != null && !h.sentiment().isBlank()) {
                        hm.put("sentiment", h.sentiment());
                    }
                    lines.add(hm);
                }
                m.put("wireLines", lines);
            }
            if (e.evidenceCount() > 0) m.put("evidenceCount", e.evidenceCount());
            if (e.lastActivityEpoch() != null) m.put("lastActivity", e.lastActivityEpoch());
            items.add(m);
        }
        return Map.of("entries", items);
    }

    /** Puts a nullable/NaN-prone figure only when it is a real number. */
    private static void putFinite(Map<String, Object> m, String key, Double v) {
        if (v != null && Double.isFinite(v)) m.put(key, v);
    }

    private Map<String, Object> subjectsPayload() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (WatchlistService.SubjectOption o : service.subjectOptions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", o.name());
            if (o.ticker() != null) m.put("ticker", o.ticker());
            items.add(m);
        }
        return Map.of("items", items);
    }
}
