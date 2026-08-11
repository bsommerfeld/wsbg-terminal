package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahooconversations.YahooConversationsSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;

/**
 * The OpenWeb handshake group — sources that do NOT ride the house fetcher
 * but a dedicated {@code @Named("openweb")} {@code WebFetcher} the terminal
 * binds at bootstrap (the handshake runs as page-context calls in a hidden
 * browser tab). Installed SEPARATELY from the other source groups, because
 * it presupposes that binding: without a browser runtime the fetcher answers
 * status-0 and the sources stay empty instead of breaking.
 */
public final class OpenWebSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<InstrumentSource> instrumentSources =
                Multibinder.newSetBinder(binder(), InstrumentSource.class);
        instrumentSources.addBinding().to(YahooConversationsSource.class);
    }
}
