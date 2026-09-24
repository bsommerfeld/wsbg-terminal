package de.bsommerfeld.signals.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Set;

/**
 * What makes a signal declaration generatable: the generated subclass has to
 * be able to extend the view, call a constructor and override every signal
 * method, and {@code signal(View::new)} has to reach the view from the
 * register's package. A new requirement is one more entry in a list.
 */
final class SignalRules {

    /** The stubs are no-argument methods; these names are taken by {@link Object}. */
    private static final Set<String> OBJECT_METHODS =
            Set.of("toString", "hashCode", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    static final List<Rule<ExecutableElement>> METHOD_RULES = List.of(
            Rule.of("a signal must not be private - the generated subclass overrides it",
                    method -> !method.getModifiers().contains(Modifier.PRIVATE)),
            Rule.of("a signal must not be static - the generated subclass overrides it",
                    method -> !method.getModifiers().contains(Modifier.STATIC)),
            Rule.of("a signal must not be final - the generated subclass overrides it",
                    method -> !method.getModifiers().contains(Modifier.FINAL)),
            Rule.of("a signal returns void - it reports that something happened",
                    method -> method.getReturnType().getKind() == TypeKind.VOID),
            Rule.of("a signal carries at most one value - put several into a record",
                    method -> method.getParameters().size() <= 1),
            Rule.of("a signal must not declare type parameters",
                    method -> method.getTypeParameters().isEmpty()),
            Rule.of("a signal must not be named like a method of Object",
                    method -> !OBJECT_METHODS.contains(method.getSimpleName().toString())));

    static final List<Rule<SignalOwner>> OWNER_RULES = List.of(
            Rule.of("signals are declared in a class",
                    owner -> owner.type().getKind() == ElementKind.CLASS),
            Rule.of("a class with signals must be public - the register refers to it",
                    owner -> owner.type().getModifiers().contains(Modifier.PUBLIC)),
            Rule.of("a class with signals must not be final - the generated subclass extends it",
                    owner -> !owner.type().getModifiers().contains(Modifier.FINAL)),
            Rule.of("a class with signals must not be abstract - the generated subclass is instantiated",
                    owner -> !owner.type().getModifiers().contains(Modifier.ABSTRACT)),
            Rule.of("a class with signals is top-level or static nested",
                    owner -> owner.type().getNestingKind() == NestingKind.TOP_LEVEL
                            || owner.type().getNestingKind() == NestingKind.MEMBER
                            && owner.type().getModifiers().contains(Modifier.STATIC)),
            Rule.of("a class with signals must not declare type parameters",
                    owner -> owner.type().getTypeParameters().isEmpty()),
            Rule.of("a class with signals needs a public constructor - signal(View::new) refers to it",
                    owner -> owner.publicConstructor().isPresent()),
            Rule.of("every signal in a class needs a name of its own",
                    owner -> owner.signals().stream().map(SignalOwner::constantName).distinct().count()
                            == owner.signals().size()));

    private SignalRules() {
    }

    /** Reports every broken rule as a compile error; true if there were none. */
    static boolean check(SignalOwner owner, Messager messager) {
        boolean valid = check(OWNER_RULES, owner, owner.type(), messager);
        for (ExecutableElement signal : owner.signals()) {
            valid &= check(METHOD_RULES, signal, signal, messager);
        }
        return valid;
    }

    private static <T> boolean check(List<Rule<T>> rules, T subject, Element at, Messager messager) {
        boolean valid = true;
        for (Rule<T> rule : rules) {
            if (!rule.holds().test(subject)) {
                messager.printMessage(Diagnostic.Kind.ERROR, rule.violation(), at);
                valid = false;
            }
        }
        return valid;
    }
}
