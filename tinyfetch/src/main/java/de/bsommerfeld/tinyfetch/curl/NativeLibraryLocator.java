package de.bsommerfeld.tinyfetch.curl;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds libcurl-impersonate. First hit wins:
 *
 * <ol>
 *   <li>system property {@code tinyfetch.library} - the file itself</li>
 *   <li>system property {@code tinyfetch.library.dir} - a directory holding
 *       the file, directly or under {@code <platform>/} (the layout
 *       {@code .script/natives.sh} writes)</li>
 *   <li>the directory of the TinyFetch jar - where a packaged application
 *       puts it (TinyUpdate's {@code lib/})</li>
 * </ol>
 */
public final class NativeLibraryLocator {

    public static final String LIBRARY_PROPERTY = "tinyfetch.library";
    public static final String LIBRARY_DIR_PROPERTY = "tinyfetch.library.dir";

    private NativeLibraryLocator() {
    }

    /**
     * @throws CurlException naming every place that was searched
     */
    public static Path locate() throws CurlException {
        List<Path> candidates = candidates();
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new CurlException("libcurl-impersonate not found (run .script/natives.sh); searched "
                + candidates, -1);
    }

    static List<Path> candidates() {
        String fileName = NativePlatform.current().libraryFileName();
        List<Path> candidates = new ArrayList<>();

        String explicit = System.getProperty(LIBRARY_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit));
        }

        String directory = System.getProperty(LIBRARY_DIR_PROPERTY);
        if (directory != null && !directory.isBlank()) {
            candidates.add(Path.of(directory, fileName));
            candidates.add(Path.of(directory, NativePlatform.currentId(), fileName));
        }

        jarDirectory().ifPresent(jarDirectory -> candidates.add(jarDirectory.resolve(fileName)));
        return candidates;
    }

    private static Optional<Path> jarDirectory() {
        try {
            CodeSource source = NativeLibraryLocator.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Optional.empty();
            }
            Path location = Path.of(source.getLocation().toURI());
            return Optional.ofNullable(Files.isDirectory(location) ? location : location.getParent());
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        }
    }
}
