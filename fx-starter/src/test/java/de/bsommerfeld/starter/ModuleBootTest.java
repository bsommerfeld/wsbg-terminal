package de.bsommerfeld.starter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ModuleBootTest {

    @TempDir
    Path root;

    @Test
    void bootsANonOpenModule_withPackagePrivateMain_inItsOwnLayer() throws Throwable {
        Path lib = jarDemo();
        ModuleBoot.run(lib, "demo.app", "demo.app.Main", List.of(), new String[]{"a", "b"});
        assertEquals("a,b|demo.app|true", System.getProperty("moduleboot.test"));
    }

    @Test
    void unknownNativeAccessModule_isRefused() throws Exception {
        Path lib = jarDemo();
        assertThrows(IllegalArgumentException.class,
                () -> ModuleBoot.run(lib, "demo.app", "demo.app.Main", List.of("no.such.module"), new String[0]));
    }

    /** Compiles a module that is not open and whose main is package-private, into lib/demo.jar. */
    private Path jarDemo() throws IOException {
        Path sources = root.resolve("src");
        Files.createDirectories(sources.resolve("demo/app"));
        Files.writeString(sources.resolve("module-info.java"), "module demo.app { }");
        Files.writeString(sources.resolve("demo/app/Main.java"), """
                package demo.app;
                class Main {
                    static void main(String[] arguments) {
                        System.setProperty("moduleboot.test", String.join(",", arguments)
                                + "|" + Main.class.getModule().getName()
                                + "|" + (Thread.currentThread().getContextClassLoader() == Main.class.getClassLoader()));
                    }
                }
                """);
        Path classes = root.resolve("classes");
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                sources.resolve("module-info.java").toString(), sources.resolve("demo/app/Main.java").toString());
        assertEquals(0, result);

        Path lib = Files.createDirectories(root.resolve("lib"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(lib.resolve("demo.jar")));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        return lib;
    }
}
