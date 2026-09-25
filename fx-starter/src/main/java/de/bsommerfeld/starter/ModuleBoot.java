package de.bsommerfeld.starter;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Boots a modular application from a directory of jars, inside the running
 * JVM: a module layer over every module in the directory, and its main
 * method called on the current thread. The in-process counterpart of
 *
 * <pre>
 * java --module-path lib --add-modules ALL-MODULE-PATH
 *      --enable-native-access=a,b --module m/Main
 * </pre>
 *
 * - without starting a second JVM, which would cost its startup time and,
 * on macOS, show up in the Dock as a second, anonymous application.
 *
 * <p>
 * Every JDK module the application needs has to be in the boot layer the
 * new layer resolves against. With the starter on the class path, run it
 * with {@code --add-modules ALL-SYSTEM}; granting native access onwards
 * needs the starter to have it itself ({@code --enable-native-access=ALL-UNNAMED}).
 */
public final class ModuleBoot {

    private ModuleBoot() {
    }

    /**
     * Resolves every module in {@code modules}, grants native access, and
     * runs the main class. Returns when its main method does.
     *
     * @param modules      the directory holding the application's jars
     * @param mainModule   the module holding the main class
     * @param mainClass    the fully qualified main class
     * @param nativeAccess the modules that get native access
     * @param arguments    the arguments main is called with
     */
    public static void run(Path modules, String mainModule, String mainClass, List<String> nativeAccess,
            String[] arguments) throws Throwable {
        ModuleLayer.Controller controller = layer(modules, nativeAccess);
        ModuleLayer layer = controller.layer();

        /*
         * The context loader is set for the application's sake: libraries
         * that look up resources or services through it (ServiceLoader
         * without a layer, FXML's default loader) would otherwise search the
         * starter's class path and find nothing of the application.
        */
        ClassLoader loader = layer.findLoader(mainModule);
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> type = Class.forName(mainClass, true, loader);

        /*
         * main may be package-private, as the launcher allows it. Reflection
         * reaches it only where the package is open to the caller, so the
         * starter opens the main class's package to itself - that one
         * package, to the starter alone, which is all the java launcher's
         * own access amounts to.
        */
        controller.addOpens(type.getModule(), type.getPackageName(), ModuleBoot.class.getModule());
        Method main = mainMethod(type);
        try {
            main.invoke(null, (Object) arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The layer over every module in {@code modules}, with native access granted. */
    static ModuleLayer.Controller layer(Path modules, List<String> nativeAccess) {
        ModuleFinder finder = ModuleFinder.of(modules);

        /*
         * Every module in the directory is a root - ALL-MODULE-PATH. Jars
         * without a module-info are automatic modules that no module-info
         * requires by name (Guice's own dependencies), so resolving from the
         * main module alone would leave them out.
        */
        Set<String> roots = finder.findAll().stream()
                .map(ModuleReference::descriptor)
                .map(descriptor -> descriptor.name())
                .collect(Collectors.toSet());
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolveAndBind(finder, ModuleFinder.of(), roots);

        /*
         * One loader for all of them, as the application class loader is for
         * a module path: code that assumes its dependencies share its loader
         * (Guice's generated classes, FXML) keeps working.
        */
        ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(
                configuration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader());
        for (String name : nativeAccess) {
            Module module = controller.layer().findModule(name)
                    .orElseThrow(() -> new IllegalArgumentException("No module " + name + " in " + modules));
            controller.enableNativeAccess(module);
        }
        return controller;
    }

    /** {@code static void main(String[])}, public or not. */
    private static Method mainMethod(Class<?> type) throws NoSuchMethodException {
        Method main = type.getDeclaredMethod("main", String[].class);
        if (!Modifier.isStatic(main.getModifiers())) {
            throw new NoSuchMethodException(type.getName() + ".main(String[]) is not static");
        }
        main.setAccessible(true);
        return main;
    }
}
