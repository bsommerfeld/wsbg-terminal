package de.bsommerfeld.signals.processor;

import de.bsommerfeld.signals.Signal;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the {@code @Signal} methods, groups them by declaring class, checks
 * each class against the {@link SignalRules} and writes its
 * {@link SignalsSource}.
 */
public final class SignalProcessor extends AbstractProcessor {

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(Signal.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (TypeElement annotation : annotations) {
            for (SignalOwner owner : owners(round.getElementsAnnotatedWith(annotation))) {
                if (SignalRules.check(owner, processingEnv.getMessager())) {
                    write(owner);
                }
            }
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
                .map(entry -> SignalOwner.of(entry.getKey(),
                        processingEnv.getElementUtils().getPackageOf(entry.getKey()), entry.getValue()))
                .toList();
    }

    private void write(SignalOwner owner) {
        String source = new SignalsSource(owner, processingEnv.getTypeUtils()).render();
        try (Writer writer = processingEnv.getFiler()
                .createSourceFile(owner.generatedQualifiedName(), owner.type()).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "could not write " + owner.generatedQualifiedName() + ": " + e.getMessage(), owner.type());
        }
    }
}
