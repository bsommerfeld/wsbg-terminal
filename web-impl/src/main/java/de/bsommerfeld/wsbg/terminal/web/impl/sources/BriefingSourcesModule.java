package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing.EqsNewsArchiveClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing.InvestegateRnsClient;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;

/**
 * The briefing module's two real SOURCES in the new world: the EQS disclosure
 * archive (ISIN-addressed instrument fan) and the Investegate/RNS index (the
 * UK regulatory wire, collected on its own cadence). Every other briefing
 * client keeps its own record API and is injected directly by its consumers -
 * only these two speak the pool/gateway contracts.
 */
public final class BriefingSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder.newSetBinder(binder(), InstrumentSource.class)
                .addBinding().to(EqsNewsArchiveClient.class);
        Multibinder.newSetBinder(binder(), CollectorSource.class)
                .addBinding().to(InvestegateRnsClient.class);
        Multibinder.newSetBinder(binder(), ArchiveSource.class)
                .addBinding().to(EqsNewsArchiveClient.class);
    }
}
