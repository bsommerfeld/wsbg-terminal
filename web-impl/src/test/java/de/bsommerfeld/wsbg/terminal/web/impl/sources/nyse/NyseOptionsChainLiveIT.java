package de.bsommerfeld.wsbg.terminal.web.impl.sources.nyse;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The options chain rides in the SAME reply as the tape - this proves it does,
 * against the live exchange, so nobody re-adds a second fetch for it later.
 */
@Tag("integration")
class NyseOptionsChainLiveIT {

    @Test
    void theTapeCarriesTheOptionsBookForFree() {
        if (!"true".equals(System.getenv("NYSE_SMOKE"))) return;
        Optional<NyseTape> tape = new NyseQuoteClient(
                new HouseFetcher(java.util.Set.of(new DirectTransport()))).tape("GME");

        assertTrue(tape.isPresent(), "the live tape must answer");
        var chain = tape.get().options();
        assertFalse(chain.isEmpty(), "the reply carries the chain");
        long calls = chain.stream().filter(NyseTape.OptionContract::call).count();
        long withOi = chain.stream().filter(c -> c.openInterest() > 0).count();
        long withExpiry = chain.stream().filter(c -> c.expiry() != null).count();
        System.out.printf("[NYSE-OPTIONS] %d contracts, %d calls, %d with OI, %d dated%n",
                chain.size(), calls, withOi, withExpiry);
        assertTrue(calls > 0 && calls < chain.size(), "both sides of the book are read");
        assertTrue(withOi > chain.size() / 2, "open interest is the point of the chain");
        assertTrue(withExpiry == chain.size(), "every contract is dated");
    }
}
