package de.bsommerfeld.wsbg.terminal.euronext;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decrypt + parse contract against a REAL encrypted reply (ASML 1M, probed
 * 2026-08-05) - the ciphertext fixture is byte-for-byte what the wire sent,
 * the plaintext fixture is what the live decrypt yielded.
 */
class EuronextClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = EuronextClientTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void decryptsTheRealCiphertextToTheRecordedPlaintext() throws IOException {
        String plain = EuronextClient
                .decrypt(fixture("/euronext-chart-asml-1m.json"),
                        EuronextClient.FALLBACK_PASSPHRASE)
                .orElseThrow();
        assertEquals(fixture("/euronext-chart-asml-1m-plain.json"), plain,
                "AES-256-CBC + EVP_BytesToKey must reproduce the live decrypt exactly");
    }

    @Test
    void parsesBarsOldestFirstWithCloseAndVolume() throws IOException {
        String plain = EuronextClient
                .decrypt(fixture("/euronext-chart-asml-1m.json"),
                        EuronextClient.FALLBACK_PASSPHRASE)
                .orElseThrow();
        List<EuronextHistory.EuronextBar> bars = EuronextClient.parseBars(plain);
        assertEquals(24, bars.size());
        var first = bars.get(0);
        var last = bars.get(bars.size() - 1);
        assertEquals(LocalDate.of(2026, 7, 3), first.date());
        assertEquals(1634.4, first.close(), 1e-6);
        assertEquals(270_434, first.volume(), 1e-9);
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertEquals(1465.8, last.close(), 1e-6);
        assertEquals(452_084, last.volume(), 1e-9);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        // The wire carries close+volume only - OHL are NaN by contract.
        assertTrue(Double.isNaN(first.open()) && Double.isNaN(first.high())
                && Double.isNaN(first.low()));
    }

    @Test
    void aWrongKeyStaysEmptyInsteadOfThrowing() throws IOException {
        String body = fixture("/euronext-chart-asml-1m.json");
        assertTrue(EuronextClient.decrypt(body, "notTheRealKey").isEmpty(),
                "bad padding is an empty, never an exception");
    }

    @Test
    void garbageEnvelopesAndPlaintextsStayEmpty() {
        assertTrue(EuronextClient.decrypt(null, "x").isEmpty());
        assertTrue(EuronextClient.decrypt("", "x").isEmpty());
        assertTrue(EuronextClient.decrypt("not json", "x").isEmpty());
        assertTrue(EuronextClient.decrypt("{\"ct\":\"AAAA\"}", "x").isEmpty(),
                "an envelope without a salt is undecryptable");
        assertTrue(EuronextClient.decrypt("{\"ct\":\"AAAA\",\"s\":\"zz\"}", "x").isEmpty(),
                "a non-hex salt is undecryptable");
        assertTrue(EuronextClient.parseBars(null).isEmpty());
        assertTrue(EuronextClient.parseBars("").isEmpty());
        assertTrue(EuronextClient.parseBars("{\"not\":\"an array\"}").isEmpty());
        assertTrue(EuronextClient.parseBars("[{\"time\":\"garbage\",\"price\":1}]").isEmpty());
    }

    @Test
    void nonEuronextIdentitiesNeverLeaveTheJvm() {
        var noNetwork = new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                    String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout) {
                throw new AssertionError("no network expected for " + url);
            }
        };
        var client = new EuronextClient(noNetwork);
        assertTrue(client.history("NL0010273215", "XNAS", "max").isEmpty(),
                "a non-Euronext MIC is a no-op");
        assertTrue(client.history("ASML", "XAMS", "max").isEmpty(),
                "a ticker is not an ISIN");
        assertTrue(client.history("NL0010273215", "XAMS", "../evil").isEmpty(),
                "a period is a URL token, nothing looser");
        assertTrue(client.history(null, "XAMS", "max").isEmpty());
        assertTrue(client.history("NL0010273215", null, "max").isEmpty());
        assertTrue(client.history("NL0010273215", "XAMS", null).isEmpty());
    }
}
