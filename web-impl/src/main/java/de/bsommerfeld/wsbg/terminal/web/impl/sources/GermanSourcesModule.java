package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.boersede.BoerseDeNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.boersenmedien.BoersenmedienNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.finanzennet.FinanzenNetNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.fnnews.FnInstrumentNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.handelsblatt.HandelsblattNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.kapitalmarktexperten.KapitalmarktexpertenClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.sharedeals.SharedealsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tradersunion.TradersUnionNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.wallstreetonline.WsoNewsClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.welt.WeltNewsClient;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;

/**
 * The German press wave of the source migration - the hand-written
 * instrument-addressed sources of the old {@code welt}, {@code handelsblatt},
 * {@code sharedeals}, {@code kapitalmarktexperten}, {@code wallstreet-online}
 * (news search), {@code boerse-de}, {@code boersenmedien}, {@code tradersunion},
 * {@code finanzen-net} and {@code fn-news} modules, registered on the
 * multibinder the gateway fans. The resolvers behind them
 * ({@code BoersenmedienResolver}, {@code FinanzenNetResolver}) are plain
 * injectable singletons and need no binding of their own.
 */
public final class GermanSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<InstrumentSource> instruments =
                Multibinder.newSetBinder(binder(), InstrumentSource.class);
        instruments.addBinding().to(WeltNewsClient.class);
        instruments.addBinding().to(HandelsblattNewsClient.class);
        instruments.addBinding().to(SharedealsClient.class);
        instruments.addBinding().to(KapitalmarktexpertenClient.class);
        instruments.addBinding().to(WsoNewsClient.class);
        instruments.addBinding().to(BoerseDeNewsClient.class);
        instruments.addBinding().to(BoersenmedienNewsClient.class);
        instruments.addBinding().to(TradersUnionNewsClient.class);
        instruments.addBinding().to(FinanzenNetNewsClient.class);
        instruments.addBinding().to(FnInstrumentNewsClient.class);

        Multibinder<ArchiveSource> archives =
                Multibinder.newSetBinder(binder(), ArchiveSource.class);
        archives.addBinding().to(WeltNewsClient.class);
        archives.addBinding().to(HandelsblattNewsClient.class);
        archives.addBinding().to(SharedealsClient.class);
        archives.addBinding().to(KapitalmarktexpertenClient.class);
        archives.addBinding().to(BoerseDeNewsClient.class);
        archives.addBinding().to(BoersenmedienNewsClient.class);
        archives.addBinding().to(TradersUnionNewsClient.class);
    }
}
