package de.bsommerfeld.signals.processor;

import de.bsommerfeld.signals.SignalKey;
import de.bsommerfeld.signals.SignalScope;
import de.bsommerfeld.signals.Signals;
import de.bsommerfeld.signals.UnhandledSignalHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalProcessorTest {

    private static final String CALCULATOR = """
            package calc;

            import de.bsommerfeld.signals.Signal;
            import java.util.List;

            public class Calculator {

                public static final List<String> LOG = new java.util.ArrayList<>();

                private final String name;

                @Marker
                public Calculator(@Marker String name) {
                    this.name = name;
                }

                @Signal
                void resultShown(int result) {
                    LOG.add(name + " showed " + result);
                }

                @Signal
                protected void cleared() {
                }
            }
            """;

    private static final String MARKER = """
            package calc;

            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            @Retention(RetentionPolicy.RUNTIME)
            public @interface Marker {
            }
            """;

    @Test
    void theGeneratedSubclassReportsACallAfterTheMethodHasRun() throws Exception {
        Compilation compilation = compileCalculator();
        Class<?> calculator = compilation.load("calc.Calculator");
        Object instance = instantiate(calculator, "tape");
        SignalScope scope = SignalScope.root(UnhandledSignalHandler.failing());
        Signals.connect(instance, scope);

        @SuppressWarnings("unchecked")
        List<String> log = (List<String>) calculator.getField("LOG").get(null);
        SignalKey<Integer> resultShown = key(compilation, "resultShown");
        scope.bind(resultShown, result -> log.add("heard " + result));

        var method = calculator.getDeclaredMethod("resultShown", int.class);
        method.setAccessible(true);
        method.invoke(instance, 42);

        assertEquals(List.of("tape showed 42", "heard 42"), log);
    }

    @Test
    void aSignalWithoutParameterIsAVoidKey() throws Exception {
        Compilation compilation = compileCalculator();
        Object instance = instantiate(compilation.load("calc.Calculator"), "tape");
        SignalScope scope = SignalScope.root(UnhandledSignalHandler.failing());
        Signals.connect(instance, scope);
        List<String> heard = new ArrayList<>();
        SignalKey<Void> cleared = key(compilation, "cleared");
        scope.bind(cleared, () -> heard.add("cleared"));

        var method = instance.getClass().getDeclaredMethod("cleared");
        method.setAccessible(true);
        method.invoke(instance);

        assertEquals(List.of("cleared"), heard);
    }

    @Test
    void theSubclassKeepsTheConstructorsAnnotations() throws Exception {
        Compilation compilation = compileCalculator();
        Class<?> calculator = compilation.load("calc.Calculator");
        @SuppressWarnings("unchecked")
        Class<? extends java.lang.annotation.Annotation> marker =
                (Class<? extends java.lang.annotation.Annotation>) compilation.load("calc.Marker");
        Constructor<?> constructor = Signals.implementationOf(calculator).getDeclaredConstructor(String.class);
        assertNotNull(constructor.getAnnotation(marker));
        assertNotNull(constructor.getParameters()[0].getAnnotation(marker));
    }

    @Test
    void aNestedClassIsFoundUnderItsFlatName() throws Exception {
        Compilation compilation = Compilation.of(Map.of("calc.Outer", """
                package calc;

                public class Outer {
                    public static class Inner {
                        @de.bsommerfeld.signals.Signal void pinged() {}
                    }
                }
                """));
        assertTrue(compilation.succeeded(), compilation.errors().toString());
        Class<?> inner = compilation.load("calc.Outer$Inner");
        assertEquals("calc.Outer_InnerSignals$Emitting", Signals.implementationOf(inner).getName());
    }

    @Test
    void aClassWithoutSignalsGetsNothingGenerated() throws Exception {
        Compilation compilation = Compilation.of(Map.of("calc.Plain", "package calc; public class Plain {}"));
        assertTrue(compilation.succeeded(), compilation.errors().toString());
        Class<?> plain = compilation.load("calc.Plain");
        assertSame(plain, Signals.implementationOf(plain));
    }

    @Test
    void refusesWhatTheGeneratedSubclassCannotBuildOn() {
        assertRefused("public final class Bad { @Signal void a() {} }", "must not be final");
        assertRefused("public abstract class Bad { @Signal void a() {} }", "must not be abstract");
        assertRefused("public interface Bad { @Signal default void a() {} }", "declared in a class");
        assertRefused("public class Bad<T> { @Signal void a() {} }", "must not declare type parameters");
        assertRefused("public class Bad { private Bad() {} @Signal void a() {} }", "constructor that is not private");
        assertRefused("public class Bad { class Inner { @Signal void a() {} } }", "top-level or static nested");
        assertRefused("public class Bad { @Signal private void a() {} }", "must not be private");
        assertRefused("public class Bad { @Signal static void a() {} }", "must not be static");
        assertRefused("public class Bad { @Signal final void a() {} }", "must not be final");
        assertRefused("public class Bad { @Signal int a() { return 0; } }", "returns void");
        assertRefused("public class Bad { @Signal void a(int x, int y) {} }", "at most one value");
        assertRefused("public class Bad { @Signal <T> void a(T t) {} }", "must not declare type parameters");
        assertRefused("public class Bad { @Signal void a() {} @Signal void a(int x) {} }", "a name of its own");
    }

    private static void assertRefused(String body, String expected) {
        Compilation compilation = Compilation.of(Map.of("calc.Bad",
                "package calc;\nimport de.bsommerfeld.signals.Signal;\n" + body));
        assertFalse(compilation.succeeded(), body);
        assertTrue(compilation.errors().stream().anyMatch(error -> error.contains(expected)),
                body + " → " + compilation.errors());
    }

    private static Compilation compileCalculator() {
        Compilation compilation = Compilation.of(Map.of("calc.Calculator", CALCULATOR, "calc.Marker", MARKER));
        assertTrue(compilation.succeeded(), compilation.errors().toString());
        return compilation;
    }

    private static Object instantiate(Class<?> type, String name) throws Exception {
        Constructor<?> constructor = Signals.implementationOf(type).getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name);
    }

    @SuppressWarnings("unchecked")
    private static <P> SignalKey<P> key(Compilation compilation, String signal) throws Exception {
        return (SignalKey<P>) compilation.load("calc.CalculatorSignals").getMethod(signal).invoke(null);
    }
}
