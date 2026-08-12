package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p><b>Snapshot once, deltas after.</b> The wire carries the whole archive, so
 * re-broadcasting it on every change would put megabytes on the socket to
 * deliver one new line — and it would grow with the archive forever. A
 * connecting client therefore gets the full state on {@code headlines}; every
 * later change ships only what is genuinely new on {@code headlines-add}, which
 * the page appends. {@link #delivered} is what the clients are known to hold.
 * Should a record VANISH (a wipe), a delta cannot express that — the publisher
 * falls back to a full snapshot on its own, so no caller has to remember to.
 */
@Singleton
public final class HeadlinePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlinePublisher.class);

    private final AgentRepository repo;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler = DaemonSchedulers.scheduled("headline-publisher");

    /** Row keys the connected pages already hold — the delta baseline. */
    private final Set<String> delivered = new HashSet<>();

    @Inject
    public HeadlinePublisher(AgentRepository repo, PushHub hub, ApplicationEventBus bus) {
        this.repo = repo;
        this.hub = hub;
        bus.register(this);
        hub.onClientOpen(this::pushSnapshot);
        scheduler.scheduleAtFixedRate(this::pushDelta, 60, 60, TimeUnit.SECONDS);
    }

    @Subscribe
    public void onAgentStreamEnd(AgentStreamEndEvent event) {
        String msg = event.fullMessage();
        if (msg == null || !msg.startsWith("||PASSIVE||")) return;
        scheduler.submit(this::pushDelta);
    }

    /**
     * The identity a row is tracked by — the same pairing the page keys its rows
     * on (clusterId + createdAt), so both ends agree on what "already there" means.
     */
    private static String keyOf(HeadlineRecord r) {
        return r.clusterId() + "@" + r.createdAt();
    }

    /**
     * Broadcasts the complete wire and resets the delta baseline to it. For a
     * fresh client (no prior state) and as the recovery path after a wipe.
     */
    public synchronized void pushSnapshot() {
        try {
            List<HeadlineRecord> records = repo.getWireHeadlines();
            delivered.clear();
            for (HeadlineRecord r : records) delivered.add(keyOf(r));
            hub.broadcast("headlines", HeadlineJson.toJson(records));
        } catch (Exception e) {
            LOG.warn("headline snapshot push failed: {}", e.getMessage());
        }
    }

    /** Broadcasts only what the pages do not have yet; silent when nothing changed. */
    private synchronized void pushDelta() {
        try {
            List<HeadlineRecord> records = repo.getWireHeadlines();
            // Anything the pages hold that the wire no longer has (a wipe) cannot
            // be undone by appending — that needs the full state.
            int kept = 0;
            List<HeadlineRecord> fresh = new ArrayList<>();
            for (HeadlineRecord r : records) {
                if (delivered.contains(keyOf(r))) kept++;
                else fresh.add(r);
            }
            if (kept < delivered.size()) {
                pushSnapshot();
                return;
            }
            if (fresh.isEmpty()) return;    // nothing changed since the last push
            for (HeadlineRecord r : fresh) delivered.add(keyOf(r));
            hub.broadcast("headlines-add", HeadlineJson.toJson(fresh));
        } catch (Exception e) {
            LOG.warn("headline delta push failed: {}", e.getMessage());
        }
    }
}
