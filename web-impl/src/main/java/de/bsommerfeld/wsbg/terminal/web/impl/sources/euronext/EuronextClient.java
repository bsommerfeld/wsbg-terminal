package de.bsommerfeld.wsbg.terminal.web.impl.sources.euronext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Euronext website's own chart service
 * ({@code live.euronext.com/en/intraday_chart/getChartData/<ISIN>-<MIC>/<period>},
 * probed 2026-08-05): ONE keyless call answers a listing's full daily price
 * history - {@code max} reaches back to roughly 2001 with venue-native
 * volumes. The catch: the reply is CryptoJS-AES ({@code {"ct","iv","s"}}),
 * AES-256-CBC with the key derived OpenSSL-style (EVP_BytesToKey over MD5,
 * passphrase + hex-decoded salt {@code s}; the derived IV equals the
 * {@code iv} field, verified live - the field is taken when present).
 *
 * <p>The passphrase is a scrape-at-startup secret, NOT a credential: it sits
 * in plain sight as {@code drupalSettings.ajax_secure.kye} in the HTML of
 * every product page. The verified value ships as a fallback constant; when
 * decryption fails the client lazily re-scrapes it once per attempt from the
 * listing's product page, so a server-side rotation heals itself without a
 * release.
 *
 * <p>Euronext identities only: an ISIN-shaped name plus a MIC from the
 * closed venue set - anything else is a network-free no-op, same gate the
 * NYSE client keeps for its tickers.
 */
@Singleton
public class EuronextClient {

    private static final Logger LOG = LoggerFactory.getLogger(EuronextClient.class);

    static final String CHART_URL =
            "https://live.euronext.com/en/intraday_chart/getChartData/%s-%s/%s";
    static final String PRODUCT_URL =
            "https://live.euronext.com/en/product/equities/%s-%s";

    /** Verified 2026-08-05; the live page's {@code kye} may rotate - see class doc. */
    static final String FALLBACK_PASSPHRASE = "24ayqVo7yJma";

    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** ISIN shape: country, nine alphanumerics, check digit - nothing looser. */
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    /** Chart periods are short URL tokens ({@code intraday}, {@code 1M}, {@code max}). */
    private static final Pattern PERIOD = Pattern.compile("[A-Za-z0-9]{1,12}");
    /** The venues Euronext itself runs - the service answers for no others. */
    private static final Set<String> MICS =
            Set.of("XPAR", "XAMS", "XBRU", "XLIS", "XMSM", "XOSL");
    private static final Pattern KYE = Pattern.compile("\"kye\":\"([^\"]+)\"");

    private final WebFetcher fetcher;
    /** {@code max} carries 25 years of bars plus a decrypt - give it room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);
    /** The scrape-at-startup secret, healed lazily on decrypt failure. */
    private volatile String passphrase = FALLBACK_PASSPHRASE;

    @Inject
    public EuronextClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The home tape for one Euronext listing.
     *
     * @param period chart window as the site names it - {@code intraday},
     *        {@code 1M} and {@code max} are the probed set
     * @return the history, or empty when the identity is not Euronext-shaped,
     *         unknown or the endpoint/decryption failed - never {@code null}
     */
    public Optional<EuronextHistory> history(String isin, String mic, String period) {
        if (isin == null || mic == null || period == null) return Optional.empty();
        String i = isin.trim().toUpperCase(Locale.ROOT);
        String m = mic.trim().toUpperCase(Locale.ROOT);
        String p = period.trim();
        if (!ISIN.matcher(i).matches() || !MICS.contains(m)
                || !PERIOD.matcher(p).matches()) {
            return Optional.empty();
        }
        try {
            String body = get(String.format(CHART_URL, i, m, p));
            if (body == null) return Optional.empty();
            Optional<String> plain = decrypt(body, passphrase);
            if (plain.isEmpty()) {
                String fresh = scrapePassphrase(i, m);
                if (fresh != null && !fresh.equals(passphrase)) {
                    LOG.info("[Euronext] kye rotated, re-scraped from product page");
                    passphrase = fresh;
                    plain = decrypt(body, fresh);
                }
            }
            if (plain.isEmpty()) {
                LOG.debug("[Euronext] undecryptable reply for {}-{}", i, m);
                return Optional.empty();
            }
            List<EuronextHistory.EuronextBar> bars = parseBars(plain.get());
            if (bars.isEmpty()) return Optional.empty();
            LOG.info("[Euronext] {}-{} ({}) → {} bars, {} .. {}",
                    i, m, p, bars.size(),
                    bars.get(0).date(), bars.get(bars.size() - 1).date());
            return Optional.of(new EuronextHistory(i, m, p, bars));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Euronext] fetch for {}-{} failed: {}", i, m, e.getMessage());
            return Optional.empty();
        }
    }

    private String get(String url) throws Exception {
        WebResponse resp = fetcher.fetch(url,
                Map.of("Accept", "application/json, text/html"),
                requestTimeout, MODES);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[Euronext] status {} for {}",
                    resp == null ? "null" : resp.status(), url);
            return null;
        }
        return resp.body();
    }

    /** The product page's {@code "kye":"..."} literal, or null. */
    private String scrapePassphrase(String isin, String mic) {
        try {
            String html = get(String.format(PRODUCT_URL, isin, mic));
            if (html == null) return null;
            Matcher m = KYE.matcher(html);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * CryptoJS-AES envelope → plaintext: AES-256-CBC, key + IV derived
     * OpenSSL-style (EVP_BytesToKey, MD5) from passphrase + salt {@code s};
     * the {@code iv} field wins when present. Empty on any failure - a wrong
     * key is a bad-padding error, never an exception to the caller.
     * Package-private for tests.
     */
    static Optional<String> decrypt(String body, String passphrase) {
        if (body == null || body.isBlank() || passphrase == null) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(body);
            String ct = root.path("ct").asText(null);
            String saltHex = root.path("s").asText(null);
            if (ct == null || saltHex == null) return Optional.empty();
            byte[] salt = HexFormat.of().parseHex(saltHex);
            byte[] derived = evpBytesToKey(
                    passphrase.getBytes(StandardCharsets.UTF_8), salt, 48);
            byte[] key = new byte[32];
            byte[] iv = new byte[16];
            System.arraycopy(derived, 0, key, 0, 32);
            System.arraycopy(derived, 32, iv, 0, 16);
            String ivHex = root.path("iv").asText(null);
            if (ivHex != null && ivHex.length() == 32) {
                iv = HexFormat.of().parseHex(ivHex);
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ct));
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * OpenSSL's {@code EVP_BytesToKey} with MD5 and one round: digest chains
     * of {@code D_i = MD5(D_{i-1} || passphrase || salt)} concatenated until
     * {@code bytes} are gathered - the exact derivation CryptoJS ships.
     */
    static byte[] evpBytesToKey(byte[] passphrase, byte[] salt, int bytes)
            throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] out = new byte[bytes];
        byte[] block = new byte[0];
        int filled = 0;
        while (filled < bytes) {
            md5.reset();
            md5.update(block);
            md5.update(passphrase);
            md5.update(salt);
            block = md5.digest();
            int n = Math.min(block.length, bytes - filled);
            System.arraycopy(block, 0, out, filled, n);
            filled += n;
        }
        return out;
    }

    /**
     * The decrypted wire: a plain JSON array of
     * {@code {"time":"yyyy-MM-dd HH:mm","price":<close>,"volume":<v>}} -
     * three fields, nothing else (probed 2026-08-05). Oldest first on the
     * wire; sorted again defensively. Package-private for tests.
     */
    static List<EuronextHistory.EuronextBar> parseBars(String plain) {
        List<EuronextHistory.EuronextBar> bars = new ArrayList<>();
        if (plain == null || plain.isBlank()) return bars;
        try {
            JsonNode root = MAPPER.readTree(plain);
            if (!root.isArray()) return bars;
            for (JsonNode b : root) {
                LocalDate date = barDate(b.path("time").asText(null));
                double close = num(b, "price");
                if (date == null || !Double.isFinite(close) || close <= 0) continue;
                bars.add(new EuronextHistory.EuronextBar(date,
                        Double.NaN, Double.NaN, Double.NaN, close, num(b, "volume")));
            }
            bars.sort((a, b) -> a.date().compareTo(b.date()));
        } catch (Exception e) {
            LOG.warn("[Euronext] unparseable plaintext: {}", e.getMessage());
            bars.clear();
        }
        return bars;
    }

    /** {@code "2026-08-05 02:00"} → the date part, null when unreadable. */
    private static LocalDate barDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    /** A string- or number-typed decimal, {@code NaN} when absent/unreadable. */
    private static double num(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return Double.NaN;
        if (v.isNumber()) return v.asDouble();
        try {
            String s = v.asText("").trim();
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
