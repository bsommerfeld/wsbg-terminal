package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The company's logo, from the company's OWN website - first-party by mandate:
 * no curated ticker→logo mapping, no third-party logo CDN. The home page the
 * press sweep already fetched carries the icon links
 * ({@code apple-touch-icon}, {@code icon}, {@code mask-icon},
 * {@code og:image}); the largest declared square wins, {@code /favicon.ico}
 * is the last resort.
 *
 * <p>Cached once per ISIN under {@code <appData>/images/logos/<isin>.<ext>}
 * with a TTL - archived reports keep pointing at the same file. Failure is
 * NOT an error: a company without a readable icon simply has no logo on the
 * sheet, and the caller renders a constant-height placeholder.
 *
 * <p>Own {@link HttpClient} for the byte download (the ImageFetcher
 * precedent) - the shared WebFetcher seam carries text only.
 */
final class CompanyLogoFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(CompanyLogoFetcher.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    private static final long MAX_BYTES = 2_000_000;

    private static final Pattern LINK_TAG = Pattern.compile("(?is)<link\\b[^>]*>");
    private static final Pattern META_OG = Pattern.compile(
            "(?is)<meta\\b[^>]*property\\s*=\\s*[\"']og:image[\"'][^>]*>");
    private static final Pattern REL = Pattern.compile(
            "(?is)\\brel\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern HREF = Pattern.compile(
            "(?is)\\bhref\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CONTENT = Pattern.compile(
            "(?is)\\bcontent\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SIZES = Pattern.compile(
            "(?is)\\bsizes\\s*=\\s*[\"'](\\d+)x(\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();
    private final String userAgent;

    CompanyLogoFetcher(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "Mozilla/5.0" : userAgent;
    }

    /** The logo cache directory - shared with the asset server's route. */
    static Path logosDir() {
        return StorageUtils.getAppDataDir().resolve("images").resolve("logos");
    }

    /**
     * The cached logo FILENAME for a key, or empty - no network. The key is
     * the ISIN wherever the run knows one.
     */
    static Optional<String> vorhanden(String key) {
        String safe = safeKey(key);
        if (safe == null) return Optional.empty();
        try {
            Path dir = logosDir();
            if (!Files.isDirectory(dir)) return Optional.empty();
            try (var files = Files.list(dir)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith(safe + "."))
                        .findFirst();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Fetches (or serves from cache) the logo for a company. Returns the
     * FILENAME under the logos directory, or empty on any miss.
     *
     * @param website  the first-party site ({@code profile.website})
     * @param homeHtml the already-fetched home page HTML, or null - a null
     *                 home page limits the search to {@code /favicon.ico}
     * @param key      the cache key, ISIN preferred
     */
    Optional<String> fetch(String website, String homeHtml, String key) {
        String safe = safeKey(key);
        if (safe == null || website == null || website.isBlank()) return Optional.empty();
        try {
            Path dir = logosDir();
            Optional<String> cached = vorhanden(safe);
            if (cached.isPresent()) {
                Path f = dir.resolve(cached.get());
                if (Files.getLastModifiedTime(f).toInstant()
                        .isAfter(Instant.now().minusMillis(TTL_MS))) {
                    return cached;
                }
            }
            URI base = URI.create(website.trim());
            if (base.getScheme() == null) base = URI.create("https://" + website.trim());
            for (String kandidat : kandidaten(homeHtml, base)) {
                byte[] bytes = lade(kandidat);
                String ext = bytes == null ? null : endung(bytes, kandidat);
                if (ext == null) continue;
                Files.createDirectories(dir);
                Path ziel = dir.resolve(safe + "." + ext);
                // A stale cache file under another extension makes way.
                try (var files = Files.list(dir)) {
                    for (Path alt : files.filter(p -> p.getFileName().toString()
                            .startsWith(safe + ".")).toList()) {
                        Files.deleteIfExists(alt);
                    }
                }
                Files.write(ziel, bytes);
                LOG.info("[LOGO] {} <- {} ({} bytes)", ziel.getFileName(), kandidat,
                        bytes.length);
                return Optional.of(ziel.getFileName().toString());
            }
        } catch (Exception e) {
            LOG.debug("[LOGO] fetch for '{}' failed: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Candidate icon URLs from the home HTML, best first: apple-touch-icon by
     * declared size, then icon by declared size, then mask-icon, then
     * og:image, then {@code /favicon.ico}.
     */
    static List<String> kandidaten(String homeHtml, URI base) {
        List<String[]> gewichtet = new ArrayList<>(); // {url, rank, size}
        if (homeHtml != null && !homeHtml.isBlank()) {
            Matcher lm = LINK_TAG.matcher(homeHtml);
            while (lm.find()) {
                String tag = lm.group();
                Matcher rm = REL.matcher(tag);
                if (!rm.find()) continue;
                String rel = rm.group(1).toLowerCase(Locale.ROOT);
                int rang;
                if (rel.contains("apple-touch-icon")) rang = 0;
                else if (rel.contains("mask-icon")) rang = 2;
                else if (rel.equals("icon") || rel.contains("shortcut icon")
                        || rel.equals("shortcut")) rang = 1;
                else continue;
                Matcher hm = HREF.matcher(tag);
                if (!hm.find()) continue;
                Matcher sm = SIZES.matcher(tag);
                int size = sm.find() ? Integer.parseInt(sm.group(1)) : 0;
                gewichtet.add(new String[] {absolut(base, hm.group(1)),
                        String.valueOf(rang), String.valueOf(size)});
            }
            Matcher om = META_OG.matcher(homeHtml);
            if (om.find()) {
                Matcher cm = CONTENT.matcher(om.group());
                if (cm.find()) {
                    gewichtet.add(new String[] {absolut(base, cm.group(1)), "3", "0"});
                }
            }
        }
        gewichtet.sort((a, b) -> {
            int c = Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
            if (c != 0) return c;
            return Integer.compare(Integer.parseInt(b[2]), Integer.parseInt(a[2]));
        });
        List<String> out = new ArrayList<>();
        for (String[] g : gewichtet) {
            if (g[0] != null && !out.contains(g[0])) out.add(g[0]);
        }
        String favicon = absolut(base, "/favicon.ico");
        if (favicon != null && !out.contains(favicon)) out.add(favicon);
        return out;
    }

    private static String absolut(URI base, String href) {
        if (href == null || href.isBlank()) return null;
        try {
            return base.resolve(href.trim()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] lade(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/*,*/*;q=0.5")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            byte[] body = resp.body();
            if (body == null || body.length < 64 || body.length > MAX_BYTES) return null;
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The file extension from the MAGIC BYTES (an anti-bot HTML page behind
     * an icon URL must never be cached as a logo), URL as a hint only for the
     * text-based SVG.
     */
    static String endung(byte[] b, String url) {
        if (b == null || b.length < 8) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "jpg";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P') {
            return "webp";
        }
        if (b[0] == 0 && b[1] == 0 && (b[2] == 1 || b[2] == 2) && b[3] == 0) {
            return "ico";
        }
        String kopf = new String(b, 0, Math.min(b.length, 256),
                java.nio.charset.StandardCharsets.UTF_8).stripLeading()
                .toLowerCase(Locale.ROOT);
        if (kopf.startsWith("<svg") || (kopf.startsWith("<?xml")
                && kopf.contains("<svg"))) {
            return "svg";
        }
        if (kopf.startsWith("gif8")) return "gif";
        return null;
    }

    /** ISIN-shaped keys only - the filename must never carry path characters. */
    private static String safeKey(String key) {
        if (key == null) return null;
        String k = key.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "");
        return k.isEmpty() || k.length() > 40 ? null : k;
    }
}
