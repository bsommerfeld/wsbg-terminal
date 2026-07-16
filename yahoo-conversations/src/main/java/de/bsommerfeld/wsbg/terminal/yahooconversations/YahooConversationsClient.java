package de.bsommerfeld.wsbg.terminal.yahooconversations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-ticker retail sentiment from Yahoo Finance's comment boards ("Yahoo
 * Conversations", powered by OpenWeb/Spot.IM) — every listed instrument has
 * ONE stable board keyed by its {@code messageBoardId} ({@code finmb_<n>}),
 * including the German venue listings ({@code SAP.DE}, {@code RHM.DE} carry
 * their own boards), so this leg speaks for the papers Stocktwits' US-only
 * gate skips.
 *
 * <p><b>Two-step addressing (both steps pinned live 2026-07-16):</b>
 * <ol>
 *   <li>the quote page carries the board id as ESCAPED JSON inside its
 *       bootstrap script ({@code \"messageBoardId\":\"finmb_24937\"}) — one
 *       browser-first page fetch per symbol, cached long (board ids are
 *       stable identity, not data);</li>
 *   <li>the conversation itself answers ONLY to the OpenWeb widget handshake
 *       (plain-HTTP POSTs 403 at the AWS edge) — that runs behind the
 *       {@code @OpenWebConversations} fetcher, which executes the handshake
 *       as page-context calls in a hidden browser tab and hands back the
 *       conversation JSON. When the browser runtime is off, that binding
 *       answers a transport failure and this source yields empty.</li>
 * </ol>
 *
 * <p><b>Shape caveat:</b> the conversation JSON cannot be pinned from
 * outside the widget (the wall blocks bare probes, archives carry no POST
 * answers) — the parser follows OpenWeb's publicly documented conversation
 * shape ({@code conversation.comments[]} with {@code content[].text},
 * {@code written_at} epoch seconds, {@code user_id} into the
 * {@code conversation.users} map) and is tolerant to the point of yielding
 * empty on anything else. Re-pin against the first live answer (user GO
 * 2026-07-16: build without tests, validate live).
 */
@Singleton
public class YahooConversationsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(YahooConversationsClient.class);

    /** Yahoo's OpenWeb spot id (pinned live 2026-07-16 from the loaded widget). */
    static final String SPOT_ID = "sp_Rba9aFpG";

    private static final String QUOTE_URL_PREFIX = "https://finance.yahoo.com/quote/";
    private static final String READ_URL_PREFIX =
            "https://api-2-0.spot.im/v1.0.0/conversation/read?spotId=";
    /** Board ids are stable identity — remember them long, refetch rarely. */
    private static final Duration BOARD_ID_TTL = Duration.ofHours(24);
    private static final Duration CONVERSATION_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int TITLE_MAX = 120;

    /** The board id as the quote page carries it: escaped OR plain JSON. */
    private static final Pattern BOARD_ID = Pattern.compile(
            "messageBoardId\\\\?\"\\s*:\\s*\\\\?\"(finmb_\\d+)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher pageFetcher;
    private final WebFetcher conversationFetcher;
    private final Duration requestTimeout = Duration.ofSeconds(25);

    private final Map<String, CachedBoardId> boardIds = lru();
    private final Map<String, CachedConversation> conversations = lru();

    private record CachedBoardId(Instant fetchedAt, String boardId) {}

    private record CachedConversation(Instant fetchedAt, List<RawNewsItem> items) {}

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        };
    }

    /** Test/default: plain direct transport on both legs. */
    public YahooConversationsClient() {
        this(new DirectWebFetcher(), new DirectWebFetcher());
    }

    /**
     * Production: the quote page rides the standard browser-first chain; the
     * conversation rides the purpose-built OpenWeb handshake fetcher.
     */
    @Inject
    public YahooConversationsClient(
            WebFetcher pageFetcher,
            @de.bsommerfeld.wsbg.terminal.source.net.OpenWebConversations
                    WebFetcher conversationFetcher) {
        this.pageFetcher = pageFetcher;
        this.conversationFetcher = conversationFetcher;
    }

    @Override
    public String sourceName() {
        return "yahoo-conversations";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        String sym = symbol.strip().toUpperCase(Locale.ROOT);
        String boardId = boardIdFor(sym);
        if (boardId == null) return List.of();
        List<RawNewsItem> items = conversationFor(sym, boardId);
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** No-op: the venue is symbol-keyed via the quote page. */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        return List.of();
    }

    /** No-op: the board key comes from the quote page, not an ISIN surface. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    /** The symbol's stable board id from its quote page, cached long; null = no board. */
    private String boardIdFor(String sym) {
        synchronized (boardIds) {
            CachedBoardId cached = boardIds.get(sym);
            if (cached != null && cached.fetchedAt().plus(BOARD_ID_TTL).isAfter(Instant.now())) {
                return cached.boardId();
            }
        }
        String boardId = null;
        try {
            WebResponse resp = pageFetcher.fetch(QUOTE_URL_PREFIX + sym + "/",
                    Map.of("User-Agent", userAgent, "Accept", "text/html"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                boardId = extractBoardId(resp.body());
            } else if (resp == null || resp.status() != 404) {
                return null; // transport trouble — never cache the miss
            }
        } catch (Exception e) {
            LOG.debug("Yahoo quote page for {} failed: {}", sym, e.getMessage());
            return null;
        }
        // A definitive answer without a board id (404, or a page that simply
        // carries none) IS cacheable — the board doesn't exist.
        synchronized (boardIds) {
            boardIds.put(sym, new CachedBoardId(Instant.now(), boardId));
        }
        return boardId;
    }

    /** The escaped-or-plain {@code messageBoardId} from the quote page, or null. */
    static String extractBoardId(String html) {
        if (html == null) return null;
        Matcher m = BOARD_ID.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private List<RawNewsItem> conversationFor(String sym, String boardId) {
        synchronized (conversations) {
            CachedConversation cached = conversations.get(boardId);
            if (cached != null
                    && cached.fetchedAt().plus(CONVERSATION_TTL).isAfter(Instant.now())) {
                return cached.items();
            }
        }
        List<RawNewsItem> items;
        try {
            WebResponse resp = conversationFetcher.fetch(
                    READ_URL_PREFIX + SPOT_ID + "&postId=" + boardId,
                    Map.of(), requestTimeout);
            if (resp == null || resp.status() != 200) {
                return stale(boardId);
            }
            items = parse(resp.body(), sym);
            if (items == null) {
                LOG.debug("Yahoo conversation {} answered a 200 that is not the "
                        + "conversation JSON — treating as a miss, not caching", boardId);
                return stale(boardId);
            }
        } catch (Exception e) {
            LOG.debug("Yahoo conversation {} failed: {}", boardId, e.getMessage());
            return stale(boardId);
        }
        synchronized (conversations) {
            conversations.put(boardId, new CachedConversation(Instant.now(), items));
        }
        return items;
    }

    private List<RawNewsItem> stale(String boardId) {
        synchronized (conversations) {
            CachedConversation cached = conversations.get(boardId);
            return cached == null ? List.of() : cached.items();
        }
    }

    /**
     * Conversation JSON → items (OpenWeb's documented shape, see class doc).
     * Null when the body is not a conversation (never cached); an empty
     * comment list is a valid empty. Package-private for the live re-pin.
     */
    static List<RawNewsItem> parse(String json, String sym) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode conversation = root.path("conversation");
            JsonNode comments = conversation.path("comments");
            if (!comments.isArray()) return null;
            JsonNode users = conversation.path("users");
            List<RawNewsItem> out = new ArrayList<>(comments.size());
            for (JsonNode comment : comments) {
                RawNewsItem item = toItem(comment, users, sym);
                if (item != null) out.add(item);
            }
            return out;
        } catch (Exception e) {
            LOG.debug("Yahoo conversation parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** One comment node → an item, or null when unusable. */
    private static RawNewsItem toItem(JsonNode comment, JsonNode users, String sym) {
        String id = comment.path("id").asText("");
        StringBuilder text = new StringBuilder();
        for (JsonNode content : comment.path("content")) {
            if ("text".equals(content.path("type").asText(""))) {
                if (text.length() > 0) text.append(' ');
                text.append(content.path("text").asText(""));
            }
        }
        String body = text.toString().replaceAll("\\s+", " ").strip();
        if (id.isEmpty() || body.isEmpty()) return null;

        String userId = comment.path("user_id").asText("");
        JsonNode user = users.path(userId);
        String name = user.path("display_name").asText(
                user.path("user_name").asText(""));

        double writtenAt = comment.path("written_at").asDouble(0);
        Instant at = writtenAt > 0 ? Instant.ofEpochSecond((long) writtenAt) : null;

        String title = body.length() <= TITLE_MAX
                ? body : body.substring(0, TITLE_MAX).stripTrailing() + "…";
        return new RawNewsItem(
                "openweb:" + id,
                title,
                name.isBlank() ? "Yahoo Conversations" : "Yahoo Conversations (" + name + ")",
                QUOTE_URL_PREFIX + sym + "/",
                at,
                List.of(),
                null,
                body,
                false);
    }
}
