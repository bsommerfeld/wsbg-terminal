package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Guard stage 3 — the {@link IdentityDesk} checkpoint, wired after the curated
 * catalogues (curated identity needs no judgment) and BEFORE the legacy
 * token/veto/judge/corpus stages, which now serve only as the desk's outage
 * fallback. The desk is read via supplier (like the judge/corpus deps) so the
 * post-construction setter on the resolver takes effect.
 */
final class DeskMatcher implements SubjectMatcher {

    private final Supplier<IdentityDesk> desk;

    DeskMatcher(Supplier<IdentityDesk> desk) {
        this.desk = desk;
    }

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        IdentityDesk d = desk.get();
        return d == null ? Optional.empty() : d.decide(ctx);
    }
}
