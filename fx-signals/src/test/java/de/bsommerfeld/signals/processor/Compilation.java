package de.bsommerfeld.signals.processor;

import de.bsommerfeld.signals.SignalKey;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Compiles sources in memory with the {@link SignalProcessor}, against the
 * runtime the tests run with, into a temporary directory.
 */
final class Compilation {

    private final boolean succeeded;
    private final List<String> errors;
    private final Path output;
    private URLClassLoader loader;

    private Compilation(boolean succeeded, List<String> errors, Path output) {
        this.succeeded = succeeded;
        this.errors = errors;
        this.output = output;
    }

    boolean succeeded() {
        return succeeded;
    }

    List<String> errors() {
        return errors;
    }

    static Compilation of(Map<String, String> sourcesByClassName) {
        try {
            Path output = Files.createTempDirectory("signals");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            List<JavaFileObject> sources = sourcesByClassName.entrySet().stream()
                    .<JavaFileObject>map(entry -> new Source(entry.getKey(), entry.getValue()))
                    .toList();
            List<String> options = List.of(
                    "-classpath", runtimeLocation().toString(),
                    "-d", output.toString(),
                    "-s", output.toString());
            JavaCompiler.CompilationTask task = compiler.getTask(null, null, diagnostics, options, null, sources);
            task.setProcessors(List.of(new SignalProcessor()));
            boolean succeeded = task.call();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(null))
                    .toList();
            return new Compilation(succeeded, errors, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads a compiled class, all of them through one loader; the runtime is shared with the test. */
    Class<?> load(String className) throws Exception {
        if (loader == null) {
            loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, Compilation.class.getClassLoader());
        }
        return Class.forName(className, true, loader);
    }

    private static Path runtimeLocation() {
        try {
            return Path.of(SignalKey.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
