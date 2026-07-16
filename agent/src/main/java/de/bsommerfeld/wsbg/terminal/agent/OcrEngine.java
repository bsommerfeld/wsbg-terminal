package de.bsommerfeld.wsbg.terminal.agent;

import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mechanical text extraction from Reddit images: Tesseract OCR over a
 * standardised screenshot. Replaces the retired multimodal vision read — the
 * goal is NOT scene understanding but pulling instrument evidence (tickers,
 * company names, WKN/ISIN strings) out of broker/watchlist/article screenshots
 * so the regular text pipeline can match it against the corpus.
 *
 * <p>Binds to a system-installed Tesseract (Homebrew/apt/MSI); {@link #available()}
 * reports whether both the native library and traineddata were found, so callers
 * can degrade to "no image text" instead of failing.
 */
final class OcrEngine {

    private static final Logger LOG = LoggerFactory.getLogger(OcrEngine.class);

    /**
     * The app's OWN Tesseract bundle inside the isolated app-data container
     * ({@code <appData>/tesseract/lib} + {@code /tessdata}), provisioned by the
     * setup scripts exactly like the isolated Ollama. Probed FIRST on both the
     * library and tessdata axes — a system install is only the fallback.
     */
    private static final Path BUNDLE_DIR =
            de.bsommerfeld.wsbg.terminal.core.util.StorageUtils.getAppDataDir().resolve("tesseract");

    /**
     * Install roots of the native Tesseract library, probed in order: the app's
     * own bundle first, then common system prefixes. JNA only searches the
     * system default paths, which on Homebrew (Apple Silicon) and some Linux
     * distros do NOT include the actual install prefix.
     */
    private static final String[] NATIVE_LIB_DIRS = {
            BUNDLE_DIR.resolve("lib").toString(),
            "/opt/homebrew/lib", // Homebrew, Apple Silicon
            "/usr/local/lib",    // Homebrew Intel / manual installs
            "/usr/lib",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
    };

    /** Tessdata locations: own bundle first, then TESSDATA_PREFIX, then system paths. */
    private static final String[] TESSDATA_DIRS = {
            BUNDLE_DIR.resolve("tessdata").toString(),
            "/opt/homebrew/share/tessdata",
            "/usr/local/share/tessdata",
            "/usr/share/tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
    };

    /**
     * Screenshots whose SHORT edge is below this get a 2x upscale before OCR:
     * Tesseract's recogniser is tuned for ~30px glyph heights, while phone-UI
     * text often lands around 12-16px. The short edge is the right yardstick —
     * on a tall 1080x2400 phone screenshot the glyph size follows the width,
     * and gating on the long edge would wrongly skip exactly those.
     */
    private static final int UPSCALE_BELOW = 1600;

    private final Tesseract tesseract;
    private final boolean available;

    OcrEngine() {
        extendJnaLibraryPath();
        String dataPath = resolveTessdata();
        boolean ok = dataPath != null && nativeLibraryPresent();
        Tesseract instance = null;
        if (ok) {
            instance = new Tesseract();
            instance.setDatapath(dataPath);
            // German UI text (umlauts, broker labels) reads clean only with the
            // deu model; ship it in the bundle, degrade to eng-only without it.
            String language = Files.exists(Path.of(dataPath, "deu.traineddata")) ? "deu+eng" : "eng";
            instance.setLanguage(language);
            // Screenshots carry no DPI metadata; without this Tesseract guesses
            // 70dpi and degrades. 144 matches the 2x-upscaled phone-UI reality.
            instance.setVariable("user_defined_dpi", "144");
            LOG.info("OCR ready: tessdata at {} (language: {})", dataPath, language);
        } else {
            LOG.warn("OCR unavailable (tessdata found: {}, native lib found: {}) — image text is skipped",
                    dataPath != null, nativeLibraryPresent());
        }
        this.tesseract = instance;
        this.available = ok;
    }

    /** Whether a usable Tesseract install was found; if false, {@link #read} throws. */
    boolean available() {
        return available;
    }

    /**
     * OCRs a screenshot and returns the raw recognised text (line-preserving).
     * Synchronised: the underlying Tesseract handle is not thread-safe.
     */
    synchronized String read(BufferedImage image) throws Exception {
        if (!available) {
            throw new IllegalStateException("OCR engine is not available on this system");
        }
        return tesseract.doOCR(preprocess(image));
    }

    /**
     * Standardises a screenshot for Tesseract: grayscale, dark-mode inversion
     * (broker apps are mostly light-on-dark, which OCR reads far worse than
     * dark-on-light), a 2x upscale for small phone-UI glyphs, and — for inverted
     * screens only — a hard binarisation. Dark-mode UIs love muted-gray text on
     * near-black, which lands below Tesseract's global Otsu threshold and simply
     * vanishes (live case: an instrument name lost while the prices around it
     * read fine). Binarising at {@link #BINARIZE_THRESHOLD} rescues it. Light
     * images stay un-thresholded: classic dark-on-light is Tesseract's home
     * turf, and a hard threshold would erase text on mid-tone photo areas.
     */
    BufferedImage preprocess(BufferedImage original) {
        BufferedImage gray = toGrayscale(original);
        boolean inverted = meanLuminance(gray) < 128;
        if (inverted) {
            invert(gray);
        }
        if (Math.min(gray.getWidth(), gray.getHeight()) < UPSCALE_BELOW) {
            gray = upscale2x(gray);
        }
        if (inverted) {
            binarize(gray);
        }
        return gray;
    }

    private BufferedImage toGrayscale(BufferedImage src) {
        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }

    private double meanLuminance(BufferedImage gray) {
        long sum = 0;
        int w = gray.getWidth(), h = gray.getHeight();
        // Sample on a stride — the mean over every 4th pixel is stable enough
        // for a bright/dark verdict and 16x cheaper on large screenshots.
        int stride = 4, samples = 0;
        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                sum += gray.getRaster().getSample(x, y, 0);
                samples++;
            }
        }
        return samples == 0 ? 255 : (double) sum / samples;
    }

    private void invert(BufferedImage gray) {
        var raster = gray.getRaster();
        int w = gray.getWidth(), h = gray.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setSample(x, y, 0, 255 - raster.getSample(x, y, 0));
            }
        }
    }

    /**
     * Everything darker than this (post-inversion) counts as ink. 200 keeps
     * muted-gray UI text (~105-115 after inversion) while the near-white
     * background and card fills (230+) drop out cleanly.
     */
    private static final int BINARIZE_THRESHOLD = 200;

    private void binarize(BufferedImage gray) {
        var raster = gray.getRaster();
        int w = gray.getWidth(), h = gray.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setSample(x, y, 0, raster.getSample(x, y, 0) < BINARIZE_THRESHOLD ? 0 : 255);
            }
        }
    }

    private BufferedImage upscale2x(BufferedImage src) {
        BufferedImage scaled = new BufferedImage(src.getWidth() * 2, src.getHeight() * 2, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        g.dispose();
        return scaled;
    }

    /**
     * Prepends known install dirs that actually contain a Tesseract library to
     * {@code jna.library.path} so JNA can bind outside its default search set.
     */
    private static void extendJnaLibraryPath() {
        StringBuilder extra = new StringBuilder();
        for (String dir : NATIVE_LIB_DIRS) {
            if (containsTesseractLib(Path.of(dir))) {
                if (extra.length() > 0) extra.append(java.io.File.pathSeparator);
                extra.append(dir);
            }
        }
        if (extra.length() == 0) return;
        String existing = System.getProperty("jna.library.path");
        System.setProperty("jna.library.path",
                existing == null || existing.isBlank() ? extra.toString()
                        : extra + java.io.File.pathSeparator + existing);
    }

    private static boolean nativeLibraryPresent() {
        for (String dir : NATIVE_LIB_DIRS) {
            if (containsTesseractLib(Path.of(dir))) return true;
        }
        return false;
    }

    private static boolean containsTesseractLib(Path dir) {
        return Files.exists(dir.resolve("libtesseract.dylib"))
                || Files.exists(dir.resolve("libtesseract.so"))
                || Files.exists(dir.resolve("libtesseract.so.5"))
                || Files.exists(dir.resolve("tesseract.dll"));
    }

    private static String resolveTessdata() {
        String bundle = TESSDATA_DIRS[0];
        if (Files.exists(Path.of(bundle, "eng.traineddata"))) {
            return bundle;
        }
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && Files.exists(Path.of(env, "eng.traineddata"))) {
            return env;
        }
        for (String dir : TESSDATA_DIRS) {
            if (Files.exists(Path.of(dir, "eng.traineddata"))) {
                return dir;
            }
        }
        return null;
    }
}
