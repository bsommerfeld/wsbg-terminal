package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed shapes of the POLITIK world-data clients (fishing-net
 * dossier, all fixtures fetched 2026-07-14): Fed RSS with its UTF-8 BOM
 * prolog, the ECB press feed answering RSS on a .html URL, the ECB SDMX
 * csvdata with per-flow column positions and the HICP flash's OBS_STATUS=E,
 * the White House WordPress feed's chrome-buried full text, the Federal
 * Register documents API, dawum's id-joined poll dump, the sanctionsmap
 * regime list with thematic country-less regimes, and the presscorner's
 * POLICY_AREA categories.
 */
class PolitikClientsTest {

    private static String fixture(String name) {
        try (InputStream in = PolitikClientsTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    // ---- FedFeedsClient ----------------------------------------------------

    @Test
    void fedPressParsesDespiteBomProlog() {
        String xml = fixture("fed-press-all.xml");
        assertEquals('\uFEFF', xml.charAt(0), "fixture must pin the BOM quirk");
        List<FedFeedsClient.Item> items = FedFeedsClient.parse(xml, FedFeedsClient.Feed.PRESS);
        assertEquals(3, items.size());
        FedFeedsClient.Item first = items.get(0);
        assertTrue(first.title().contains("discount rate meetings"));
        assertEquals("Monetary Policy", first.category());
        assertTrue(first.link().contains("pressreleases/monetary20260714a.htm"));
        assertEquals(Instant.parse("2026-07-14T18:00:00Z"), first.publishedAt());
        assertEquals(FedFeedsClient.Feed.PRESS, first.feed());
    }

    @Test
    void fedSpeechesCarryTheFeedMarker() {
        List<FedFeedsClient.Item> items =
                FedFeedsClient.parse(fixture("fed-speeches.xml"), FedFeedsClient.Feed.SPEECHES);
        assertEquals(2, items.size());
        assertTrue(items.get(0).title().startsWith("Bowman,"));
        assertEquals("Speech", items.get(0).category());
        assertEquals(FedFeedsClient.Feed.SPEECHES, items.get(0).feed());
    }

    @Test
    void fedGarbageYieldsEmpty() {
        assertTrue(FedFeedsClient.parse(null, FedFeedsClient.Feed.PRESS).isEmpty());
        assertTrue(FedFeedsClient.parse("<html>wall</html>", FedFeedsClient.Feed.PRESS).isEmpty());
    }

    // ---- EcbFeedsClient ------------------------------------------------------

    @Test
    void ecbPressHtmlUrlIsActuallyRss() {
        List<EcbFeedsClient.PressItem> items = EcbFeedsClient.parsePress(fixture("ecb-press.xml"));
        assertEquals(3, items.size());
        assertTrue(items.get(0).title().contains("digital euro pilot"));
        assertNotNull(items.get(0).publishedAt());
        assertTrue(items.get(0).link().contains("ecb.europa.eu"));
    }

    @Test
    void ecbDfrCsvYieldsLatestObservation() {
        EcbFeedsClient.Observation obs = EcbFeedsClient.parseSeriesCsv(fixture("ecb-dfr.csv"));
        assertNotNull(obs);
        assertEquals("2026-07-14", obs.isoPeriod());
        assertEquals(2.25, obs.value());
        assertFalse(obs.estimate());
    }

    @Test
    void ecbEstrCsvYieldsLatestFixing() {
        EcbFeedsClient.Observation obs = EcbFeedsClient.parseSeriesCsv(fixture("ecb-estr.csv"));
        assertNotNull(obs);
        assertEquals("2026-07-13", obs.isoPeriod());
        assertEquals(2.184, obs.value());
    }

    @Test
    void ecbHicpFlashIsMarkedEstimate() {
        // The newest month of HICP/M.U2.N.000000.4D0.ANR arrives OBS_STATUS=E:
        // that IS Eurostat's flash estimate.
        EcbFeedsClient.Observation obs = EcbFeedsClient.parseSeriesCsv(fixture("ecb-hicp.csv"));
        assertNotNull(obs);
        assertEquals("2026-06", obs.isoPeriod());
        assertEquals(2.8, obs.value());
        assertTrue(obs.estimate());
    }

    @Test
    void ecbSeriesGarbageYieldsNull() {
        assertNull(EcbFeedsClient.parseSeriesCsv(null));
        assertNull(EcbFeedsClient.parseSeriesCsv("<html>wall</html>"));
        // The portal's own 404 shape is JSON, not CSV.
        assertNull(EcbFeedsClient.parseSeriesCsv(
                "{\"title\":\"Not Found\",\"status\":404}"));
        assertNull(EcbFeedsClient.parseSeriesCsv("KEY,TIME_PERIOD,OBS_VALUE\n"));
    }

    @Test
    void ecbCsvSplitKeepsQuotedCommasTogether() {
        List<String> row = EcbFeedsClient.splitCsv("a,\"b, with comma\",c,\"d \"\"q\"\"\"");
        assertEquals(List.of("a", "b, with comma", "c", "d \"q\""), row);
    }

    // ---- WhiteHouseActionsClient --------------------------------------------

    @Test
    void whiteHouseExcerptIsTheActionsOwnProseNotWpChrome() {
        List<WhiteHouseActionsClient.Action> actions =
                WhiteHouseActionsClient.parse(fixture("whitehouse-actions.xml"));
        assertEquals(2, actions.size());
        WhiteHouseActionsClient.Action first = actions.get(0);
        assertEquals("Nominations and Withdrawals Sent to the Senate", first.title());
        assertTrue(first.link().startsWith("https://www.whitehouse.gov/presidential-actions/"));
        assertEquals(Instant.parse("2026-07-14T19:12:59Z"), first.publishedAt());
        assertTrue(first.excerpt().startsWith("NOMINATIONS SENT TO THE SENATE:"));
        // Numeric WP entities are decoded ("D’Avanzo", not "D&#8217;Avanzo").
        assertTrue(first.excerpt().contains("D’Avanzo"));
        assertTrue(first.excerpt().length() <= WhiteHouseActionsClient.EXCERPT_CHARS);
        // The proclamation item: prose starts right after the page chrome.
        WhiteHouseActionsClient.Action second = actions.get(1);
        assertTrue(second.excerpt().startsWith("BY THE PRESIDENT OF THE UNITED STATES"));
        for (WhiteHouseActionsClient.Action a : actions) {
            assertFalse(a.excerpt().contains("<"), "HTML must be stripped");
            assertFalse(a.excerpt().contains("wp-block"), "WP chrome must not leak");
            assertFalse(a.excerpt().contains("appeared first on"), "WP trailer must not leak");
        }
    }

    @Test
    void whiteHouseTrailerIsStrippedFromDescriptionFallback() {
        String text = "NOMINATIONS SENT TO THE SENATE: Somebody, of Somewhere. "
                + "The post Nominations and Withdrawals Sent to the Senate appeared first on "
                + "The White House.";
        assertEquals("NOMINATIONS SENT TO THE SENATE: Somebody, of Somewhere.",
                WhiteHouseActionsClient.stripTrailer(text));
    }

    @Test
    void whiteHouseGarbageYieldsEmpty() {
        assertTrue(WhiteHouseActionsClient.parse(null).isEmpty());
        assertTrue(WhiteHouseActionsClient.parse("not xml at all").isEmpty());
    }

    // ---- FederalRegisterClient ----------------------------------------------

    @Test
    void federalRegisterPresidentialDocumentsParse() {
        List<FederalRegisterClient.Doc> docs =
                FederalRegisterClient.parseDocuments(fixture("federal-register-presdocu.json"));
        assertEquals(5, docs.size());
        FederalRegisterClient.Doc first = docs.get(0);
        assertTrue(first.title().startsWith("250th Anniversary"));
        assertEquals("Presidential Document", first.type());
        assertEquals(LocalDate.of(2026, 7, 8), first.publicationDate());
        assertNull(first.abstractText(), "presidential documents usually ship no abstract");
        assertTrue(first.url().startsWith("https://www.federalregister.gov/documents/"));
    }

    @Test
    void federalRegisterSignificantRulesCarryAbstracts() {
        List<FederalRegisterClient.Doc> docs =
                FederalRegisterClient.parseDocuments(fixture("federal-register-rules.json"));
        assertEquals(5, docs.size());
        assertEquals("Rule", docs.get(0).type());
        assertEquals(LocalDate.of(2026, 7, 14), docs.get(0).publicationDate());
        assertNotNull(docs.get(0).abstractText());
        assertTrue(docs.get(0).abstractText().contains("Endangered Species Act"));
    }

    @Test
    void federalRegisterGarbageYieldsEmpty() {
        assertTrue(FederalRegisterClient.parseDocuments(null).isEmpty());
        assertTrue(FederalRegisterClient.parseDocuments("<html>wall</html>").isEmpty());
    }

    // ---- DawumClient ----------------------------------------------------------

    @Test
    void dawumJoinsSurveysToParliamentInstituteAndParties() {
        List<DawumClient.Survey> surveys = DawumClient.parse(fixture("dawum.json"));
        assertEquals(5, surveys.size());
        DawumClient.Survey sh = surveys.stream()
                .filter(s -> "Schleswig-Holstein".equals(s.parliament()))
                .findFirst().orElseThrow();
        assertEquals("INSA", sh.institute());
        assertEquals(LocalDate.of(2026, 7, 14), sh.date());
        assertEquals(1000, sh.surveyedPersons());
        assertEquals(27.0, sh.results().get("CDU"));
        assertEquals(18.0, sh.results().get("AfD"));
        assertEquals(6.0, sh.results().get("SSW"));
    }

    @Test
    void dawumLatestCapsPerParliamentNewestFirst() {
        List<DawumClient.Survey> all = DawumClient.parse(fixture("dawum.json"));
        List<DawumClient.Survey> latest = DawumClient.latestOf(all, 1);
        assertEquals(2, latest.size(), "one per polled parliament");
        assertTrue(latest.stream().anyMatch(s -> "Bundestag".equals(s.parliament())));
        assertTrue(latest.stream().anyMatch(s -> "Schleswig-Holstein".equals(s.parliament())));
        DawumClient.Survey bund = latest.stream()
                .filter(s -> "Bundestag".equals(s.parliament())).findFirst().orElseThrow();
        // Decimal percentages survive as doubles.
        assertEquals(28.5, bund.results().get("AfD"));
        assertEquals(LocalDate.of(2026, 7, 14), bund.date());
    }

    @Test
    void dawumGarbageYieldsEmpty() {
        assertTrue(DawumClient.parse(null).isEmpty());
        assertTrue(DawumClient.parse("<html>wall</html>").isEmpty());
    }

    // ---- SanctionsMapClient ---------------------------------------------------

    @Test
    void sanctionsRegimesParseWithCountryMeasuresAndUpdate() {
        List<SanctionsMapClient.Regime> regimes =
                SanctionsMapClient.parse(fixture("sanctionsmap-regime.json"));
        assertEquals(3, regimes.size());
        SanctionsMapClient.Regime taliban = regimes.get(0);
        assertEquals(1, taliban.id());
        assertEquals("Afghanistan", taliban.country());
        assertEquals("UN", taliban.adoptedBy());
        assertEquals(3, taliban.measuresCount());
        // amendment 1726178400 (unix seconds) → 2024-09-12 UTC
        assertEquals(LocalDate.of(2024, 9, 12), taliban.lastUpdate());
    }

    @Test
    void sanctionsThematicRegimeHasNoCountry() {
        // A thematic regime (chemical weapons) ships country.data as [].
        List<SanctionsMapClient.Regime> regimes =
                SanctionsMapClient.parse(fixture("sanctionsmap-regime.json"));
        SanctionsMapClient.Regime thematic = regimes.stream()
                .filter(r -> r.id() == 46).findFirst().orElseThrow();
        assertNull(thematic.country());
        assertEquals("EU", thematic.adoptedBy());
    }

    @Test
    void sanctionsForCountryPicksTheBroadestRegime() {
        List<SanctionsMapClient.Regime> regimes =
                SanctionsMapClient.parse(fixture("sanctionsmap-regime.json"));
        Optional<SanctionsMapClient.Regime> russia =
                SanctionsMapClient.pickForCountry(regimes, "russia");
        assertTrue(russia.isPresent());
        assertEquals(26, russia.get().id());
        assertEquals(66, russia.get().measuresCount());
        assertTrue(SanctionsMapClient.pickForCountry(regimes, "Atlantis").isEmpty());
        assertTrue(SanctionsMapClient.pickForCountry(regimes, null).isEmpty());
    }

    @Test
    void sanctionsGarbageYieldsEmpty() {
        assertTrue(SanctionsMapClient.parse(null).isEmpty());
        assertTrue(SanctionsMapClient.parse("<html>wall</html>").isEmpty());
    }

    // ---- EuPresscornerClient ----------------------------------------------------

    @Test
    void presscornerItemsCarryPolicyAreaCodes() {
        List<EuPresscornerClient.Item> items =
                EuPresscornerClient.parse(fixture("eu-presscorner.xml"));
        assertEquals(3, items.size());
        EuPresscornerClient.Item first = items.get(0);
        assertTrue(first.title().contains("Roswall"));
        assertTrue(first.link().contains("presscorner/detail/en/"));
        assertEquals("ENVIRO", first.policyArea());
        assertEquals(Instant.parse("2026-07-14T17:25:55Z"), first.publishedAt());
    }

    @Test
    void presscornerPolicyAreaHelperIsShapeStrict() {
        assertEquals("TRADE", EuPresscornerClient.policyArea("POLICY_AREA=TRADE"));
        assertNull(EuPresscornerClient.policyArea("Press"));
        assertNull(EuPresscornerClient.policyArea(""));
        assertNull(EuPresscornerClient.policyArea(null));
    }

    @Test
    void presscornerGarbageYieldsEmpty() {
        assertTrue(EuPresscornerClient.parse(null).isEmpty());
        assertTrue(EuPresscornerClient.parse("<html>wall</html>").isEmpty());
    }
}
