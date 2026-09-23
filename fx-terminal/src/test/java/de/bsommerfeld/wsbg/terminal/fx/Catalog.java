package de.bsommerfeld.wsbg.terminal.fx;

import java.io.IOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Every concrete top-level {@link FxmlNode}: what {@link CatalogTest} walks over. Read from
 * the module when running on the module path, from the code source directory
 * or jar otherwise.
 */
final class Catalog {

    private static final String CLASS_SUFFIX = ".class";

    private Catalog() {
    }

    static List<Class<? extends FxmlNode<?>>> all() throws IOException {
        Module module = FxmlNode.class.getModule();
        List<String> entries = module.isNamed() ? moduleEntries(module) : codeSourceEntries();
        return entries.stream()
                .filter(entry -> entry.endsWith(CLASS_SUFFIX))
                .map(Catalog::className)
                .filter(Catalog::isTopLevel)
                .sorted()
                .map(Catalog::load)
                .filter(Catalog::isConcreteNode)
                .<Class<? extends FxmlNode<?>>>map(Catalog::asNode)
                .toList();
    }

    private static List<String> moduleEntries(Module module) throws IOException {
        ModuleReference reference = module.getLayer().configuration()
                .findModule(module.getName()).orElseThrow().reference();
        try (ModuleReader reader = reference.open(); Stream<String> entries = reader.list()) {
            return entries.toList();
        }
    }

    private static List<String> codeSourceEntries() throws IOException {
        Path root;
        try {
            root = Path.of(FxmlNode.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                return files.filter(Files::isRegularFile).map(file -> root.relativize(file).toString()).toList();
            }
        }
        try (JarFile jar = new JarFile(root.toFile())) {
            return jar.stream().map(JarEntry::getName).toList();
        }
    }

    private static String className(String entry) {
        return entry.substring(0, entry.length() - CLASS_SUFFIX.length()).replace('/', '.').replace('\\', '.');
    }

    private static boolean isTopLevel(String className) {
        return !className.equals("module-info") && !className.endsWith(".package-info") && !className.contains("$");
    }

    private static boolean isConcreteNode(Class<?> type) {
        return type != null
                && FxmlNode.class.isAssignableFrom(type)
                && !java.lang.reflect.Modifier.isAbstract(type.getModifiers());
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, Catalog.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends FxmlNode<?>> asNode(Class<?> type) {
        return (Class<? extends FxmlNode<?>>) type;
    }
}
