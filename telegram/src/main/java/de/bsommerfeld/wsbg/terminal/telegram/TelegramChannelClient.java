package de.bsommerfeld.wsbg.terminal.telegram;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public finance Telegram channels via the keyless web preview
 * ({@code https://t.me/s/<channel>}) — the fast rumor/notification wire the
 * classic press legs never carry: publisher push channels blast headlines
 * minutes-fresh, 24/7, no login, no API key, no anti-bot (live-probed
 * 2026-07-16, plain client 200).
 *
 * <p><b>This source is a FIREHOSE over a channel list, not a search:</b> the
 * preview page serves only the ~20 newest messages of ONE channel, so each
 * configured channel is fetched at most once per TTL (~5 min politeness), the
 * results are unioned into a POOL shared across all queries, and
 * {@link #newsForName} filters that pool by relevance against the full
 * MESSAGE TEXT (significant words, umlaut-tolerant, precision over recall —
 * channel posts are chatty, a generic name must not flood the pool).
 * {@link #newsFor} and {@link #newsForIsin} stay no-ops: channel posts tag
 * neither tickers nor ISINs, companies only surface by name.
 *
 * <p><b>The preview opt-out trap (pinned 2026-07-16):</b> the web preview is
 * per-channel opt-OUT. A channel with preview disabled 302-redirects
 * {@code /s/<channel>} to the bare profile card ({@code tgme_page} markup,
 * NO {@code tgme_widget_message} blocks) — and the direct transport follows
 * redirects, so the answer is a healthy 200 that just carries no messages.
 * Such a channel is marked dead for the session and never fetched again;
 * transport failures (non-200, exception) stay transient and retry.
 *
 * <p>Message fields (pinned live 2026-07-16 on {@code finanzen_net} /
 * {@code godmodetrader}): each message is a
 * {@code <div class="tgme_widget_message_wrap">} block; the stable id lives
 * in {@code data-post="channel/12345"} (channel part in the channel's
 * CANONICAL casing, e.g. {@code GodmodeTrader/52869} for a lowercase query —
 * used verbatim as uuid and permalink path); the ISO-8601 timestamp with
 * offset in {@code <time datetime="2026-07-16T12:47:34+00:00">}; the body
 * HTML in {@code tgme_widget_message_text} (inline {@code <i>/<b>/<a>/<br/>}
 * only, emoji as text inside {@code <i class="emoji">}). Media-only posts
 * carry no text block and are skipped.
 */
@Singleton
public class TelegramChannelClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramChannelClient.class);

    /**
     * Publisher push channels as the starting set — finanzen.net's headline
     * blaster, GodmodeTrader's analysis wire and the MarketTwits macro feed.
     * A curated START list is wiring, not editorial curation: the set is
     * injectable via the channel-list constructor for tests and expansion.
     */
    static final List<String> DEFAULT_CHANNELS =
            List.of("finanzen_net", "godmodetrader", "markettwits");

    private static final String PREVIEW_URL = "https://t.me/s/";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int TITLE_MAX = 120;

    /** Generic words that must never carry the text-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** The stable message id: {@code data-post="channel/12345"}. */
    private static final Pattern DATA_POST =
            Pattern.compile("data-post=\"([A-Za-z0-9_]+/\\d+)\"");

    /** ISO timestamp of the message: {@code <time datetime="...">}. */
    private static final Pattern TIME_DATETIME =
            Pattern.compile("<time datetime=\"([^\"]+)\"");

    /** The message body HTML — inline elements only, never a nested div. */
    private static final Pattern MESSAGE_TEXT = Pattern.compile(
            "class=\"tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final List<String> channels;
    private final Duration cacheTtl;

    /** Channels whose preview is disabled — dead for the session, never refetched. */
    private final Set<String> deadChannels = ConcurrentHashMap.newKeySet();

    /** The shared pool: all channels' parsed previews, refreshed at most once per TTL. */
    private volatile CachedPool pool;

    private record CachedPool(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport over the default channel set. */
    public TelegramChannelClient() {
        this(new DirectWebFetcher(), DEFAULT_CHANNELS);
    }

    /**
     * Production: the {@code @DirectFirst} seam — {@code t.me/s/} answers a
     * bare client with no wall (live-probed 2026-07-16), so this client
     * declares "fine on plain HTTP" through the policy annotation like the
     * other keyless no-wall sources.
     */
    @Inject
    public TelegramChannelClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this(fetcher, DEFAULT_CHANNELS);
    }

    /** Injectable channel list for tests and expansion. */
    public TelegramChannelClient(WebFetcher fetcher, List<String> channels) {
        this(fetcher, channels, CACHE_TTL);
    }

    /** Test seam: additionally injectable TTL ({@code Duration.ZERO} = always refetch). */
    TelegramChannelClient(WebFetcher fetcher, List<String> channels, Duration cacheTtl) {
        this.fetcher = fetcher;
        this.channels = List.copyOf(channels);
        this.cacheTtl = cacheTtl;
    }

    @Override
    public String sourceName() {
        return "telegram";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** No-op: channel posts tag no tickers — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: channel posts tag no ISINs — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : currentPool()) {
            // Against the full MESSAGE TEXT, not just the derived title — the
            // company mention often sits past the 120-char headline cut.
            if (textMatches(item.summary(), words)) out.add(item);
        }
        return cap(out, limit);
    }

    /**
     * The shared, TTL-cached pool. Synchronized so a burst of queries makes
     * exactly ONE fetch round; a round that yields nothing keeps the stale
     * pool (and is not cached, so the next call retries).
     */
    private synchronized List<RawNewsItem> currentPool() {
        CachedPool cached = pool;
        if (cached != null && cached.fetchedAt().plus(cacheTtl).isAfter(Instant.now())) {
            return cached.items();
        }
        List<RawNewsItem> items = new ArrayList<>();
        for (String channel : channels) {
            if (deadChannels.contains(channel)) continue;
            items.addAll(fetchChannel(channel));
        }
        if (items.isEmpty() && cached != null) {
            return cached.items(); // outage: serve the stale pool, retry next call
        }
        pool = new CachedPool(Instant.now(), items);
        return items;
    }

    /**
     * One channel's preview page → its messages. A healthy 200 WITHOUT
     * message blocks is the preview opt-out (the followed redirect landed on
     * the profile card) — that channel is dead for the session. Transport
     * failures stay transient.
     */
    private List<RawNewsItem> fetchChannel(String channel) {
        try {
            WebResponse resp = fetcher.fetch(PREVIEW_URL + channel,
                    Map.of("User-Agent", userAgent,
                            "Accept", "text/html,application/xhtml+xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!hasMessageBlocks(body)) {
                    // Preview opt-out: /s/<channel> redirected to the bare
                    // profile card — a 200 that can never carry messages.
                    deadChannels.add(channel);
                    LOG.debug("Telegram channel {} has the web preview disabled "
                            + "(profile-card answer) — dead for this session", channel);
                    return List.of();
                }
                return parse(channel, body);
            }
            LOG.debug("Telegram channel {} answered status {}",
                    channel, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Telegram channel {} fetch failed: {}", channel, e.getMessage());
        }
        return List.of();
    }

    /** True when the body carries message widgets — the profile card carries none. */
    static boolean hasMessageBlocks(String body) {
        return body != null && body.contains("tgme_widget_message");
    }

    /**
     * One preview page → {@link RawNewsItem}s, oldest-first as served.
     * Regex-scoped per {@code data-post} block (house style for HTML),
     * garbage-tolerant: a torn or alien body yields what it can, never
     * throws. Media-only messages (no text block) are skipped.
     * Package-private for tests.
     */
    static List<RawNewsItem> parse(String channel, String html) {
        if (html == null || html.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            Matcher posts = DATA_POST.matcher(html);
            record PostAt(int start, String dataPost) {}
            List<PostAt> found = new ArrayList<>();
            while (posts.find()) found.add(new PostAt(posts.start(), posts.group(1)));
            for (int i = 0; i < found.size(); i++) {
                int end = i + 1 < found.size() ? found.get(i + 1).start() : html.length();
                String block = html.substring(found.get(i).start(), end);
                RawNewsItem item = toItem(channel, found.get(i).dataPost(), block);
                if (item != null) out.add(item);
            }
        } catch (Exception e) {
            LOG.debug("Telegram preview parse failed for {}: {}", channel, e.getMessage());
        }
        return out;
    }

    /** One message block → a {@link RawNewsItem}, or null for media-only posts. */
    private static RawNewsItem toItem(String channel, String dataPost, String block) {
        Matcher tm = MESSAGE_TEXT.matcher(block);
        if (!tm.find()) return null; // media-only post: nothing to report
        String text = stripHtml(tm.group(1));
        if (text == null || text.isBlank()) return null;
        Matcher time = TIME_DATETIME.matcher(block);
        return new RawNewsItem(
                dataPost,
                headlineOf(text),
                "Telegram (@" + channel + ")",
                // data-post verbatim — it carries the channel's canonical casing.
                "https://t.me/" + dataPost,
                time.find() ? parseDatetime(time.group(1)) : null,
                List.of(),
                null,
                text,
                false);
    }

    /** The first ~{@value TITLE_MAX} chars of the text, cut at a word boundary. */
    static String headlineOf(String text) {
        if (text.length() <= TITLE_MAX) return text;
        int cut = text.lastIndexOf(' ', TITLE_MAX);
        if (cut < TITLE_MAX / 2) cut = TITLE_MAX; // one giant token (a URL): hard cut
        return text.substring(0, cut).strip() + "…";
    }

    /** ISO-8601 with offset ("2026-07-16T12:47:34+00:00") → {@link Instant}; unparseable → null. */
    static Instant parseDatetime(String datetime) {
        try {
            return OffsetDateTime.parse(datetime).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** The body's HTML tags stripped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** True when the message text carries at least one significant word of the queried name. */
    static boolean textMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
}
