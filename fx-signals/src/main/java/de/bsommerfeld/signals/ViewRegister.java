package de.bsommerfeld.signals;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one place that knows the views and what their signals mean. The views
 * know nothing of each other: a view calls its {@link Signal @Signal} method,
 * the call comes up here, and the wiring decides which view is shown next and
 * what it is handed.
 *
 * <pre>{@code
 * import static de.bsommerfeld.wsbg.terminal.Signals.signal;
 *
 * @Override
 * public void init() {
 *     register(DashboardView.class);
 *     register(HeadlineCheckView.class);
 *
 *     wire(signal(DashboardView::new).checkHeadline())
 *             .to(HeadlineCheckView.class, (view, headline) -> view.inspect(headline));
 * }
 * }</pre>
 *
 * <p>{@code Signals} is generated into the module's root package; its
 * {@code signal(View::new)} lists exactly the signals of that view. The
 * constructor reference only selects the view, it is never called.
 *
 * <p>Each registered view is built once, on first use, through the factory -
 * as the generated subclass that reports its signals, if it declares any.
 * Confined to one thread, like the UI it serves.
 *
 * @param <N> what a view is to the host, a node for JavaFX
 */
public abstract class ViewRegister<N> {

    /** The generated class beside a view: {@code a.b.Outer$Inner} → {@code a.b.Outer_InnerSignals}. */
    private static final String GENERATED_SUFFIX = "Signals";
    /** The subclass that reports the calls, nested in the generated class. */
    private static final String EMITTER_NAME = "Emitter";

    private static final ClassValue<Class<?>> IMPLEMENTATIONS = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            return findImplementation(type);
        }
    };

    private final Function<Class<? extends N>, ? extends N> factory;
    private final Consumer<? super N> host;

    private final Map<Class<? extends N>, N> views = new LinkedHashMap<>();
    private final Map<SignalStub<?, ?>, Route> routes = new HashMap<>();

    /**
     * @param factory builds a view from its class - typically the injector
     * @param host    shows a view, replacing the one shown before
     */
    protected ViewRegister(Function<Class<? extends N>, ? extends N> factory, Consumer<? super N> host) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Registers the views and wires their signals. Called once, before the first {@link #show}. */
    public abstract void init();

    /** @throws IllegalStateException if {@code view} is registered already */
    protected final void register(Class<? extends N> view) {
        Objects.requireNonNull(view, "view");
        if (views.containsKey(view)) {
            throw new IllegalStateException(view.getSimpleName() + " is registered already");
        }
        views.put(view, null);
    }

    /**
     * Starts wiring {@code signal}; {@link Wire#to} finishes it.
     *
     * @throws IllegalStateException if the view that declares it is not registered
     */
    protected final <P> Wire<P> wire(SignalStub<? extends N, P> signal) {
        Objects.requireNonNull(signal, "signal");
        requireRegistered(signal.owner(), signal.toString());
        return new Wire<>(signal);
    }

    /** Shows {@code view}, building it first if it has not been shown before. */
    public final void show(Class<? extends N> view) {
        host.accept(view(view));
    }

    private N view(Class<? extends N> type) {
        requireRegistered(type, type.getSimpleName());
        N view = views.get(type);
        if (view == null) {
            view = factory.apply(implementationOf(type));
            if (view instanceof SignalEmitter emitter) {
                emitter.connectSignals(this::dispatch);
            }
            views.put(type, view);
        }
        return view;
    }

    private void dispatch(SignalStub<?, ?> signal, Object payload) {
        Route route = routes.get(signal);
        if (route == null) {
            throw new IllegalStateException(signal + " was sent, but nothing is wired to it");
        }
        switch (signal.type()) {
            case CHANGE_VIEW -> {
                N target = view(route.target);
                route.delivery.accept(target, payload);
                host.accept(target);
            }
            default -> throw new IllegalArgumentException("This is not a valid option" + signal.type());
        }
    }

    private void requireRegistered(Class<?> view, String what) {
        if (!views.containsKey(view)) {
            throw new IllegalStateException(what + ": register(" + view.getSimpleName() + ".class) first");
        }
    }

    /** The class to build for {@code view}: the generated subclass if it declares signals, the view itself otherwise. */
    @SuppressWarnings("unchecked")
    private static <T> Class<? extends T> implementationOf(Class<T> view) {
        return (Class<? extends T>) IMPLEMENTATIONS.get(view);
    }

    private static Class<?> findImplementation(Class<?> type) {
        String packagePrefix = type.getPackageName().isEmpty() ? "" : type.getPackageName() + ".";
        String flatName = type.getName().substring(packagePrefix.length()).replace('$', '_');
        try {
            Class<?> generated = Class.forName(packagePrefix + flatName + GENERATED_SUFFIX + "$" + EMITTER_NAME,
                    false, type.getClassLoader());
            return type.isAssignableFrom(generated) ? generated : type;
        } catch (ClassNotFoundException e) {
            return type;
        }
    }

    /** A signal on its way to a target. */
    public final class Wire<P> {

        private final SignalStub<?, P> signal;

        private Wire(SignalStub<?, P> signal) {
            this.signal = signal;
        }

        /** Shows {@code target} when the signal is sent; the payload is dropped. */
        public void to(Class<? extends N> target) {
            to(target, (view, payload) -> {
            });
        }

        /**
         * Hands the payload to {@code target} through {@code delivery}, then
         * shows it.
         *
         * @throws IllegalStateException if the target is not registered or the
         *                               signal is wired already
         */
        @SuppressWarnings("unchecked")
        public <T extends N> void to(Class<T> target, BiConsumer<? super T, ? super P> delivery) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(delivery, "delivery");
            requireRegistered(target, signal + " → " + target.getSimpleName());
            Route route = new Route(target, (view, payload) -> delivery.accept(target.cast(view), (P) payload));
            if (routes.putIfAbsent(signal, route) != null) {
                throw new IllegalStateException(signal + " is wired already");
            }
        }
    }

    /** Where a wired signal goes, and how its payload gets there. */
    private final class Route {

        private final Class<? extends N> target;
        private final BiConsumer<N, Object> delivery;

        private Route(Class<? extends N> target, BiConsumer<N, Object> delivery) {
            this.target = target;
            this.delivery = delivery;
        }
    }
}
