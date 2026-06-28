package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes AI-generated headlines from {@link AgentRepository} to every
 * connected page. Subscribes to {@link AgentStreamEndEvent} so new
 * headlines appear instantly (no polling), and also sends a snapshot
 * whenever a fresh client connects.
 *
 * <p>
 * A 60-second safety poll catches any edge cases where a headline is
 * recorded without an accompanying stream-end event.
 */
@Singleton
public final class HeadlinePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlinePublisher.class);

    private final AgentRepository repo;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "headline-publisher");
                t.setDaemon(true);
                return t;
            });

    /** Fingerprint of the last pushed snapshot — skip re-broadcasting identical state. */
    private volatile String lastSnapshotKey = "";

    @Inject
    public HeadlinePublisher(AgentRepository repo, PushHub hub, ApplicationEventBus bus) {
        this.repo = repo;
        this.hub = hub;
        bus.register(this);
        hub.onClientOpen(() -> pushAll(true));
        scheduler.scheduleAtFixedRate(() -> pushAll(false), 60, 60, TimeUnit.SECONDS);
    }

    @Subscribe
    public void onAgentStreamEnd(AgentStreamEndEvent event) {
        String msg = event.fullMessage();
        if (msg == null || !msg.startsWith("||PASSIVE||")) return;
        scheduler.submit(() -> pushAll(false));
    }

    /**
     * @param force if {@code true}, broadcast even when the snapshot
     *              hash matches the last push — for fresh client opens
     *              where the receiver has no prior state.
     */
    private void pushAll(boolean force) {
        try {
            List<HeadlineRecord> records = repo.getRecentHeadlines();
            // Compact key: count + ids + last createdAt. Cheap to compute,
            // changes whenever anything meaningful changes.
            String key = records.size() + ":" + records.stream()
                    .map(r -> r.clusterId() + "@" + r.createdAt())
                    .reduce((a, b) -> a + "," + b).orElse("");
            if (!force && key.equals(lastSnapshotKey)) {
                return;     // nothing changed since last broadcast
            }
            lastSnapshotKey = key;
            hub.broadcast("headlines", HeadlineJson.toJson(records));
        } catch (Exception e) {
            LOG.warn("headline push failed: {}", e.getMessage());
        }
    }
}
