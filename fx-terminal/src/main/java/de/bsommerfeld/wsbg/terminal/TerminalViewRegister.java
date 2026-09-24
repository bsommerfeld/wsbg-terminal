package de.bsommerfeld.wsbg.terminal;

import de.bsommerfeld.signals.ViewRegister;
import de.bsommerfeld.wsbg.terminal.dashboard.Dashboard;
import de.bsommerfeld.wsbg.terminal.fx.Fx;
import javafx.scene.Node;

import java.util.function.Consumer;

/**
 * The terminal's views and the signals between them. Views are built by the
 * injector; wiring goes through {@code signal(View::new)} from the generated
 * {@code Signals} in this package, once a view declares a signal.
 */
final class TerminalViewRegister extends ViewRegister<Node> {

    /** @param host shows a view in the window, below the title bar */
    TerminalViewRegister(Consumer<Node> host) {
        super(type -> Fx.injector().getInstance(type), host);
    }

    @Override
    public void init() {
        register(Dashboard.class);
    }
}
