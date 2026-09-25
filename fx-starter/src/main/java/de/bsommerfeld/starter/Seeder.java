package de.bsommerfeld.starter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Lays a bundled install out into the data directory - on the first start,
 * and again whenever a new installer brought a seed this install has not
 * taken yet.
 *
 * <h3>The seed version decides, not the install's</h3>
 * After an install has been updated, its own {@code version.txt} is newer
 * than the seed it came from, and that must not bring the seed back. So the
 * seed's version is recorded apart from the install's, in {@code seed.txt},
 * and a seed is laid out when its version differs from that record - which
 * happens exactly when a different installer has run since. A reinstall of
 * an older installer therefore wins too: installing is the user's explicit
 * choice, and the next update check brings the install forward again.
 *
 * <h3>A directory or a zip</h3>
 * A seed is the install's tree, as a directory or zipped. A bundle ships it
 * zipped: native packagers put every jar they find in the bundle on the
 * launcher's class path, and the seed's jars must not end up there.
 *
 * <h3>Replaced whole</h3>
 * The install is replaced, not merged: a jar left over from another version
 * would sit in the module directory next to its successor, and two modules of
 * one name refuse to boot. The seed is copied beside the install first and
 * swapped in afterwards, so a copy that fails leaves the old install as it
 * was.
 */
public final class Seeder {

    /** The version a seed or an install carries - the file TinyUpdate records its release tag in. */
    static final String VERSION_FILE = "version.txt";

    /** The version of the seed an install was last laid out from. */
    static final String SEED_FILE = "seed.txt";

    private Seeder() {
    }

    /**
     * Lays the seed of {@code install} out when it is due: the directory
     * {@code <seeds>/<name>}, else the zip {@code <seeds>/<name>.zip}, named
     * as the install is.
     *
     * @return whether the install was laid out; {@code false} when there is
     *         no such seed, or the install already took this one
     */
    public static boolean seed(Path seeds, Path install) throws IOException {
        String name = install.getFileName().toString();
        Path directory = seeds.resolve(name);
        if (Files.isDirectory(directory)) {
            return layOut(directory, install);
        }
        Path zip = seeds.resolve(name + ".zip");
        if (Files.isRegularFile(zip)) {
            try (FileSystem archive = FileSystems.newFileSystem(zip)) {
                return layOut(archive.getPath("/"), install);
            }
        }
        return false;
    }

    private static boolean layOut(Path seed, Path install) throws IOException {
        String seedVersion = Objects.requireNonNullElse(read(seed.resolve(VERSION_FILE)), "");
        boolean installed = Files.isRegularFile(install.resolve(VERSION_FILE));
        if (installed && seedVersion.equals(read(install.resolve(SEED_FILE)))) {
            return false;
        }

        Path staging = install.resolveSibling(install.getFileName() + ".seeding");
        deleteTree(staging);
        copyTree(seed, staging);
        Files.writeString(staging.resolve(SEED_FILE), seedVersion);

        deleteTree(install);
        try {
            Files.move(staging, install, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, install);
        }
        return true;
    }

    private static String read(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file).strip() : null;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
