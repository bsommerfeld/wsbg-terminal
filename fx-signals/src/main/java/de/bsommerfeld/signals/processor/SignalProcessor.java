package de.bsommerfeld.signals.processor;

import de.bsommerfeld.signals.Signal;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the {@code @Signal} methods, groups them by view, checks each view
 * against the {@link SignalRules} and writes its {@link SignalsSource}; one
 * round later, the {@link EntrySource} over all of them.
 *
 * <p>{@code Signals} goes into the package named like the module, or into the
 * package the option {@code -Asignals.package=...} names. A compiler that
 * recompiles only the changed files - an IDE - hands over only some of the
 * views; the views of earlier compilations are kept in an index beside the
 * classes and merged in, as long as they still declare signals.
 */
public final class SignalProcessor extends AbstractProcessor {

    static final String PACKAGE_OPTION = "signals.package";
    private static final String INDEX = "META-INF/de.bsommerfeld.signals/views";

    /** View → its generated class, both qualified. */
    private final Map<String, String> generated = new TreeMap<>();
    private ModuleElement module;
    private boolean entryWritten;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(Signal.class.getName());
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(PACKAGE_OPTION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        List<SignalOwner> owners = owners(round.getElementsAnnotatedWith(Signal.class));
        for (SignalOwner owner : owners) {
            module = processingEnv.getElementUtils().getModuleOf(owner.type());
            if (SignalRules.check(owner, processingEnv.getMessager())) {
                write(owner.generatedQualifiedName(), new SignalsSource(owner, processingEnv.getTypeUtils()).render(),
                        owner.type());
                generated.put(owner.type().getQualifiedName().toString(), owner.generatedQualifiedName());
            }
        }
        // The round after the views: their generated classes exist by then.
        if (owners.isEmpty() && !generated.isEmpty() && !entryWritten) {
            entryWritten = true;
            writeEntry();
        }
        return true;
    }

    private List<SignalOwner> owners(Set<? extends Element> signals) {
        Map<TypeElement, List<ExecutableElement>> byOwner = new LinkedHashMap<>();
        for (Element signal : signals) {
            TypeElement type = (TypeElement) signal.getEnclosingElement();
            byOwner.computeIfAbsent(type, ignored -> new ArrayList<>()).add((ExecutableElement) signal);
        }
        return byOwner.entrySet().stream()
                .map(entry -> owner(entry.getKey(), entry.getValue()))
                .toList();
    }

    private SignalOwner owner(TypeElement type, List<ExecutableElement> signals) {
        return SignalOwner.of(type, processingEnv.getElementUtils().getPackageOf(type), signals);
    }

    private void writeEntry() {
        String packageName = entryPackage();
        if (packageName == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "signals: no package for "
                    + EntrySource.SIMPLE_NAME + " - compile as a named module or pass -A" + PACKAGE_OPTION + "=...");
            return;
        }
        Map<String, String> views = new TreeMap<>(earlierViews());
        views.putAll(generated);
        EntrySource entry = new EntrySource(packageName, views.values());
        write(entry.qualifiedName(), entry.render());
        writeIndex(views.keySet());
    }

    private String entryPackage() {
        String option = processingEnv.getOptions().get(PACKAGE_OPTION);
        if (option != null && !option.isBlank()) {
            return option.strip();
        }
        return module != null && !module.isUnnamed() ? module.getQualifiedName().toString() : null;
    }

    /** The views of earlier compilations that still declare signals and still have their generated class. */
    private Map<String, String> earlierViews() {
        Map<String, String> views = new TreeMap<>();
        for (String name : readIndex()) {
            TypeElement type = typeElement(name);
            if (type == null || generated.containsKey(name)) {
                continue;
            }
            List<ExecutableElement> signals = ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                    .filter(method -> method.getAnnotation(Signal.class) != null)
                    .toList();
            String generatedName = owner(type, signals).generatedQualifiedName();
            if (!signals.isEmpty() && typeElement(generatedName) != null) {
                views.put(name, generatedName);
            }
        }
        return views;
    }

    private TypeElement typeElement(String name) {
        Elements elements = processingEnv.getElementUtils();
        return module != null ? elements.getTypeElement(module, name) : elements.getTypeElement(name);
    }

    private List<String> readIndex() {
        try {
            FileObject index = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", INDEX);
            try (Reader reader = index.openReader(true); BufferedReader lines = new BufferedReader(reader)) {
                return lines.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            }
        } catch (IOException | IllegalArgumentException firstCompilation) {
            return List.of();
        }
    }

    private void writeIndex(Set<String> views) {
        try (Writer writer = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", INDEX).openWriter()) {
            writer.write(String.join("\n", views) + "\n");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "signals: could not write " + INDEX + ": " + e.getMessage());
        }
    }

    private void write(String qualifiedName, String source, Element... originatingElements) {
        try (Writer writer = processingEnv.getFiler()
                .createSourceFile(qualifiedName, originatingElements).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "signals: could not write " + qualifiedName + ": " + e.getMessage());
        }
    }
}
