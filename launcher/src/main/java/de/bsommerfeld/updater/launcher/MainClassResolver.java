package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Discovers the application entry point by scanning JAR manifests in
 * {@code lib/}. Keeps the launcher decoupled from the terminal module's class
 * name: as long as the terminal JAR declares its {@code Main-Class} in the
 * manifest (Maven's jar-plugin does this via {@code <mainClass>}), the launcher
 * finds it. The terminal JAR is preferred over third-party JARs that may also
 * declare a {@code Main-Class} (e.g. opennlp), and known third-party CLI entry
 * points (those not under the {@code .wsbg.} package) are ignored.
 */
final class MainClassResolver {

    private MainClassResolver() {
    }

    /**
     * @throws IOException if no JAR with a matching Main-Class manifest entry
     *                     is found
     */
    static String resolve(Path libDir) throws IOException {
        try (Stream<Path> jars = Files.list(libDir)) {
            List<Path> candidates = jars
                    .filter(p -> p.toString().endsWith(".jar"))
                    .toList();

            // Prefer our terminal jar over third-party jars that might have a Main-Class
            // (like opennlp)
            List<Path> orderedCandidates = new ArrayList<>();
            for (Path jar : candidates) {
                if (jar.getFileName().toString().startsWith("terminal-")) {
                    orderedCandidates.add(0, jar);
                } else {
                    orderedCandidates.add(jar);
                }
            }

            for (Path jar : orderedCandidates) {
                String mainClass = readMainClassFromManifest(jar);
                if (mainClass != null) {
                    // Ignore known third-party CLI entry points
                    if (!mainClass.contains(".wsbg.")) {
                        continue;
                    }
                    return mainClass;
                }
            }
        }

        throw new IOException("CORRUPT INSTALLATION: No JAR in lib/ declares a Main-Class. "
                + "The update may have delivered incomplete artifacts.");
    }

    /**
     * Reads the {@code Main-Class} attribute from a JAR's manifest. Returns
     * {@code null} on failure.
     */
    private static String readMainClassFromManifest(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null)
                return null;
            return manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            return null;
        }
    }
}
