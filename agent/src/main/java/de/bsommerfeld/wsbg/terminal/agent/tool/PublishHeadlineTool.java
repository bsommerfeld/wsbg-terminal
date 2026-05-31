package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.agent.InvestigationCluster;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Persists a headline for a cluster and broadcasts it to the UI event bus.
 *
 * <p>
 * Validates that:
 * <ul>
 * <li>the cluster exists,</li>
 * <li>the headline is non-empty and ≤ 20 words,</li>
 * <li>no headline has been published for this (cluster, ticker) slot in the
 * last 10 min (either by another agent run or earlier in this run).</li>
 * </ul>
 */
@Deprecated // legacy agent tool-loop — persist/QA now lives in HeadlineWriter; no longer wired
public final class PublishHeadlineTool implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(PublishHeadlineTool.class);
    /**
     * Word cap on the headline text. 20 leaves room for catalyst
     * headlines that need a subject + reaction („US-Iran 60-Tage
     * Waffenstillstand; WSBG-Defense bearish, Risk-on bullish"),
     * still well under newspaper-headline length.
     */
    private static final int MAX_WORDS = 20;
    /**
     * Catches any &lt;...&gt; sequence — opens, closes, and self-closes
     * alike. The model occasionally emits {@code &lt;strong&gt;}
     * fragments expecting Markdown-like rendering; the UI ships the
     * text as plain text, so the tag would leak literally.
     */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    /**
     * Per-cluster cooldown — 10 min is the spam guard, not the dedup
     * mechanism. The real dedup happens upstream in the prompt: the
     * cluster report tags every thread/comment that was already cited
     * in a prior headline with [✓ COVERED], so the agent self-skips
     * when nothing new appeared. The cooldown is a backstop for when
     * the model ignores those tags.
     */
    private static final long COOLDOWN_SECS = 600;

    /**
     * Cross-cluster ticker cooldown — within this window, a NORMAL
     * headline mentioning the same ticker is just clutter (one cluster
     * already covered it), and an IMPORTANT headline gets downgraded
     * to NORMAL so only the first move-flag of the window lights up
     * the UI. 15 min lets the first headline breathe before alternative
     * angles can land.
     */
    private static final long TICKER_WINDOW_SECS = 900;

    @Override
    public String name() {
        return "publishHeadline";
    }

    @Override
    public ToolSpecification specification() {
        JsonArraySchema stringArray = JsonArraySchema.builder()
                .items(JsonStringSchema.builder().build())
                .build();
        return ToolSpecification.builder()
                .name(name())
                .description("Publishes a headline for a cluster. Headline must be ≤ 20 words, "
                        + "declarative (no questions), news-wire style, in the user's language. "
                        + "Do NOT open the headline with 'Apes', 'Reddit-Apes' or 'WSBG-Apes' — "
                        + "vary the lead. A 10-min cooldown per (cluster, ticker) is enforced server-side. "
                        + "Optionally attach sourceThreadIds and sourceCommentIds so the UI can "
                        + "deep-link back to the specific evidence — keep these short, only the "
                        + "ids you actually leaned on. Set highlight to IMPORTANT when the cluster "
                        + "implies a real price move, breaking event, or pennystock rocket so the "
                        + "UI lights it up — see system prompt for the rubric.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("clusterId", "Cluster ID to attach the headline to.")
                        .addStringProperty("headline", "The headline text (≤ 20 words).")
                        .addProperty("sourceThreadIds", stringArray)
                        .addProperty("sourceCommentIds", stringArray)
                        .addStringProperty("highlight",
                                "Editorial flag: NORMAL (default) or IMPORTANT.")
                        .addStringProperty("tickerSymbol",
                                "Ticker shape only — 1-5 uppercase letters, optionally with a "
                                        + ".X or -X class suffix (TSLA, BRK.A). Omit for "
                                        + "non-instrument headlines or when the post lacks a "
                                        + "clear primary asset. Do NOT pass category words like "
                                        + "Energie / Crypto / Oil — those go in 'sectors'.")
                        .addProperty("subjects", JsonArraySchema.builder()
                                .description("Every named instrument visible in the headline "
                                        + "text, with its ticker. The UI highlights each name "
                                        + "with a glow; on hover the letters flip into the "
                                        + "ticker; on click the ticker is copied to clipboard. "
                                        + "For multi-instrument headlines ('CrowdStrike, Palo "
                                        + "Alto Networks und Zscaler vor Earnings') pass all "
                                        + "three. For single-instrument headlines pass one. "
                                        + "Each entry: { name: exact substring you wrote, "
                                        + "ticker: 1-5 uppercase letters }. Omit (empty array) "
                                        + "when the headline has no specific named instrument.")
                                .items(JsonObjectSchema.builder()
                                        .addStringProperty("name",
                                                "Asset name exactly as written in the headline.")
                                        .addStringProperty("ticker",
                                                "Ticker symbol — 1-5 caps, no leading $.")
                                        .required("name", "ticker")
                                        .build())
                                .build())
                        .addProperty("priceMovePercent", JsonNumberSchema.builder()
                                .description("Signed % move implied by the post (e.g. 14, -22, 606). "
                                        + "Use the number the post / comments actually mention; "
                                        + "do not estimate.")
                                .build())
                        .addProperty("sectors", stringArray)
                        .addStringProperty("assetClass",
                                "One of: stock, etf, crypto, commodity, forex, bond, option, other. "
                                        + "Omit if the headline isn't about a specific instrument.")
                        .addStringProperty("sentiment",
                                "Crowd-sentiment classifier. EXACTLY one of: BULLISH, BEARISH, "
                                        + "MIXED, FOMO, CAPITULATION, SQUEEZE, REVERSAL, "
                                        + "BREAKOUT, NEUTRAL. Read the upvote/downvote pattern "
                                        + "and the room's directional bias — this is what the UI "
                                        + "colours, so getting it right matters more than ticker "
                                        + "categorisation.")
                        .required("clusterId", "headline", "sentiment")
                        .build())
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        String clusterId = args.path("clusterId").asText("").trim();
        String headline = args.path("headline").asText("").trim();

        if (clusterId.isEmpty())
            return "Error: 'clusterId' is required.";
        if (headline.isEmpty())
            return "Error: 'headline' is required.";

        int wordCount = headline.split("\\s+").length;
        if (wordCount > MAX_WORDS)
            return "Error: headline has " + wordCount + " words, max " + MAX_WORDS
                    + ". Shorten and try again.";

        if (HTML_TAG.matcher(headline).find())
            return "Error: headline contains HTML/markup ('<...>'). The UI renders the headline "
                    + "text as plain text — write a clean German sentence, no <strong>, <em>, "
                    + "<br>, no Markdown asterisks or backticks. Resubmit without markup.";

        InvestigationCluster cluster = ctx.clusterRegistry().getCluster(clusterId);
        if (cluster == null)
            return "Error: no cluster with id '" + clusterId + "'.";

        List<String> threadIds = readStringArray(args.path("sourceThreadIds"));
        List<String> commentIds = readStringArray(args.path("sourceCommentIds"));
        // Filter thread IDs to actual cluster members — the agent occasionally
        // hallucinates IDs that aren't in the cluster.
        threadIds.removeIf(id -> !cluster.activeThreadIds.contains(id));
        // Comment IDs must start with the Reddit comment prefix t1_; anything
        // else (usernames, free text) is silently dropped.
        commentIds.removeIf(id -> !id.startsWith("t1_"));

        HeadlineHighlight highlight = HeadlineHighlight.fromString(
                args.path("highlight").asText(""));
        String tickerSymbol = sanitizeTicker(args.path("tickerSymbol").asText("").trim());
        // Yahoo Finance is the single source of truth for tickers. If the
        // model passed a ticker without first calling lookupTicker in
        // this run, drop it on the floor — better no badge than a wrong
        // one. The headline itself still publishes.
        if (tickerSymbol != null && !ctx.isTickerValidated(tickerSymbol)) {
            LOG.info("[AGENT] dropped unvalidated tickerSymbol '{}' for {} (no prior lookupTicker)",
                    tickerSymbol, clusterId);
            tickerSymbol = null;
        }

        // Per-run + per-cluster dedup is keyed on (clusterId, ticker).
        // Community-question clusters („Wo rein?", „Was haltet ihr von …")
        // surface multiple distinct assets in the comments; the agent
        // needs to publish one headline per asset, all on the same
        // cluster id, all in the same run. Same key twice → block.
        // Empty ticker counts as a single „no-ticker" slot per cluster.
        String runKey = runKey(clusterId, tickerSymbol);
        if (ctx.publishedThisRun().contains(runKey)) {
            String slot = tickerSymbol == null ? "this cluster (no ticker)"
                    : "ticker " + tickerSymbol + " on this cluster";
            return "Error: already published a headline for " + slot
                    + " in this run. Switch to a different ticker, or move on / call done.";
        }

        long now = Instant.now().getEpochSecond();
        // Per-ticker cooldown: a previous headline for the SAME (cluster,
        // ticker) within COOLDOWN_SECS blocks. A previous headline for the
        // same cluster but a different ticker is fine — that's the
        // multi-asset case.
        List<HeadlineRecord> prior = ctx.agentRepository().getHeadlinesByClusterId(clusterId);
        if (!prior.isEmpty()) {
            HeadlineRecord lastForSlot = null;
            for (int i = prior.size() - 1; i >= 0; i--) {
                HeadlineRecord r = prior.get(i);
                String rTicker = r.tickerSymbol();
                boolean sameSlot = tickerSymbol == null
                        ? (rTicker == null || rTicker.isEmpty())
                        : tickerSymbol.equalsIgnoreCase(rTicker);
                if (sameSlot) {
                    lastForSlot = r;
                    break;
                }
            }
            if (lastForSlot != null) {
                long age = now - lastForSlot.createdAt();
                if (age < COOLDOWN_SECS) {
                    String slot = tickerSymbol == null ? "this cluster (no ticker)"
                            : "ticker " + tickerSymbol;
                    return "Error: " + slot + " already had a headline " + age
                            + "s ago (cooldown " + COOLDOWN_SECS + "s). If nothing "
                            + "new has happened in this cluster, move on to the next "
                            + "dirty cluster — or publish a different angle with a "
                            + "different ticker.";
                }
            }
        }

        // Subjects = every named instrument the UI should glow + flip.
        // Each one must (a) appear verbatim in the headline text and
        // (b) have a valid ticker shape. Drop entries that fail either
        // check — better to skip a glow than wrap the wrong substring
        // or render an invalid ticker on hover.
        List<HeadlineSubject> subjects = new ArrayList<>();
        JsonNode subjectsNode = args.path("subjects");
        if (subjectsNode != null && subjectsNode.isArray()) {
            for (JsonNode entry : subjectsNode) {
                String name = entry.path("name").asText("").trim();
                String ticker = sanitizeTicker(entry.path("ticker").asText("").trim());
                if (name.isEmpty() || ticker == null) continue;
                if (!ctx.isTickerValidated(ticker)) {
                    LOG.info("[AGENT] dropped unvalidated subject ticker '{}' (name '{}') — "
                            + "no prior lookupTicker", ticker, name);
                    continue;
                }
                if (!headline.contains(name)) {
                    LOG.info("[AGENT] subject name '{}' not in headline text — dropping", name);
                    continue;
                }
                // De-duplicate on (name, ticker) so the same pair passed
                // twice doesn't produce two wraps competing for the same
                // substring.
                final String fname = name;
                final String fticker = ticker;
                boolean dup = subjects.stream().anyMatch(
                        s -> s.name().equals(fname) && s.ticker().equals(fticker));
                if (!dup) subjects.add(new HeadlineSubject(name, ticker));
            }
        }

        List<String> threadIdsForDedup = new ArrayList<>(readStringArray(args.path("sourceThreadIds")));
        threadIdsForDedup.removeIf(id -> !cluster.activeThreadIds.contains(id));

        // Cross-cluster ticker dedup: same ticker within the window
        // is usually noise — one cluster already covered $NBIS, the
        // second hit recycles the same story under a different cluster
        // id. We reject UNLESS the new headline cites at least one
        // thread that no prior same-ticker headline already cited.
        // That carves out the genuine "second angle, new evidence"
        // case while killing the more common "agent forgot it just
        // published this" duplicate.
        if (tickerSymbol != null) {
            final String tickerForFilter = tickerSymbol;
            long horizon = now - TICKER_WINDOW_SECS;
            List<HeadlineRecord> recentSameTicker = ctx.agentRepository().getRecentHeadlines().stream()
                    .filter(h -> h.createdAt() >= horizon)
                    .filter(h -> !clusterId.equals(h.clusterId()))
                    .filter(h -> tickerForFilter.equalsIgnoreCase(h.tickerSymbol()))
                    .toList();
            if (!recentSameTicker.isEmpty()) {
                java.util.Set<String> alreadyCited = new java.util.HashSet<>();
                recentSameTicker.forEach(h -> alreadyCited.addAll(h.sourceThreadIds()));
                boolean hasNewThread = threadIdsForDedup.stream().anyMatch(t -> !alreadyCited.contains(t));
                if (!hasNewThread) {
                    long lastAt = recentSameTicker.get(recentSameTicker.size() - 1).createdAt();
                    long age = now - lastAt;
                    LOG.info("[AGENT] skip duplicate ticker {} for {} (prior headline {}s ago, no fresh threads)",
                            tickerSymbol, clusterId, age);
                    return "Error: ticker " + tickerSymbol + " already covered "
                            + age + "s ago by another cluster with the same evidence. "
                            + "Move on to the next dirty cluster, or publish a different angle "
                            + "with a fresh sourceThreadId.";
                }
                if (highlight == HeadlineHighlight.IMPORTANT) {
                    LOG.info("[AGENT] downgrade IMPORTANT→NORMAL for {} (ticker {} already flagged in last {}s)",
                            clusterId, tickerSymbol, TICKER_WINDOW_SECS);
                    highlight = HeadlineHighlight.NORMAL;
                }
            }
        }

        Double priceMove = null;
        JsonNode pmNode = args.path("priceMovePercent");
        if (pmNode.isNumber())
            priceMove = pmNode.asDouble();
        // Sanity-check: a priceMovePercent above 500% combined with a
        // € / $ amount in the headline text almost always means the
        // model misread a position P&L („+266% on this trade", „+436%
        // Gewinn auf 0.75 €") as a day-move. Real intraday rockets
        // (+606% pennystocks) exist, but only when the headline does
        // NOT carry an absolute money amount. When both signals fire,
        // null the field — the headline still publishes, just without
        // the misleading badge.
        if (priceMove != null && Math.abs(priceMove) > 500.0
                && headline.matches(".*\\d[\\d.,]*\\s*(€|\\$|EUR|USD).*")) {
            LOG.info("[AGENT] capping suspicious priceMovePercent {} for {} — "
                    + "headline carries a money amount, likely position-P&L "
                    + "not a day-move", priceMove, clusterId);
            priceMove = null;
        }

        List<String> sectors = readStringArray(args.path("sectors")).stream()
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(distinctByLower())
                .stream().limit(2).toList();
        String assetClass = normalizeAssetClass(args.path("assetClass").asText("").trim());

        HeadlineSentiment sentiment = HeadlineSentiment.fromString(
                args.path("sentiment").asText(""));

        // Attach a live Yahoo snapshot for the primary ticker — current
        // price, day move, and intraday sparkline series. Cached from the
        // lookupTicker call earlier in this run, so this is almost always
        // a cache hit (no extra round-trip). Null when there's no ticker
        // or Yahoo had nothing; the headline publishes either way.
        MarketSnapshot snapshot = null;
        if (tickerSymbol != null && ctx.yahooFinance() != null) {
            snapshot = ctx.yahooFinance().fetchChart(tickerSymbol).orElse(null);
        }

        ctx.agentRepository().saveHeadline(clusterId, headline, "",
                threadIds, commentIds, highlight, tickerSymbol, subjects, priceMove,
                sectors, assetClass, sentiment, snapshot);
        ctx.publishedThisRun().add(runKey(clusterId, tickerSymbol));
        String subjectsLog = subjects.isEmpty() ? "" :
                " subjects=" + subjects.stream()
                        .map(s -> "«" + s.name() + "»→" + s.ticker())
                        .reduce((a, b) -> a + "," + b).orElse("");
        LOG.info("[AGENT] published headline for {} [{}{} {}{}]{}{}{}: {}",
                clusterId,
                highlight,
                tickerSymbol == null ? "" : " " + tickerSymbol,
                sentiment,
                priceMove == null ? "" : String.format(" %+.1f%%", priceMove),
                sectors.isEmpty() ? "" : " sectors=" + sectors,
                assetClass == null ? "" : " asset=" + assetClass,
                subjectsLog,
                headline);
        if (!threadIds.isEmpty() || !commentIds.isEmpty()) {
            LOG.info("[AGENT]   sources: threads={}, comments={}", threadIds, commentIds);
        }

        // The UI only checks the ||PASSIVE|| prefix and pulls the actual record
        // (with highlight + ticker + move) from AgentRepository afterwards.
        String payload = "||PASSIVE||" + headline + "||REF||ID:" + clusterId;
        ctx.eventBus().post(new AgentStreamEndEvent(payload));

        return "Published headline for " + clusterId
                + " (highlight=" + highlight.name() + ").";
    }

    /**
     * Ticker shape filter. Gemma4 routinely passes category words
     * ("Energie", "Brent", "Crypto") or full company names ("Snowflake")
     * as tickerSymbol — those then get rendered with a {@code $} prefix
     * in the headline UI, which looks broken. We accept only the
     * ticker-shaped strings the UI's highlighter actually formats:
     * 1-5 uppercase letters, optionally followed by a class suffix
     * ({@code BRK.A}, {@code TSM-P}). Anything else returns null so the
     * record stores no ticker rather than a malformed one.
     */
    private static String sanitizeTicker(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String clean = raw.startsWith("$") ? raw.substring(1) : raw;
        if (clean.matches("[A-Z]{1,5}([.-][A-Z0-9]{1,3})?")) {
            return clean;
        }
        LOG.info("[AGENT] rejected tickerSymbol '{}' — not a ticker shape", raw);
        return null;
    }

    /**
     * Case-insensitive distinct collector — drops duplicates that
     * differ only in casing ("AI" vs "Ai" vs "ai"). Preserves
     * first-seen capitalisation for the surviving element.
     */
    private static java.util.stream.Collector<String, ?, java.util.List<String>> distinctByLower() {
        return java.util.stream.Collector.of(
                java.util.ArrayList::new,
                (acc, s) -> {
                    String key = s.toLowerCase(java.util.Locale.ROOT);
                    boolean exists = acc.stream().anyMatch(
                            x -> x.toLowerCase(java.util.Locale.ROOT).equals(key));
                    if (!exists) acc.add(s);
                },
                (a, b) -> { a.addAll(b); return a; });
    }

    /**
     * Coerces gemma4's free-form asset-class guesses ("Stock", "Aktie",
     * "Equity", "Stocks") into the small canonical vocabulary the UI
     * filter knows about. Unknown labels return null.
     */
    private static String normalizeAssetClass(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        if (s.matches("stock|stocks|equity|equities|aktie|aktien|share|shares")) return "stock";
        if (s.matches("etf|etfs|fund|funds")) return "etf";
        if (s.matches("crypto|cryptocurrency|coin|coins|token|tokens|krypto")) return "crypto";
        if (s.matches("commodity|commodities|metal|metals|oil|gold|silver")) return "commodity";
        if (s.matches("forex|fx|currency|currencies|pair|pairs")) return "forex";
        if (s.matches("bond|bonds|treasury|treasuries|note|notes|yield")) return "bond";
        if (s.matches("option|options|call|put|warrant|warrants")) return "option";
        if (s.matches("other|misc")) return "other";
        return null;
    }

    /**
     * Per-run dedup key. (clusterId, ticker) — empty ticker uses a
     * single „no-ticker" slot so the per-run lock for tickerless
     * headlines still works.
     */
    private static String runKey(String clusterId, String ticker) {
        if (ticker == null || ticker.isEmpty()) return clusterId + "|_no_ticker";
        return clusterId + "|" + ticker.toUpperCase(java.util.Locale.ROOT);
    }

    private static List<String> readStringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray())
            return out;
        for (JsonNode el : node) {
            String s = el.asText("").trim();
            if (!s.isEmpty())
                out.add(s);
        }
        return out;
    }
}
