package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probes of the world-context sources - real HTTP against the real sources,
 * excluded from normal runs. This is the house's isolated verification: no
 * synthetic scraper, no fixture stand-in for a source that is either up or is
 * not (2026-07/08 source waves).
 */
@Tag("integration")
class WorldSourcesLiveIT {

    @Test
    void gdeltAnswersAttacksAtCityPrecision() {
        List<GdeltConflictClient.Attack> attacks = new GdeltConflictClient().attacks();
        System.out.println("[GDELT] attacks=" + attacks.size());
        assertFalse(attacks.isEmpty(), "GDELT answered no violent events");
        attacks.stream().limit(5).forEach(a -> System.out.println("[GDELT] " + a));
        assertTrue(attacks.stream().anyMatch(a -> a.place() != null && a.place().contains(",")),
                "no place carried a city-level name - the centroid filter is not biting");
    }

    @Test
    void auroraOvalAnswersAGrid() {
        var oval = new AuroraOvalClient().latest();
        System.out.println("[OVATION] present=" + oval.isPresent()
                + " points=" + oval.map(o -> o.points().size()).orElse(-1));
        assertTrue(oval.isPresent(), "OVATION answered nothing");
    }

    @Test
    void gdacsAnswersAlerts() {
        List<GdacsClient.GdacsEvent> events = new GdacsClient().events();
        System.out.println("[GDACS] events=" + events.size());
        events.stream().limit(5).forEach(e -> System.out.println("[GDACS] " + e));
        assertFalse(events.isEmpty(), "GDACS answered nothing");
    }

    @Test
    void firmsAnswersFireClusters() {
        List<WildfireClient.FireCluster> fires = new WildfireClient().clusters();
        System.out.println("[FIRMS] clusters=" + fires.size());
        assertFalse(fires.isEmpty(), "FIRMS answered nothing");
    }

    @Test
    void oniAnswersTheCurrentEnsoPhase() {
        var oni = new EnsoClient().latest();
        System.out.println("[ONI] " + oni.orElse(null));
        assertTrue(oni.isPresent(), "CPC answered nothing");
    }

    @Test
    void openMeteoAnswersTheGlobalRaster() {
        List<GlobalWeatherGridClient.Cell> cells = new GlobalWeatherGridClient().grid();
        System.out.println("[WXGRID] cells=" + cells.size());
        assertFalse(cells.isEmpty(), "Open-Meteo answered no cells");
    }

    @Test
    void gdeltAnswersRelationArcsBetweenPlaces() {
        List<GdeltConflictClient.Relation> relations = new GdeltConflictClient().relations();
        System.out.println("[GDELT-REL] relations=" + relations.size());
        relations.stream().limit(8).forEach(r -> System.out.println("[GDELT-REL] " + r));
        assertFalse(relations.isEmpty(), "GDELT answered no actor pairs");
        assertTrue(relations.stream().anyMatch(r -> !r.stance().equals("COOPERATION")),
                "not a single conflict stance in a whole window is implausible");
    }

    @Test
    void gdeltReadsTheNonEnglishPressToo() {
        // The translingual stream is the whole point of reading two files: a
        // world picture that only sees anglophone media is not a world picture.
        List<GdeltConflictClient.Attack> attacks = new GdeltConflictClient().attacks();
        long nonCom = attacks.stream()
                .map(GdeltConflictClient.Attack::sourceUrl)
                .filter(u -> u != null && !u.isBlank())
                .filter(u -> !u.contains(".com/") && !u.contains(".net/") && !u.contains(".org/"))
                .count();
        System.out.println("[GDELT-LANG] attacks=" + attacks.size()
                + " from non-.com/.net/.org domains=" + nonCom);
        attacks.stream().limit(10).forEach(a -> System.out.println("[GDELT-LANG] " + a.sourceUrl()));
    }

    @Test
    void eonetAnswersOpenNaturalEvents() {
        List<EonetClient.NaturalEvent> events = new EonetClient().events();
        System.out.println("[EONET] events=" + events.size());
        events.stream().limit(6).forEach(e -> System.out.println("[EONET] " + e));
        assertFalse(events.isEmpty(), "EONET answered nothing");
        assertTrue(events.stream().noneMatch(e -> "wildfires".equals(e.category())),
                "wildfires must stay with the satellite feed");
    }

    @Test
    void launchLibraryAnswersPadsInsideTheHorizon() {
        List<LaunchPadClient.Launch> launches = new LaunchPadClient().upcoming();
        System.out.println("[LAUNCH] launches=" + launches.size());
        launches.stream().limit(5).forEach(l -> System.out.println("[LAUNCH] " + l));
        assertFalse(launches.isEmpty(), "Launch Library answered nothing");
    }

    @Test
    void airQualityAnswersTheDirtyCells() {
        List<AirQualityGridClient.AirCell> cells = new AirQualityGridClient().grid();
        System.out.println("[AIRQ] dirty cells=" + cells.size());
        cells.stream().limit(5).forEach(c -> System.out.println("[AIRQ] " + c));
        // An entirely clean planet is a valid (if unlikely) answer - the probe
        // only asserts the leg ran without throwing.
    }

    @Test
    void iodaAnswersCountryLevelAlerts() {
        List<InternetOutageClient.Outage> outages = new InternetOutageClient().outages();
        System.out.println("[IODA] critical countries=" + outages.size());
        outages.stream().limit(5).forEach(o -> System.out.println("[IODA] " + o));
    }

    @Test
    void issAnswersAFreshFix() {
        var station = new OrbitClient().station();
        System.out.println("[ISS] " + station.orElse(null));
        assertTrue(station.isPresent(), "wheretheiss answered nothing");
        assertTrue(Math.abs(station.get().lat()) <= 90, "impossible latitude");
    }

    @Test
    void dwdAnswersItsOwnWarningDistricts() {
        List<GermanWeatherAlertClient.Alert> alerts = new GermanWeatherAlertClient().alerts();
        System.out.println("[DWD] warned districts=" + alerts.size());
        alerts.stream().limit(4).forEach(a -> System.out.println("[DWD] " + a));
        // A calm Germany is a valid answer; the probe checks the leg RUNS and
        // that whatever comes back is placed somewhere inside the country.
        assertTrue(alerts.stream().allMatch(a -> a.lat() > 45 && a.lat() < 56
                && a.lon() > 5 && a.lon() < 16), "a DWD district outside Germany is a parse bug");
    }

    @Test
    void adsbAnswersAircraftAroundAField() {
        // Frankfurt: the house's own home airport, busy at any hour.
        List<FlightClient.Aircraft> ac = new FlightClient().around(50.0379, 8.5622, "FRA");
        System.out.println("[ADSB] aircraft=" + ac.size());
        ac.stream().limit(5).forEach(a -> System.out.println("[ADSB] " + a));
        assertFalse(ac.isEmpty(), "no transponder at all around Frankfurt is implausible");
        assertTrue(ac.stream().anyMatch(a -> a.callsign() != null), "no callsign carried");
    }

    @Test
    void autobahnAnswersTheLiveNetwork() {
        List<AutobahnClient.RoadIncident> roads = new AutobahnClient().incidents();
        long blocked = roads.stream().filter(AutobahnClient.RoadIncident::blocked).count();
        System.out.println("[AUTOBAHN] incidents=" + roads.size() + " blocked=" + blocked);
        roads.stream().limit(5).forEach(r -> System.out.println("[AUTOBAHN] " + r));
        assertFalse(roads.isEmpty(), "the whole German motorway network reported nothing at all");
        assertTrue(roads.stream().allMatch(r -> r.lat() > 45 && r.lat() < 56
                && r.lon() > 5 && r.lon() < 16), "a Bundesautobahn outside Germany is a parse bug");
    }

    @Test
    void pegelonlineAnswersGaugesOutOfBand() {
        List<WaterLevelClient.Gauge> gauges = new WaterLevelClient().gauges();
        System.out.println("[PEGEL] out-of-band gauges=" + gauges.size());
        gauges.stream().limit(6).forEach(g -> System.out.println("[PEGEL] " + g));
        assertTrue(gauges.stream().allMatch(g -> "low".equals(g.state()) || "high".equals(g.state())),
                "a gauge inside its normal band must never reach the map");
    }

    @Test
    void statusBoardsAnswer() {
        ServiceStatusClient.StatusReport report = new ServiceStatusClient().report();
        System.out.println("[STATUS] boards=" + report.boardsChecked()
                + " degraded=" + report.degraded());
        assertTrue(report.boardsChecked() > 0, "no status board answered at all");
    }
}
