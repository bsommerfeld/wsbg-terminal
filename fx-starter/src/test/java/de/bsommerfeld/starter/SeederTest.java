package de.bsommerfeld.starter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SeederTest {

    @TempDir
    Path root;

    /** A seeds directory with the install "app" as a directory. */
    private Path seed(String version, String jar) throws IOException {
        Path seeds = root.resolve("seeds-" + version);
        Path seed = seeds.resolve("app");
        Files.createDirectories(seed.resolve("lib"));
        Files.writeString(seed.resolve("lib").resolve(jar), jar);
        Files.writeString(seed.resolve("version.txt"), version);
        return seeds;
    }

    /** A seeds directory with the install "app" zipped. */
    private Path zippedSeed(String version, String jar) throws IOException {
        Path seeds = root.resolve("zipped-" + version);
        Files.createDirectories(seeds);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(seeds.resolve("app.zip")))) {
            zip.putNextEntry(new ZipEntry("lib/" + jar));
            zip.write(jar.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("version.txt"));
            zip.write(version.getBytes(StandardCharsets.UTF_8));
        }
        return seeds;
    }

    @Test
    void zippedSeed_isLaidOutAlike() throws IOException {
        Path install = root.resolve("app");
        assertTrue(Seeder.seed(zippedSeed("v1", "app-1.jar"), install));

        assertEquals("app-1.jar", Files.readString(install.resolve("lib/app-1.jar")));
        assertEquals("v1", Files.readString(install.resolve("seed.txt")));
        assertFalse(Seeder.seed(zippedSeed("v1", "app-1.jar"), install));
    }

    @Test
    void firstStart_laysOutTheSeed_andRecordsItsVersion() throws IOException {
        Path install = root.resolve("app");
        assertTrue(Seeder.seed(seed("v1", "app-1.jar"), install));

        assertEquals("app-1.jar", Files.readString(install.resolve("lib/app-1.jar")));
        assertEquals("v1", Files.readString(install.resolve("version.txt")));
        assertEquals("v1", Files.readString(install.resolve("seed.txt")));
        assertFalse(Files.exists(root.resolve("app.seeding")));
    }

    @Test
    void sameSeedAgain_leavesAnUpdatedInstallAlone() throws IOException {
        Path seed = seed("v1", "app-1.jar");
        Path install = root.resolve("app");
        Seeder.seed(seed, install);

        // an update moved the install on to v2
        Files.delete(install.resolve("lib/app-1.jar"));
        Files.writeString(install.resolve("lib/app-2.jar"), "app-2.jar");
        Files.writeString(install.resolve("version.txt"), "v2");

        assertFalse(Seeder.seed(seed, install));
        assertTrue(Files.exists(install.resolve("lib/app-2.jar")));
        assertEquals("v2", Files.readString(install.resolve("version.txt")));
    }

    @Test
    void newSeed_replacesTheInstallWhole() throws IOException {
        Path install = root.resolve("app");
        Seeder.seed(seed("v1", "app-1.jar"), install);
        Files.writeString(install.resolve("lib/stray.jar"), "stray");

        assertTrue(Seeder.seed(seed("v3", "app-3.jar"), install));
        assertFalse(Files.exists(install.resolve("lib/app-1.jar")));
        assertFalse(Files.exists(install.resolve("lib/stray.jar")));
        assertTrue(Files.exists(install.resolve("lib/app-3.jar")));
        assertEquals("v3", Files.readString(install.resolve("seed.txt")));
    }

    @Test
    void installWithoutVersion_isSeededAgain() throws IOException {
        Path seed = seed("v1", "app-1.jar");
        Path install = root.resolve("app");
        Seeder.seed(seed, install);
        Files.delete(install.resolve("version.txt"));

        assertTrue(Seeder.seed(seed, install));
        assertEquals("v1", Files.readString(install.resolve("version.txt")));
    }

    @Test
    void missingSeed_doesNothing() throws IOException {
        assertFalse(Seeder.seed(root.resolve("nothing"), root.resolve("app")));
        assertFalse(Files.exists(root.resolve("app")));
    }
}
