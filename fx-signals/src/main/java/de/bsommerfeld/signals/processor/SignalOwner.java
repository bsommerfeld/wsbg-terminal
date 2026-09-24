package de.bsommerfeld.signals.processor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A view that declares signals, with its signal methods in declaration order.
 * Also the naming contract with the runtime ({@code ViewRegister}): the
 * generated class for {@code a.b.Outer.Inner} is {@code a.b.Outer_InnerSignals},
 * its subclass of the view the nested {@code Emitter}.
 *
 * @param packageName empty for the unnamed package
 */
record SignalOwner(TypeElement type, String packageName, List<ExecutableElement> signals) {

    static final String GENERATED_SUFFIX = "Signals";
    static final String EMITTER_NAME = "Emitter";
    /** The functional interface {@code View::new} converts to, nested in the generated class. */
    static final String REFERENCE_NAME = "Ref";

    static SignalOwner of(TypeElement type, PackageElement enclosingPackage, List<ExecutableElement> signals) {
        String packageName = enclosingPackage.isUnnamed() ? "" : enclosingPackage.getQualifiedName().toString();
        return new SignalOwner(type, packageName, List.copyOf(signals));
    }

    /** {@code Outer_InnerSignals}. */
    String generatedSimpleName() {
        String qualified = type.getQualifiedName().toString();
        String nested = packageName.isEmpty() ? qualified : qualified.substring(packageName.length() + 1);
        return nested.replace('.', '_') + GENERATED_SUFFIX;
    }

    String generatedQualifiedName() {
        return packageName.isEmpty() ? generatedSimpleName() : packageName + "." + generatedSimpleName();
    }

    /** The constructors a subclass can call. */
    List<ExecutableElement> inheritableConstructors() {
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .filter(constructor -> !constructor.getModifiers().contains(Modifier.PRIVATE))
                .toList();
    }

    /** The constructor {@code View::new} refers to from outside the package. */
    Optional<ExecutableElement> publicConstructor() {
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
                .findFirst();
    }

    /** {@code checkHeadline} → {@code CHECK_HEADLINE}: the stub's constant in the generated class. */
    static String constantName(ExecutableElement signal) {
        return signal.getSimpleName().toString()
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_")
                .toUpperCase(Locale.ROOT);
    }
}
