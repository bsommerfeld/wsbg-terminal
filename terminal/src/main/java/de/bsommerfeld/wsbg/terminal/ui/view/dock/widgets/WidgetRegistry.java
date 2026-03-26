package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;

import de.bsommerfeld.wsbg.terminal.ui.view.dock.DockWidget;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry for instantiating different DockWidgets via their identifier.
 * Required for parsing the layout state back into actual widget instances.
 */
public class WidgetRegistry {

    private static final Map<String, Supplier<DockWidget>> REGISTRY = new HashMap<>();

    /**
     * Registers a new widget type with a specific identifier.
     * The factory supplier spawns instances.
     */
    public static void register(String identifier, Supplier<DockWidget> factory) {
        REGISTRY.put(identifier, factory);
    }

    /**
     * Creates a DockWidget securely from the registry using its identifier.
     */
    public static DockWidget create(String identifier) {
        Supplier<DockWidget> factory = REGISTRY.get(identifier);
        if (factory != null) {
            return factory.get();
        }
        return null; // or log a warning if an unknown widget layout was found
    }
}
