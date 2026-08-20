package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.fourchan.FourChanBizSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.lemmy.LemmySource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.reuters.ReutersNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.telegram.TelegramChannelSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;

/**
 * The hand-written collectors of the feeds migration wave — the sources whose
 * upstream format is NOT RSS/Atom (news-sitemap XML, HTML preview scrape,
 * JSON catalogs/listings) and therefore cannot ride a curated
 * {@code sources.csv} row. Every pure feed pool of this wave lives in the CSV
 * instead; the catalog module registers those rows automatically.
 */
public final class FeedSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<CollectorSource> collectors =
                Multibinder.newSetBinder(binder(), CollectorSource.class);
        collectors.addBinding().to(ReutersNewsSource.class);
        collectors.addBinding().to(TelegramChannelSource.class);
        collectors.addBinding().to(FourChanBizSource.class);
        collectors.addBinding().to(LemmySource.class);
    }
}
