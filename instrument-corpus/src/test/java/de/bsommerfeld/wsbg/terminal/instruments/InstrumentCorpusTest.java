package de.bsommerfeld.wsbg.terminal.instruments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentCorpusTest {

    private static InstrumentCorpus corpusOf(Path dir, InstrumentEntry... entries) {
        InstrumentCorpus c = new InstrumentCorpus(dir.resolve("instruments.jsonl"),
                List.of(new CorpusSource() {
                    @Override
                    public String name() {
                        return "fixture";
                    }

                    @Override
                    public List<InstrumentEntry> fetch() {
                        return List.of(entries);
                    }
                }));
        c.refresh();
        return c;
    }

    @Test
    void searchRanksExactTokenAbovePrefixAndCapsAtK(@TempDir Path dir) {
        InstrumentCorpus c = corpusOf(dir,
                new InstrumentEntry("MSTR", "MicroStrategy Incorporated", null, "US", "EQUITY", "sec"),
                new InstrumentEntry("MU", "Micron Technology Inc", null, "US", "EQUITY", "sec"),
                new InstrumentEntry("AAPL", "Apple Inc.", null, "US", "EQUITY", "sec"));

        List<InstrumentEntry> hits = c.search("MicroStrategy", 2);
        assertEquals("MSTR", hits.get(0).symbol(), "exact token beats prefix cousin");
        assertTrue(hits.size() <= 2);
        assertTrue(c.search("Zinsen", 5).isEmpty(), "no lexical bridge → no candidates");
    }

    @Test
    void searchFindsBySymbolToken(@TempDir Path dir) {
        InstrumentCorpus c = corpusOf(dir,
                new InstrumentEntry("R3NK.DE", "RENK Group AG", "DE000RENK730", "DE", "EQUITY", "wso"),
                new InstrumentEntry("RWE.DE", "RWE AG", "DE0007037129", "DE", "EQUITY", "wso"));
        assertEquals("R3NK.DE", c.search("RENK", 3).get(0).symbol());
    }

    @Test
    void persistAndReloadRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("instruments.jsonl");
        InstrumentCorpus c = corpusOf(dir,
                new InstrumentEntry("NVDA", "NVIDIA CORP", null, "US", "EQUITY", "sec"));
        assertTrue(Files.exists(file), "refresh persists");

        InstrumentCorpus reloaded = new InstrumentCorpus(file, List.of());
        reloaded.start(); // no sources → pure disk load, no refresh thread work
        assertEquals(1, reloaded.size());
        assertEquals("NVDA", reloaded.search("Nvidia", 1).get(0).symbol());
    }

    @Test
    void legalFormNoiseNeverBridgesCompanies(@TempDir Path dir) {
        InstrumentCorpus c = corpusOf(dir,
                new InstrumentEntry("AAPL", "Apple Inc.", null, "US", "EQUITY", "sec"),
                new InstrumentEntry("MSFT", "MICROSOFT CORP", null, "US", "EQUITY", "sec"));
        assertTrue(c.search("Inc Corp Group", 5).isEmpty(), "stop tokens carry no match");
    }

    @Test
    void secParseReadsTheOfficialShape() throws Exception {
        String body = "{\"0\":{\"cik_str\":1045810,\"ticker\":\"NVDA\",\"title\":\"NVIDIA CORP\"},"
                + "\"1\":{\"cik_str\":320193,\"ticker\":\"AAPL\",\"title\":\"Apple Inc.\"}}";
        List<InstrumentEntry> got = SecTickerSource.parse(body);
        assertEquals(2, got.size());
        assertEquals("NVDA", got.get(0).symbol());
        assertEquals("NVIDIA CORP", got.get(0).name());
        assertEquals("sec", got.get(0).source());
    }

    @Test
    void wsoSourceJoinsNameAndTickerAliasByIsin(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("wso-isin.jsonl");
        Files.writeString(f, """
                {"q":"direxion daily semiconductor bull 3x shares","isin":"US25459W4583","wkn":"A1C1G7","name":"Direxion Daily Semiconductor Bull 3X Shares"}
                {"q":"ticker:SOXL","isin":"US25459W4583","wkn":"A1C1G7","name":"Direxion Daily Semiconductor Bull 3X Shares"}
                {"q":"meta wolf","isin":"DE000A3E5A59","wkn":null,"name":"Meta Wolf AG"}
                garbled line
                """);
        List<InstrumentEntry> got = new WsoIsinSource(f).fetch();
        assertEquals(2, got.size(), "one instrument per ISIN, torn lines skipped");
        assertEquals("SOXL", got.get(0).symbol(), "ticker: alias contributes the symbol");
        assertEquals("DE000A3E5A59", got.get(1).symbol(), "no alias → ISIN stands in as symbol");
        assertEquals("DE", got.get(1).exchange(), "ISIN country as exchange hint");
    }

    @Test
    void xetraParseReadsTheOfficialCsvShape() {
        String body = """
                Market:;XETR
                Date Last Update:;06.07.2026
                Product Status;Instrument Status;Instrument;ISIN;Product ID;Instrument ID;WKN;Mnemonic;MIC Code;CCP eligible Code;Trading Model Type;Product Assignment Group;Product Assignment Group Description;Designated Sponsor Member ID;Designated Sponsor;Price Range Value;Price Range Percentage;Minimum Quote Size;Instrument Type;Tick Size 1
                Active;Active;RHEINMETALL AG;DE0007030009;1;2;703000;RHM;XETR;Y;Continuous;GER1;DEUTSCHLAND;X;Y;;2;100;CS;0.05
                Active;Active;ISHS CORE DAX UCITS ETF;DE0005933931;3;4;593393;EXS1;XETR;Y;Continuous;ETF0;ETF;X;Y;;2;100;ETF;0.01
                Active;Active;SOME GOLD ETC;DE000A0S9GB0;5;6;A0S9GB;4GLD;XETR;Y;Continuous;ETC0;ETC;X;Y;;2;100;ETC;0.01
                Suspended;Active;DEAD CORP;DE0000000001;7;8;000001;DED;XETR;Y;Continuous;GER1;DEUTSCHLAND;X;Y;;2;100;CS;0.05
                """;
        var got = XetraSource.parse(body);
        assertEquals(2, got.size(), "only Active CS/ETF survive; ETC + Suspended skipped");
        assertEquals("RHM.DE", got.get(0).symbol(), "Yahoo-style mnemonic.DE symbol");
        assertEquals("DE0007030009", got.get(0).isin());
        assertEquals("EQUITY", got.get(0).type());
        assertEquals("ETF", got.get(1).type());
    }
}
