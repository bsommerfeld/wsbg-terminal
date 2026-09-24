package de.bsommerfeld.signals.processor;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles views and a register written the way the application writes them,
 * then sends signals through the result.
 */
class SignalProcessorTest {

    private static final String BOARD = """
            package calc;

            import de.bsommerfeld.signals.Signal;
            import de.bsommerfeld.signals.SignalType;
            import java.util.List;

            public class Board {

                public static final List<String> LOG = new java.util.ArrayList<>();

                @Marker
                public Board() {
                }

                @Signal
                public void picked(String name) {
                    LOG.add("board picked " + name);
                }

                @Signal(SignalType.CHANGE_VIEW)
                protected void cleared() {
                }
            }
            """;

    private static final String DETAIL = """
            package calc;

            import de.bsommerfeld.signals.Signal;

            public class Detail {

                public String shown;

                public void show(String name) {
                    shown = name;
                }

                @Signal
                void closed() {
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

    /** The register, with {@code WIRING} to fill in. */
    private static final String VIEWS = """
            package calc;

            import de.bsommerfeld.signals.ViewRegister;
            import java.util.List;

            import static calc.Signals.signal;

            public class Views extends ViewRegister<Object> {

                public Views(List<Object> shown) {
                    super(type -> {
                        try {
                            var constructor = type.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            return constructor.newInstance();
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }, shown::add);
                }

                @Override
                public void init() {
                    WIRING
                }
            }
            """;

    private static final String WIRING = """
            register(Board.class);
            register(Detail.class);

            wire(signal(Board::new).picked()).to(Detail.class, (detail, name) -> detail.show(name));
            wire(signal(Detail::new).closed()).to(Board.class);
            """;

    private final List<Object> shown = new ArrayList<>();

    @Test
    void aSignalShowsTheWiredViewAndHandsItThePayload() throws Exception {
        Compilation compilation = compile(WIRING);
        Object board = started(compilation);

        compilation.load("calc.Board").getMethod("picked", String.class).invoke(board, "AAPL");

        Object detail = shown.getLast();
        assertEquals("calc.Detail", detail.getClass().getSuperclass().getName());
        assertEquals("AAPL", detail.getClass().getField("shown").get(detail));
        assertEquals(List.of("board picked AAPL"), compilation.load("calc.Board").getField("LOG").get(null));
    }

    @Test
    void aSignalWithoutPayloadOnlyChangesTheView() throws Exception {
        Compilation compilation = compile(WIRING);
        Object board = started(compilation);
        compilation.load("calc.Board").getMethod("picked", String.class).invoke(board, "AAPL");

        var closed = compilation.load("calc.Detail").getDeclaredMethod("closed");
        closed.setAccessible(true);
        closed.invoke(shown.getLast());

        assertSame(board, shown.getLast());
    }

    @Test
    void aSignalNothingIsWiredToFails() throws Exception {
        Compilation compilation = compile(WIRING);
        Object board = started(compilation);
        var cleared = compilation.load("calc.Board").getDeclaredMethod("cleared");
        cleared.setAccessible(true);

        var e = assertThrows(InvocationTargetException.class, () -> cleared.invoke(board));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("Board#cleared"), e.getCause().getMessage());
    }

    @Test
    void signalListsOnlyTheSignalsOfThatView() {
        Compilation compilation = Compilation.of(sources("""
                register(Board.class);
                register(Detail.class);
                wire(signal(Detail::new).picked()).to(Board.class);
                """));
        assertFalse(compilation.succeeded());
        assertTrue(compilation.errors().stream().anyMatch(error -> error.contains("picked")),
                compilation.errors().toString());
    }

    @Test
    void theDeliveryIsTypedByThePayload() {
        Compilation compilation = Compilation.of(sources("""
                register(Board.class);
                register(Detail.class);
                wire(signal(Board::new).picked()).to(Detail.class, (detail, name) -> detail.show(name.length()));
                """));
        assertFalse(compilation.succeeded());
    }

    @Test
    void wiringAViewThatIsNotRegisteredFailsInInit() throws Exception {
        Compilation compilation = compile("""
                register(Board.class);
                wire(signal(Board::new).picked()).to(Detail.class);
                """);
        var e = assertThrows(InvocationTargetException.class, () -> init(compilation));
        assertTrue(e.getCause().getMessage().contains("register(Detail.class) first"), e.getCause().getMessage());
    }

    @Test
    void aSignalIsWiredOnce() throws Exception {
        Compilation compilation = compile(WIRING + "wire(signal(Board::new).picked()).to(Board.class);");
        var e = assertThrows(InvocationTargetException.class, () -> init(compilation));
        assertTrue(e.getCause().getMessage().contains("wired already"), e.getCause().getMessage());
    }

    @Test
    void theEmitterKeepsTheConstructorsAnnotations() throws Exception {
        Compilation compilation = compile(WIRING);
        @SuppressWarnings("unchecked")
        Class<? extends java.lang.annotation.Annotation> marker =
                (Class<? extends java.lang.annotation.Annotation>) compilation.load("calc.Marker");
        Constructor<?> constructor = compilation.load("calc.BoardSignals$Emitter").getDeclaredConstructor();
        assertNotNull(constructor.getAnnotation(marker));
    }

    @Test
    void aNestedViewIsGeneratedUnderItsFlatName() throws Exception {
        Compilation compilation = Compilation.of(Map.of("calc.Outer", """
                package calc;

                public class Outer {
                    public static class Inner {
                        @de.bsommerfeld.signals.Signal void pinged() {}
                    }
                }
                """));
        assertTrue(compilation.succeeded(), compilation.errors().toString());
        assertNotNull(compilation.load("calc.Outer_InnerSignals$Emitter"));
    }

    @Test
    void withoutSignalsNothingIsGenerated() {
        Compilation compilation = Compilation.of(Map.of("calc.Plain", "package calc; public class Plain {}"));
        assertTrue(compilation.succeeded(), compilation.errors().toString());
        assertThrows(ClassNotFoundException.class, () -> compilation.load("calc.Signals"));
    }

    @Test
    void recompilingOneViewKeepsTheOthersInSignals() throws Exception {
        Compilation first = Compilation.of(Map.of("calc.Board", BOARD, "calc.Detail", DETAIL, "calc.Marker", MARKER));
        assertTrue(first.succeeded(), first.errors().toString());

        Compilation second = Compilation.onTopOf(first, Map.of("calc.Board", BOARD));
        assertTrue(second.succeeded(), second.errors().toString());
        String signals = second.generatedSource("calc.Signals");
        assertTrue(signals.contains("calc.BoardSignals signal("), signals);
        assertTrue(signals.contains("calc.DetailSignals signal("), signals);
    }

    @Test
    void refusesWhatTheGeneratedCodeCannotBuildOn() {
        assertRefused("public final class Bad { @Signal void a() {} }", "must not be final");
        assertRefused("public abstract class Bad { @Signal void a() {} }", "must not be abstract");
        assertRefused("class Bad { @Signal void a() {} }", "must be public");
        assertRefused("public interface Bad { @Signal default void a() {} }", "declared in a class");
        assertRefused("public class Bad<T> { @Signal void a() {} }", "must not declare type parameters");
        assertRefused("public class Bad { Bad() {} @Signal void a() {} }", "public constructor");
        assertRefused("public class Bad { public class Inner { @Signal void a() {} } }", "top-level or static nested");
        assertRefused("public class Bad { @Signal private void a() {} }", "must not be private");
        assertRefused("public class Bad { @Signal static void a() {} }", "must not be static");
        assertRefused("public class Bad { @Signal final void a() {} }", "must not be final");
        assertRefused("public class Bad { @Signal int a() { return 0; } }", "returns void");
        assertRefused("public class Bad { @Signal void a(int x, int y) {} }", "at most one value");
        assertRefused("public class Bad { @Signal <T> void a(T t) {} }", "must not declare type parameters");
        assertRefused("public class Bad { @Signal void a() {} @Signal void a(int x) {} }", "a name of its own");
        assertRefused("public class Bad { @Signal protected void finalize() {} }", "method of Object");
    }

    private static void assertRefused(String body, String expected) {
        Compilation compilation = Compilation.of(Map.of("calc.Bad",
                "package calc;\nimport de.bsommerfeld.signals.Signal;\n" + body));
        assertFalse(compilation.succeeded(), body);
        assertTrue(compilation.errors().stream().anyMatch(error -> error.contains(expected)),
                body + " → " + compilation.errors());
    }

    private static Map<String, String> sources(String wiring) {
        Map<String, String> sources = new HashMap<>(Map.of(
                "calc.Board", BOARD, "calc.Detail", DETAIL, "calc.Marker", MARKER));
        sources.put("calc.Views", VIEWS.replace("WIRING", wiring));
        return sources;
    }

    private static Compilation compile(String wiring) {
        Compilation compilation = Compilation.of(sources(wiring));
        assertTrue(compilation.succeeded(), compilation.errors().toString());
        return compilation;
    }

    private Object init(Compilation compilation) throws Exception {
        Object views = compilation.load("calc.Views").getConstructor(List.class).newInstance(shown);
        views.getClass().getMethod("init").invoke(views);
        return views;
    }

    /** Inits the register and shows the board; returns it. */
    private Object started(Compilation compilation) throws Exception {
        Object views = init(compilation);
        views.getClass().getMethod("show", Class.class).invoke(views, compilation.load("calc.Board"));
        return shown.getLast();
    }
}
