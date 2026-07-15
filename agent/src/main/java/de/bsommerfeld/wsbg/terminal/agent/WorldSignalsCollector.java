package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.briefing.CisaKevClient;
import de.bsommerfeld.wsbg.terminal.briefing.DawumClient;
import de.bsommerfeld.wsbg.terminal.briefing.DiviClient;
import de.bsommerfeld.wsbg.terminal.briefing.EcbFeedsClient;
import de.bsommerfeld.wsbg.terminal.briefing.EiaWpsrClient;
import de.bsommerfeld.wsbg.terminal.briefing.EnergyChartsClient;
import de.bsommerfeld.wsbg.terminal.briefing.EuPresscornerClient;
import de.bsommerfeld.wsbg.terminal.briefing.FedFeedsClient;
import de.bsommerfeld.wsbg.terminal.briefing.FederalRegisterClient;
import de.bsommerfeld.wsbg.terminal.briefing.HarpexClient;
import de.bsommerfeld.wsbg.terminal.briefing.HolidayCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.PortWatchClient;
import de.bsommerfeld.wsbg.terminal.briefing.PresseportalClient;
import de.bsommerfeld.wsbg.terminal.briefing.RkiSurveillanceClient;
import de.bsommerfeld.wsbg.terminal.briefing.SanctionsMapClient;
import de.bsommerfeld.wsbg.terminal.briefing.SpaceWeatherClient;
import de.bsommerfeld.wsbg.terminal.briefing.SportsCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.TrafficClient;
import de.bsommerfeld.wsbg.terminal.briefing.WhiteHouseActionsClient;
import de.bsommerfeld.wsbg.terminal.briefing.WhoOutbreakClient;
import de.bsommerfeld.wsbg.terminal.briefing.WikipediaCurrentEventsClient;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ChokepointStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CivicStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ConflictStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CyberStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.FreightStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HealthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HolidayStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OilStockStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PolicyStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PollStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PowerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SpaceWxStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The ONE fishing-net collection point (2026-07-15): every world-data leg —
 * maritime chokepoints, US oil stocks, container freight, German power, space
 * weather, the policy wires (incl. EU sanction updates), polls, the civic
 * layer (presseportal + Autobahn/MVG), public health, exploited CVEs, sport
 * and holidays — gathered into ONE {@code WorldSignals} snapshot. Both
 * consumers read it: the Abendausgabe freezes it beside the day (its own
 * relevance triage decides what reaches the prose), and the KI-DD offers the
 * FULL catch to its subject-scoped judge — nothing is pre-filtered away, the
 * fishing-net doctrine ("Markt-Effekt entscheidet die KI, nie die Ingestion").
 *
 * <p>Every leg is individually guarded and every client optional — a missing
 * binding or a failing source costs its signal, never the caller. All clients
 * carry their own politeness caches, so back-to-back collects (a DD right
 * after the evening report) cost almost no network.
 */
@Singleton
class WorldSignalsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(WorldSignalsCollector.class);

    // Freeze budgets — backstops for the snapshot, not curation: the
    // consumers' AI judges decide what reaches any prose.
    static final int MAX_POLICY_PER_SOURCE = 4;
    static final int MAX_POLLS = 4;
    static final int MAX_CIVIC_PER_CHANNEL = 4;
    static final int MAX_CIVIC_BLAULICHT = 8;
    static final int MAX_CIVIC = 36;
    static final int MAX_OUTBREAKS = 3;
    static final int MAX_CYBER = 6;
    static final int MAX_SPORTS = 8;
    static final int MAX_CIVIC_TRAFFIC = 5;
    static final int MAX_CONFLICTS = 8;

    /**
     * The single-digit Autobahnen — the national backbone (a deterministic
     * shape rule, not a curated pick list); logistics rides them.
     */
    private static final List<String> AUTOBAHN_ARTERIES =
            List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9");

    private static final Map<String, String> LEAGUE_LABELS = Map.of(
            "bl1", "1. Bundesliga", "bl2", "2. Bundesliga",
            "bl3", "3. Liga", "dfb", "DFB-Pokal");

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    // Every source is @Inject(optional = true): present in prod, absent in tests.
    private volatile PortWatchClient portWatchClient;
    private volatile EiaWpsrClient eiaWpsrClient;
    private volatile HarpexClient harpexClient;
    private volatile EnergyChartsClient energyChartsClient;
    private volatile SpaceWeatherClient spaceWeatherClient;
    private volatile FedFeedsClient fedFeedsClient;
    private volatile EcbFeedsClient ecbFeedsClient;
    private volatile WhiteHouseActionsClient whiteHouseClient;
    private volatile FederalRegisterClient federalRegisterClient;
    private volatile DawumClient dawumClient;
    private volatile EuPresscornerClient euPresscornerClient;
    private volatile PresseportalClient presseportalClient;
    private volatile WhoOutbreakClient whoOutbreakClient;
    private volatile DiviClient diviClient;
    private volatile RkiSurveillanceClient rkiClient;
    private volatile CisaKevClient cisaKevClient;
    private volatile SportsCalendarClient sportsClient;
    private volatile HolidayCalendarClient holidayClient;
    private volatile SanctionsMapClient sanctionsMapClient;
    private volatile TrafficClient trafficClient;
    private volatile WikipediaCurrentEventsClient wikipediaClient;

    @com.google.inject.Inject(optional = true)
    void setPortWatchClient(PortWatchClient client) {
        this.portWatchClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEiaWpsrClient(EiaWpsrClient client) {
        this.eiaWpsrClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setHarpexClient(HarpexClient client) {
        this.harpexClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEnergyChartsClient(EnergyChartsClient client) {
        this.energyChartsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setSpaceWeatherClient(SpaceWeatherClient client) {
        this.spaceWeatherClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setFedFeedsClient(FedFeedsClient client) {
        this.fedFeedsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEcbFeedsClient(EcbFeedsClient client) {
        this.ecbFeedsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWhiteHouseClient(WhiteHouseActionsClient client) {
        this.whiteHouseClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setFederalRegisterClient(FederalRegisterClient client) {
        this.federalRegisterClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setDawumClient(DawumClient client) {
        this.dawumClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEuPresscornerClient(EuPresscornerClient client) {
        this.euPresscornerClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setPresseportalClient(PresseportalClient client) {
        this.presseportalClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWhoOutbreakClient(WhoOutbreakClient client) {
        this.whoOutbreakClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setDiviClient(DiviClient client) {
        this.diviClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setRkiClient(RkiSurveillanceClient client) {
        this.rkiClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCisaKevClient(CisaKevClient client) {
        this.cisaKevClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setSportsClient(SportsCalendarClient client) {
        this.sportsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setHolidayClient(HolidayCalendarClient client) {
        this.holidayClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setSanctionsMapClient(SanctionsMapClient client) {
        this.sanctionsMapClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setTrafficClient(TrafficClient client) {
        this.trafficClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWikipediaClient(WikipediaCurrentEventsClient client) {
        this.wikipediaClient = client;
    }

    /**
     * The full catch. Every leg individually guarded; a day where every leg
     * whiffed returns null. This method only INGESTS — what reaches any prose
     * is decided by the consumers' AI judges.
     */
    WorldSignals collect(LocalDate today, ZoneId zone, Instant dayStart) {
        WorldSignals s = new WorldSignals(
                guarded("chokepoints", List.of(), this::chokepoints),
                guarded("oil stocks", null, this::oilStocks),
                guarded("freight", null, this::freight),
                guarded("power", null, this::power),
                guarded("space weather", null, this::spaceWx),
                guarded("policy wire", List.of(), () -> policyWire(today, dayStart)),
                guarded("polls", List.of(), () -> polls(today)),
                guarded("civic wire", List.of(), () -> civicWire(dayStart)),
                guarded("health", null, this::health),
                guarded("cyber", List.of(), this::cyber),
                guarded("sports", List.of(), () -> sportsTomorrow(today, zone)),
                guarded("holidays", null, () -> holidays(today)),
                guarded("conflicts", List.of(), () -> conflicts(today)));
        boolean empty = s.chokepoints().isEmpty() && s.oilStocks() == null
                && s.freight() == null && s.power() == null && s.spaceWeather() == null
                && s.policy().isEmpty() && s.polls().isEmpty() && s.civic().isEmpty()
                && s.health() == null && s.cyber().isEmpty()
                && s.sportsTomorrow().isEmpty() && s.holidays() == null
                && s.conflicts().isEmpty();
        return empty ? null : s;
    }

    /**
     * All chokepoints of PortWatch's newest day (T-2) with the house-computed
     * week-over-week transit delta — ONE extra day-query, never 28 per-name
     * history calls.
     */
    private List<ChokepointStat> chokepoints() {
        PortWatchClient client = portWatchClient;
        if (client == null) return List.of();
        List<PortWatchClient.ChokepointDay> latest = client.latest();
        if (latest.isEmpty()) return List.of();
        LocalDate day = latest.get(0).date();
        Map<String, Integer> weekAgo = new HashMap<>();
        for (PortWatchClient.ChokepointDay d : client.day(day.minusDays(7))) {
            if (d.nTotal() >= 0) weekAgo.put(d.name(), d.nTotal());
        }
        List<ChokepointStat> out = new ArrayList<>();
        for (PortWatchClient.ChokepointDay d : latest) {
            Integer prior = weekAgo.get(d.name());
            Double delta = prior == null || prior <= 0 || d.nTotal() < 0
                    ? null : (d.nTotal() - prior) * 100.0 / prior;
            out.add(new ChokepointStat(d.name(), d.date().toString(),
                    d.nTotal() < 0 ? null : d.nTotal(), delta));
        }
        return out;
    }

    private OilStockStat oilStocks() {
        EiaWpsrClient client = eiaWpsrClient;
        if (client == null) return null;
        return client.latest().map(w -> new OilStockStat(w.weekEnding().toString(),
                nanToNull(w.commercialCrudeMb()), nanToNull(w.commercialCrudeDeltaMb()),
                nanToNull(w.sprMb()), nanToNull(w.sprDeltaMb()),
                nanToNull(w.gasolineMb()), nanToNull(w.gasolineDeltaMb()),
                nanToNull(w.distillateMb()), nanToNull(w.distillateDeltaMb())))
                .orElse(null);
    }

    /** Weekly points kept for the freight curve figure. */
    static final int FREIGHT_SERIES_WEEKS = 60;

    private FreightStat freight() {
        HarpexClient client = harpexClient;
        if (client == null) return null;
        List<HarpexClient.HarpexPoint> points = client.latest(FREIGHT_SERIES_WEEKS);
        if (points.isEmpty()) return null;
        HarpexClient.HarpexPoint last = points.get(points.size() - 1);
        Double weekAgo = points.size() > 1 ? points.get(points.size() - 2).value() : null;
        List<Double> series = new ArrayList<>(points.size());
        for (HarpexClient.HarpexPoint p : points) series.add(p.value());
        return new FreightStat(last.value(), weekAgo, last.date().toString(), series);
    }

    private PowerStat power() {
        EnergyChartsClient client = energyChartsClient;
        if (client == null) return null;
        EnergyChartsClient.PriceStats price = client.priceToday().orElse(null);
        EnergyChartsClient.PowerMix mix = client.currentMix().orElse(null);
        if (price == null && mix == null) return null;
        List<Double> series = guarded("power series", List.of(), client::priceSeries);
        return new PowerStat(
                price == null ? null : nanToNull(price.currentEurMwh()),
                price == null ? null : nanToNull(price.minEurMwh()),
                price == null ? null : nanToNull(price.maxEurMwh()),
                price == null ? null : nanToNull(price.avgEurMwh()),
                mix == null ? null : nanToNull(mix.renewableSharePercent()),
                mix == null ? null : mix.topSource(), series);
    }

    private SpaceWxStat spaceWx() {
        SpaceWeatherClient client = spaceWeatherClient;
        if (client == null) return null;
        return client.latest().map(s -> new SpaceWxStat(
                s.r() < 0 ? null : s.r(), s.s() < 0 ? null : s.s(),
                s.g() < 0 ? null : s.g(),
                s.forecastMaxG() < 0 ? null : s.forecastMaxG())).orElse(null);
    }

    /**
     * Today's policy-wire items across the desks, each capped alone. EU
     * sanction updates ride here too — but only a regime AMENDED today is an
     * event (the standing register would repeat as noise every evening).
     */
    private List<PolicyStat> policyWire(LocalDate today, Instant dayStart) {
        List<PolicyStat> out = new ArrayList<>();
        FedFeedsClient fed = fedFeedsClient;
        if (fed != null) {
            guardedRun("fed wire", () -> {
                for (FedFeedsClient.Item it : fed.pressReleases(20)) {
                    addPolicy(out, "FED", it.title(), it.publishedAt(), dayStart);
                }
                for (FedFeedsClient.Item it : fed.speeches(10)) {
                    addPolicy(out, "FED", it.title(), it.publishedAt(), dayStart);
                }
            });
        }
        EcbFeedsClient ecb = ecbFeedsClient;
        if (ecb != null) {
            guardedRun("ecb wire", () -> {
                for (EcbFeedsClient.PressItem it : ecb.press(20)) {
                    addPolicy(out, "EZB", it.title(), it.publishedAt(), dayStart);
                }
            });
        }
        WhiteHouseActionsClient wh = whiteHouseClient;
        if (wh != null) {
            guardedRun("white house wire", () -> {
                for (WhiteHouseActionsClient.Action it : wh.actions(15)) {
                    addPolicy(out, "WHITE_HOUSE", it.title(), it.publishedAt(), dayStart);
                }
            });
        }
        FederalRegisterClient fedReg = federalRegisterClient;
        if (fedReg != null) {
            guardedRun("federal register wire", () -> {
                // Date-only feed: today's presidential documents, no HH:mm.
                for (FederalRegisterClient.Doc d : fedReg.presidentialDocuments(10)) {
                    if (!today.equals(d.publicationDate()) || d.title() == null) continue;
                    if (countBySource(out, "FEDERAL_REGISTER") >= MAX_POLICY_PER_SOURCE) break;
                    out.add(new PolicyStat("FEDERAL_REGISTER", d.title().strip(), null));
                }
            });
        }
        EuPresscornerClient eu = euPresscornerClient;
        if (eu != null) {
            guardedRun("eu presscorner wire", () -> {
                for (EuPresscornerClient.Item it : eu.items(10)) {
                    addPolicy(out, "EU_KOMMISSION", it.title(), it.publishedAt(), dayStart);
                }
            });
        }
        SanctionsMapClient sanctions = sanctionsMapClient;
        if (sanctions != null) {
            guardedRun("eu sanctions wire", () -> {
                for (SanctionsMapClient.Regime r : sanctions.regimes()) {
                    if (!today.equals(r.lastUpdate())) continue;
                    if (countBySource(out, "EU_SANKTIONEN") >= MAX_POLICY_PER_SOURCE) break;
                    String scope = r.country() != null && !r.country().isBlank()
                            ? r.country() : r.specification();
                    out.add(new PolicyStat("EU_SANKTIONEN",
                            "Sanktionsregime aktualisiert: " + scope
                                    + " (" + r.measuresCount() + " Maßnahmen)", null));
                }
            });
        }
        return out;
    }

    private static void addPolicy(List<PolicyStat> out, String source, String title,
            Instant publishedAt, Instant dayStart) {
        if (title == null || title.isBlank()) return;
        if (publishedAt == null || publishedAt.isBefore(dayStart)) return;
        if (countBySource(out, source) >= MAX_POLICY_PER_SOURCE) return;
        out.add(new PolicyStat(source, title.strip(), localTime(publishedAt)));
    }

    private static long countBySource(List<PolicyStat> out, String source) {
        return out.stream().filter(p -> source.equals(p.source())).count();
    }

    /** The freshest poll per parliament (dawum), 14-day cut, newest first. */
    private List<PollStat> polls(LocalDate today) {
        DawumClient client = dawumClient;
        if (client == null) return List.of();
        List<PollStat> out = new ArrayList<>();
        for (DawumClient.Survey s : client.latest(1)) {
            if (s.date() == null || s.date().isBefore(today.minusDays(14))) continue;
            List<Map.Entry<String, Double>> ranked = s.results().entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(8)
                    .toList();
            String topline = ranked.stream().limit(5)
                    .map(e -> e.getKey() + " " + pollPercent(e.getValue()))
                    .collect(Collectors.joining(", "));
            if (topline.isBlank()) continue;
            Map<String, Double> results = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Double> e : ranked) results.put(e.getKey(), e.getValue());
            out.add(new PollStat(s.parliament(), s.institute(), s.date().toString(),
                    topline, results));
        }
        out.sort(Comparator.comparing(PollStat::dateIso).reversed());
        return out.size() > MAX_POLLS ? List.copyOf(out.subList(0, MAX_POLLS)) : out;
    }

    /** German percent style, integer polls without a decimal ("24 %", "4,5 %"). */
    private static String pollPercent(double value) {
        if (value == Math.floor(value)) return String.format(Locale.ROOT, "%.0f %%", value);
        return String.format(Locale.ROOT, "%.1f %%", value).replace('.', ',');
    }

    /**
     * Today's civic layer: presseportal (every channel sampled, Blaulicht
     * widest) plus the traffic wire — Autobahn closures/warnings on the
     * single-digit national arteries and Munich transit disruptions.
     */
    private List<CivicStat> civicWire(Instant dayStart) {
        List<CivicStat> out = new ArrayList<>();
        PresseportalClient client = presseportalClient;
        if (client != null) {
            for (PresseportalClient.Channel ch : PresseportalClient.Channel.values()) {
                int chCap = ch == PresseportalClient.Channel.BLAULICHT
                        ? MAX_CIVIC_BLAULICHT : MAX_CIVIC_PER_CHANNEL;
                out.addAll(guarded("presseportal " + ch, List.of(),
                        () -> civicChannel(client, ch, chCap, dayStart)));
            }
        }
        TrafficClient traffic = trafficClient;
        if (traffic != null) {
            guardedRun("autobahn wire", () -> {
                int n = 0;
                for (TrafficClient.RoadEvent e : traffic.autobahnDisruptions(AUTOBAHN_ARTERIES)) {
                    if (e.title() == null || e.title().isBlank()) continue;
                    boolean startedToday = e.startedAt() != null
                            && !e.startedAt().isBefore(dayStart);
                    out.add(new CivicStat("AUTOBAHN", e.road(), e.title().strip(),
                            startedToday ? localTime(e.startedAt()) : null));
                    if (++n >= MAX_CIVIC_TRAFFIC) break;
                }
            });
            guardedRun("mvg wire", () -> {
                int n = 0;
                for (TrafficClient.TransitMessage msg : traffic.mvgMessages()) {
                    if (msg.publishedAt() == null || msg.publishedAt().isBefore(dayStart)) continue;
                    if (msg.title() == null || msg.title().isBlank()) continue;
                    out.add(new CivicStat("MVG", null, msg.title().strip(),
                            localTime(msg.publishedAt())));
                    if (++n >= MAX_CIVIC_TRAFFIC) break;
                }
            });
        }
        if (out.size() > MAX_CIVIC) {
            LOG.info("[WORLD] civic wire: {} today-items over freeze cap {}, "
                    + "dropping the tail", out.size(), MAX_CIVIC);
            return List.copyOf(out.subList(0, MAX_CIVIC));
        }
        return out;
    }

    private static List<CivicStat> civicChannel(PresseportalClient client,
            PresseportalClient.Channel ch, int chCap, Instant dayStart) {
        List<CivicStat> lines = new ArrayList<>();
        for (PresseportalClient.Item it : client.channel(ch, 80)) {
            if (it.publishedAt() == null || it.publishedAt().isBefore(dayStart)) continue;
            if (it.title() == null || it.title().isBlank()) continue;
            lines.add(new CivicStat(ch.name(), it.office(), it.title().strip(),
                    localTime(it.publishedAt())));
            if (lines.size() >= chCap) break;
        }
        return lines;
    }

    /** DIVI ICU occupancy + RKI ARE incidence + the newest WHO outbreak notices. */
    private HealthStat health() {
        Double icu = null;
        DiviClient divi = diviClient;
        if (divi != null) {
            icu = guarded("divi", null, () -> {
                long occupied = 0;
                long free = 0;
                for (DiviClient.StateIcu s : divi.states()) {
                    if (s.occupied() >= 0 && s.free() >= 0) {
                        occupied += s.occupied();
                        free += s.free();
                    }
                }
                return occupied + free > 0 ? occupied * 100.0 / (occupied + free) : null;
            });
        }
        Double are = null;
        String areWeek = null;
        List<Double> areSeries = List.of();
        RkiSurveillanceClient rki = rkiClient;
        if (rki != null) {
            List<RkiSurveillanceClient.WeeklyIncidence> weeks =
                    guarded("rki", List.of(), () -> rki.latest(16));
            if (!weeks.isEmpty()) {
                RkiSurveillanceClient.WeeklyIncidence w = weeks.get(weeks.size() - 1);
                are = w.incidence();
                areWeek = w.isoWeek();
                List<Double> series = new ArrayList<>(weeks.size());
                for (RkiSurveillanceClient.WeeklyIncidence wk : weeks) {
                    series.add(wk.incidence());
                }
                areSeries = series;
            }
        }
        List<String> outbreaks = List.of();
        WhoOutbreakClient who = whoOutbreakClient;
        if (who != null) {
            outbreaks = guarded("who outbreaks", List.of(),
                    () -> who.latest(MAX_OUTBREAKS).stream()
                            .filter(o -> o.title() != null && !o.title().isBlank())
                            .map(o -> (o.publishedAt() == null ? ""
                                    : LocalDate.ofInstant(o.publishedAt(),
                                            ZoneId.systemDefault()) + " - ")
                                    + o.title().strip())
                            .toList());
        }
        if (icu == null && are == null && outbreaks.isEmpty()) return null;
        return new HealthStat(icu, are, areWeek, outbreaks, areSeries);
    }

    /** CVEs newly listed as actively exploited (CISA KEV, last 3 days). */
    private List<CyberStat> cyber() {
        CisaKevClient client = cisaKevClient;
        if (client == null) return List.of();
        List<CyberStat> out = new ArrayList<>();
        for (CisaKevClient.Kev k : client.recentlyAdded(3)) {
            String vendorProduct = ((k.vendorProject() == null ? "" : k.vendorProject())
                    + " " + (k.product() == null ? "" : k.product())).strip();
            out.add(new CyberStat(k.cveID(), vendorProduct,
                    k.dateAdded() == null ? null : k.dateAdded().toString()));
            if (out.size() >= MAX_CYBER) break;
        }
        return out;
    }

    /** Tomorrow's German football fixtures — schedule facts for the outlook. */
    private List<String> sportsTomorrow(LocalDate today, ZoneId zone) {
        SportsCalendarClient client = sportsClient;
        if (client == null) return List.of();
        LocalDate tomorrow = today.plusDays(1);
        List<String> out = new ArrayList<>();
        for (SportsCalendarClient.Fixture f : client.upcomingDefault()) {
            if (f.kickoff() == null) continue;
            if (!tomorrow.equals(LocalDate.ofInstant(f.kickoff(), zone))) continue;
            String league = LEAGUE_LABELS.getOrDefault(f.league(), f.league());
            out.add(league + ": " + f.home() + " - " + f.away()
                    + " (" + localTime(f.kickoff()) + ")");
            if (out.size() >= MAX_SPORTS) break;
        }
        return out;
    }

    /** The holiday board: next public holiday, tomorrow flag, school-holiday states. */
    private HolidayStat holidays(LocalDate today) {
        HolidayCalendarClient client = holidayClient;
        if (client == null) return null;
        List<HolidayCalendarClient.PublicHoliday> upcoming =
                guarded("public holidays", List.of(), () -> client.upcomingPublic(3));
        HolidayCalendarClient.PublicHoliday next = upcoming.isEmpty() ? null : upcoming.get(0);
        boolean tomorrowHoliday = upcoming.stream().anyMatch(h ->
                h.nationwide() && today.plusDays(1).equals(h.date()));
        List<String> schoolStates = guarded("school holidays", List.of(), () -> {
            List<String> states = new ArrayList<>();
            for (String code : HolidayCalendarClient.STATE_CODES) {
                HolidayCalendarClient.SchoolHoliday sh = client.currentOrNextSchoolHoliday(code);
                if (sh != null && sh.start() != null && sh.end() != null
                        && !today.isBefore(sh.start()) && !today.isAfter(sh.end())) {
                    states.add(code);
                }
            }
            return states;
        });
        if (next == null && !tomorrowHoliday && schoolStates.isEmpty()) return null;
        return new HolidayStat(next == null ? null : next.name(),
                next == null ? null : next.date().toString(), tomorrowHoliday, schoolStates);
    }

    /**
     * Today's armed-conflict/attack events (Wikipedia Current Events portal,
     * attributed), geocoded COUNTRY-LEVEL by the deterministic
     * {@link WorldGeo#locate} — the map's war layer (user mandate 2026-07-15:
     * a conflict beside a chokepoint whose transit delta shows the effect,
     * the Hormuz case). Events without a locatable country still ride along
     * (list + judges see them; only the map skips them).
     */
    private List<ConflictStat> conflicts(LocalDate today) {
        WikipediaCurrentEventsClient client = wikipediaClient;
        if (client == null) return List.of();
        List<ConflictStat> out = new ArrayList<>();
        for (WikipediaCurrentEventsClient.WorldEvent e : client.eventsOn(today, MAX_CONFLICTS)) {
            String category = e.category() == null ? "" : e.category().toLowerCase(Locale.ROOT);
            if (!category.contains("conflict") && !category.contains("attack")) continue;
            if (e.text() == null || e.text().isBlank()) continue;
            WorldGeo.Located hit = WorldGeo.locate(e.text());
            out.add(new ConflictStat(hit == null ? null : hit.country(),
                    e.text().strip(), e.source(),
                    hit == null ? null : hit.lat(), hit == null ? null : hit.lon()));
            if (out.size() >= MAX_CONFLICTS) break;
        }
        return out;
    }

    // --- small helpers -------------------------------------------------------

    private <T> T guarded(String what, T fallback, Supplier<T> leg) {
        try {
            return leg.get();
        } catch (Exception e) {
            LOG.warn("World-signal leg '{}' failed: {}", what, e.getMessage());
            return fallback;
        }
    }

    private void guardedRun(String what, Runnable leg) {
        guarded(what, null, () -> {
            leg.run();
            return null;
        });
    }

    private static String localTime(Instant instant) {
        return LocalTime.ofInstant(instant, ZoneId.systemDefault()).format(HOUR_MINUTE);
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
