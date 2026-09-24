```java

import de.bsommerfeld.signals.Signal;

@Signal(SignalType.CHANGE_VIEW) // CHANGE_VIEW is the default, @Signal alone is the same
public void checkHeadline(Headline headline) {
    // do whatever with the headline
}

// in the register, e.g. TerminalViewRegister extends ViewRegister<Node>
import static de.bsommerfeld.wsbg.terminal.Signals.signal; // generated

@Override
public void init() {

    // the views the register may show and build
    register(DashboardView.class);
    register(HeadlineCheckView.class);

    // signal(View::new) lists exactly that view's @Signal methods
    wire(signal(DashboardView::new).checkHeadline())
            .to(HeadlineCheckView.class, (view, headline) -> view.inspect(headline));
}
```
