package de.bsommerfeld.wsbg.terminal.web.impl;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import de.bsommerfeld.wsbg.terminal.web.gateway.WebGateway;
import de.bsommerfeld.wsbg.terminal.web.impl.gateway.FactsSources;
import de.bsommerfeld.wsbg.terminal.web.instrument.InstrumentRegister;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import de.bsommerfeld.wsbg.terminal.web.schedule.CollectorScheduler;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the REAL wiring: {@link WebSourcesModule} plus the two seams the
 * environment contributes ({@link InstrumentRegister}, {@link FactsSources}).
 * The unit suite never builds the injector, so a duplicate binding, a missing
 * {@code @Inject} or a broken catalog row would otherwise first explode at app
 * start — this test makes that a suite failure instead.
 */
class WebSourcesWiringTest {

    private Injector injector() {
        return Guice.createInjector(new WebSourcesModule(), new AbstractModule() {
            @Override
            protected void configure() {
                bind(InstrumentRegister.class)
                        .toInstance(ResolvedInstrument::ofName);
                bind(FactsSources.class).toInstance(FactsSources.NONE);
            }
        });
    }

    @Test
    void theWholeWebWorldWires() {
        Injector injector = injector();
        assertNotNull(injector.getInstance(WebGateway.class));
        assertNotNull(injector.getInstance(ArticlePool.class));
        assertNotNull(injector.getInstance(CollectorScheduler.class));
        assertNotNull(injector.getInstance(WebLifecycle.class));
    }

    @Test
    void everySourceSetPopulatesWithoutDuplicates() {
        Injector injector = injector();
        Set<CollectorSource> collectors =
                injector.getInstance(Key.get(new TypeLiteral<Set<CollectorSource>>() {}));
        Set<SearchEngine> engines =
                injector.getInstance(Key.get(new TypeLiteral<Set<SearchEngine>>() {}));
        Set<InstrumentSource> instruments =
                injector.getInstance(Key.get(new TypeLiteral<Set<InstrumentSource>>() {}));
        Set<ArchiveSource> archives =
                injector.getInstance(Key.get(new TypeLiteral<Set<ArchiveSource>>() {}));

        assertFalse(collectors.isEmpty(), "catalog rows + hand collectors must register");
        assertFalse(engines.isEmpty());
        assertFalse(instruments.isEmpty());
        assertFalse(archives.isEmpty(), "the archive role must have registered doors");

        // Source names are the attribution identity — duplicates would make
        // outcomes and pool attribution ambiguous.
        for (var set : new Set<?>[] {collectors, engines, instruments, archives}) {
            Set<String> names = new HashSet<>();
            for (Object s : set) {
                String name = ((de.bsommerfeld.wsbg.terminal.web.source.WebSource) s).sourceName();
                assertTrue(names.add(name), "duplicate sourceName: " + name);
            }
        }
    }
}
