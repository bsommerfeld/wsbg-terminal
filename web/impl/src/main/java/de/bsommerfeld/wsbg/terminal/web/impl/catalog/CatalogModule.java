package de.bsommerfeld.wsbg.terminal.web.impl.catalog;

import com.google.inject.AbstractModule;
import com.google.inject.Provider;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;

import java.util.List;

/**
 * Turns the curated list into live collectors: one {@link CuratedFeedSource}
 * multibinding per catalog row. Installed beside {@code WebModule}; the rows
 * are read once at wiring time, so a broken catalog fails the boot loudly.
 */
public final class CatalogModule extends AbstractModule {

    @Override
    protected void configure() {
        List<CatalogRow> rows = SourceCatalog.load();
        Multibinder<CollectorSource> collectors =
                Multibinder.newSetBinder(binder(), CollectorSource.class);
        Provider<WebFetcher> fetcher = binder().getProvider(WebFetcher.class);
        for (CatalogRow row : rows) {
            collectors.addBinding().toProvider(new RowProvider(row, fetcher));
        }
    }

    /** Memoizing provider — one source instance per row for the JVM's lifetime. */
    private static final class RowProvider implements Provider<CollectorSource> {
        private final CatalogRow row;
        private final Provider<WebFetcher> fetcher;
        private volatile CuratedFeedSource instance;

        RowProvider(CatalogRow row, Provider<WebFetcher> fetcher) {
            this.row = row;
            this.fetcher = fetcher;
        }

        @Override
        public CollectorSource get() {
            CuratedFeedSource local = instance;
            if (local == null) {
                synchronized (this) {
                    local = instance;
                    if (local == null) {
                        local = new CuratedFeedSource(row, fetcher.get());
                        instance = local;
                    }
                }
            }
            return local;
        }
    }
}
