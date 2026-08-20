package de.bsommerfeld.wsbg.terminal.web.impl.sources.telegram;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * 2026-07-16, plain client 200). HTML, not a feed — hence a hand-written
 * collector beside the curated feed rows.
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
public final class TelegramChannelSource extends AbstractWebSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramChannelSource.class);

    /**
     * Publisher push channels as the starting set, re-probed live 2026-07-16
     * across ~35 finance candidates (most have the preview opted out):
     * finanzen.net's headline blaster (DE, minutes-fresh), the MarketTwits
     * macro feed, WalterBloomberg (the DeItaone market wire mirror,
     * minutes-fresh) and Watcher.Guru (breaking markets/crypto).
     * GodmodeTrader was dropped — its channel went silent in 2022.
     * A curated START list is wiring, not editorial curation: the set is
     * injectable via the channel-list constructor for tests and expansion.
     */
    static final List<String> DEFAULT_CHANNELS =
            List.of("finanzen_net", "markettwits", "WalterBloomberg", "watcherguru");

    private static final String PREVIEW_URL = "https://t.me/s/";
    private static final int TITLE_MAX = 120;

    /** The stable message id: {@code data-post="channel/12345"}. */
    private static final Pattern DATA_POST =
            Pattern.compile("data-post=\"([A-Za-z0-9_]+/\\d+)\"");

    /** ISO timestamp of the message: {@code <time datetime="...">}. */
    private static final Pattern TIME_DATETIME =
            Pattern.compile("<time datetime=\"([^\"]+)\"");

    /** The message body HTML — inline elements only, never a nested div. */
    private static final Pattern MESSAGE_TEXT = Pattern.compile(
            "class=\"tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final List<String> channels;

    /** Channels whose preview is disabled — dead for the session, never refetched. */
    private final Set<String> deadChannels = ConcurrentHashMap.newKeySet();

    @Inject
    public TelegramChannelSource(WebFetcher fetcher) {
        this(fetcher, DEFAULT_CHANNELS);
    }

    /** Injectable channel list for tests and expansion. */
    TelegramChannelSource(WebFetcher fetcher, List<String> channels) {
        super(fetcher);
        this.channels = List.copyOf(channels);
    }

    @Override
    public String sourceName() {
        return "telegram";
    }

    /**
     * Direct-first: {@code t.me/s/} answers a bare client with no wall
     * (live-probed 2026-07-16).
     */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** A minutes-fresh rumor wire — fast cadence. */
    @Override
    public FetchInterval interval() {
        return FetchInterval.of(5, 10);
    }

    @Override
    public List<Article> collect() {
        List<Article> items = new ArrayList<>();
        for (String channel : channels) {
            if (deadChannels.contains(channel)) continue;
            if (hostCoolingDown(PREVIEW_URL + channel)) continue;
            items.addAll(fetchChannel(channel));
        }
        return items;
    }

    /**
     * One channel's preview page → its messages. A healthy 200 WITHOUT
     * message blocks is the preview opt-out (the followed redirect landed on
     * the profile card) — that channel is dead for the session. Transport
     * failures stay transient.
     */
    private List<Article> fetchChannel(String channel) {
        try {
            WebResponse resp = get(PREVIEW_URL + channel,
                    Map.of("Accept", "text/html,application/xhtml+xml"), requestTimeout);
            if (resp.status() == 200) {
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
            LOG.debug("Telegram channel {} answered status {}", channel, resp.status());
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
     * One preview page → {@link Article}s, oldest-first as served.
     * Regex-scoped per {@code data-post} block (house style for HTML),
     * garbage-tolerant: a torn or alien body yields what it can, never
     * throws. Media-only messages (no text block) are skipped.
     * Package-private for tests.
     */
    static List<Article> parse(String channel, String html) {
        if (html == null || html.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
        try {
            Matcher posts = DATA_POST.matcher(html);
            record PostAt(int start, String dataPost) {}
            List<PostAt> found = new ArrayList<>();
            while (posts.find()) found.add(new PostAt(posts.start(), posts.group(1)));
            for (int i = 0; i < found.size(); i++) {
                int end = i + 1 < found.size() ? found.get(i + 1).start() : html.length();
                String block = html.substring(found.get(i).start(), end);
                Article item = toItem(channel, found.get(i).dataPost(), block);
                if (item != null) out.add(item);
            }
        } catch (Exception e) {
            LOG.debug("Telegram preview parse failed for {}: {}", channel, e.getMessage());
        }
        return out;
    }

    /** One message block → an {@link Article}, or null for media-only posts. */
    private static Article toItem(String channel, String dataPost, String block) {
        Matcher tm = MESSAGE_TEXT.matcher(block);
        if (!tm.find()) return null; // media-only post: nothing to report
        String text = stripHtml(tm.group(1));
        if (text == null || text.isBlank()) return null;
        Matcher time = TIME_DATETIME.matcher(block);
        return new Article(
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
}
