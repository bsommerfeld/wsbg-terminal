package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedMonitorService;
import de.bsommerfeld.wsbg.terminal.ui.bridge.DonationStatsPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.EurUsdPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.FearGreedPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.FjNewsPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.HeadlinePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.MarketHoursPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.OsAppearancePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.RedditHealthPublisher;

/**
 * The Java→page publishers. All eager so they subscribe to the event bus / hub
 * before any data flows.
 *
 * <p>Ordering that must be preserved: each {@code *MonitorService} binds before
 * its {@code *Publisher} so the publisher can register its listener against a
 * running poll loop (EurUsd, FearGreed).
 */
final class PublisherModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(HeadlinePublisher.class).asEagerSingleton();
        bind(FjNewsPublisher.class).asEagerSingleton();
        bind(MarketHoursPublisher.class).asEagerSingleton();
        bind(RedditHealthPublisher.class).asEagerSingleton();
        bind(DonationStatsPublisher.class).asEagerSingleton();
        // EurUsdMonitorService must come before EurUsdPublisher so the
        // publisher can register its listener against a running poll loop.
        bind(EurUsdMonitorService.class).asEagerSingleton();
        bind(EurUsdPublisher.class).asEagerSingleton();
        // Same ordering: the monitor's poll loop must exist before the publisher subscribes.
        bind(FearGreedMonitorService.class).asEagerSingleton();
        bind(FearGreedPublisher.class).asEagerSingleton();
        // Pushes the host OS dark/light appearance to the page. Needed because the
        // OSR Chromium can't see the real macOS theme, so the page's matchMedia
        // can't drive "follow system". Eager so it polls + pushes from boot.
        bind(OsAppearancePublisher.class).asEagerSingleton();
    }
}
