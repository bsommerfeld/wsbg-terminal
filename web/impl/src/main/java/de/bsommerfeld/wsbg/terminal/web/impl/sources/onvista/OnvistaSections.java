package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared onvista SECTION parsers and their records — the pieces the old
 * {@code OnvistaMarketClient} and {@code OnvistaFundamentalsClient} carried
 * and that {@link OnvistaPageBundle} still needs: venue quotes with the
 * RLT/DLY quality flag, the row-shaped EOD block the page embeds, the company
 * snapshot, the figures panel, the message board and the fund holders. The
 * records are exactly the old ones; only the carrying class changed, because
 * the dedicated per-endpoint clients did not survive the web migration (their
 * facts leg lives in {@link OnvistaFactsSource}).
 *
 * @see OnvistaApi for transport, rate discipline and the terms-of-service note
 */
public final class OnvistaSections {

    private OnvistaSections() {}

    // ---------------------------------------------------------------- quotes

    /**
     * One venue's live line. {@code quality} is onvista's {@code codeQualityPrice}:
     * {@code RLT} = realtime, {@code DLY} = delayed (and {@code qualityBidAsk}
     * says the same for the book side — a venue can be realtime on the trade
     * and delayed on the quote).
     */
    public record VenueQuote(String marketName, String codeMarket, String codeExchange,
            long idNotation, String isoCountry, String isoCurrency,
            double last, double bid, double ask, double volumeBid, double volumeAsk,
            double open, double high, double low, double previousLast,
            double performancePct, double totalVolume, double volume4Weeks,
            double high1Year, double low1Year, double performance1YearPct,
            String quality, String qualityBidAsk, Instant datetimeLast) {

        /** True when this venue's LAST price is realtime rather than delayed. */
        public boolean realtime() {
            return "RLT".equalsIgnoreCase(quality);
        }

        /** True when bid/ask are realtime too. */
        public boolean realtimeBook() {
            return "RLT".equalsIgnoreCase(qualityBidAsk);
        }

        /** Bid-ask spread in percent of the mid, NaN when the book is empty. */
        public double spreadPct() {
            if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0 || ask <= 0) {
                return Double.NaN;
            }
            return (ask - bid) / ((ask + bid) / 2.0) * 100.0;
        }
    }

    /** Package-private, network-free: a {@code list} of quote rows → venues. */
    static List<VenueQuote> parseQuotes(JsonNode root) {
        List<VenueQuote> out = new ArrayList<>();
        for (JsonNode q : OnvistaApi.listOf(root, "list")) {
            JsonNode m = q.path("market");
            String name = OnvistaApi.text(m, "name");
            if (name == null) continue;
            out.add(new VenueQuote(name, OnvistaApi.text(m, "codeMarket"),
                    OnvistaApi.text(m, "codeExchange"),
                    m.path("idNotation").asLong(0), OnvistaApi.text(m, "isoCountry"),
                    OnvistaApi.text(q, "isoCurrency"),
                    OnvistaApi.num(q, "last"), OnvistaApi.num(q, "bid"), OnvistaApi.num(q, "ask"),
                    OnvistaApi.num(q, "volumeBid"), OnvistaApi.num(q, "volumeAsk"),
                    OnvistaApi.num(q, "open"), OnvistaApi.num(q, "high"),
                    OnvistaApi.num(q, "low"), OnvistaApi.num(q, "previousLast"),
                    OnvistaApi.num(q, "performancePct"), OnvistaApi.num(q, "totalVolume"),
                    OnvistaApi.num(q, "volume4Weeks"),
                    OnvistaApi.num(q, "highPrice1Year"), OnvistaApi.num(q, "lowPrice1Year"),
                    OnvistaApi.num(q, "performance1YearPct"),
                    OnvistaApi.text(q, "codeQualityPrice"),
                    OnvistaApi.text(q, "codeQualityPriceBidAsk"),
                    OnvistaApi.instant(q, "datetimeLast")));
        }
        return List.copyOf(out);
    }

    // ----------------------------------------------------------- EOD history

    /** One daily bar. {@code open} is onvista's {@code first}, {@code close} its {@code last}. */
    public record EodBar(LocalDate date, double open, double high, double low,
            double close, double volume) {}

    /**
     * A venue's daily history plus the window onvista itself declares available.
     * {@code startAvailable} is the value the (old) two-stage API read fed back
     * as {@code startDate}.
     */
    public record EodHistory(String marketName, long idNotation, String isoCurrency,
            LocalDate startAvailable, LocalDate endAvailable, List<EodBar> bars) {

        public boolean isEmpty() {
            return bars.isEmpty();
        }
    }

    /**
     * Package-private, network-free: the ROW-shaped EOD block the page bundle
     * carries ({@code {history:[{datetime,first,last,high,low,volume}…]}}),
     * as opposed to the API's columnar arrays. Same record out, so a caller
     * cannot tell which road the bars came down.
     */
    static Optional<EodHistory> parseEodRows(JsonNode node) {
        if (node == null) return Optional.empty();
        JsonNode rows = OnvistaApi.listOf(node, "history");
        if (rows.isEmpty()) return Optional.empty();
        List<EodBar> bars = new ArrayList<>(rows.size());
        for (JsonNode r : rows) {
            Instant at = OnvistaApi.instant(r, "datetime");
            if (at == null) continue;
            bars.add(new EodBar(OnvistaApi.dayOf(at), OnvistaApi.num(r, "first"),
                    OnvistaApi.num(r, "high"), OnvistaApi.num(r, "low"),
                    OnvistaApi.num(r, "last"), OnvistaApi.num(r, "volume")));
        }
        if (bars.isEmpty()) return Optional.empty();
        return Optional.of(new EodHistory(OnvistaApi.text(node.path("market"), "name"),
                node.path("idNotation").asLong(0), OnvistaApi.text(node, "isoCurrency"),
                OnvistaApi.dayOf(OnvistaApi.instant(node, "datetimeStartAvailableHistory")),
                OnvistaApi.dayOf(OnvistaApi.instant(node, "datetimeEndAvailableHistory")),
                List.copyOf(bars)));
    }

    // ------------------------------------------------------- figures panel

    /**
     * One fiscal year of figures, keyed exactly as onvista names its fields —
     * no curated field list, the caller asks for what it needs.
     * {@code estimate} is true for the projection years, which onvista marks
     * with a trailing {@code e} in the label ({@code "2026e"}, {@code "26/27e"}).
     */
    public record FiscalYear(String label, int year, boolean estimate,
            String isoCurrency, Map<String, Double> figures) {

        /** A figure by its onvista name, NaN when this year does not carry it. */
        public double get(String name) {
            Double v = figures.get(name);
            return v == null ? Double.NaN : v;
        }
    }

    /** The technical panel: moving averages, relative strength, momentum, volatility. */
    public record TechnicalPanel(long idNotation, Instant calculatedAt,
            Map<String, Double> figures) {

        public double get(String name) {
            Double v = figures.get(name);
            return v == null ? Double.NaN : v;
        }

        public double movingAverage(int days) {
            return get("movingAverage" + days);
        }

        public double rsi(int days) {
            return get("relativeStrengthIndexWilder" + days);
        }

        public double volatility(int days) {
            return get("volatility" + days);
        }
    }

    /** Beta and correlation of the share against ONE reference index. */
    public record BenchmarkFit(OnvistaEntity index, boolean isDefault,
            double beta30, double beta250, double correlation30, double correlation250,
            double outperformance1W, double outperformance1M, double outperformance1Y) {}

    /**
     * The complete figures panel: balance sheets, fundamentals INCLUDING the
     * estimate years, growth rates, margins/returns, the technical panel and
     * the benchmark fits. SAP 2026-08-02: 10 balance-sheet years, 13 fiscal
     * years 2016–2028 of which the last three are estimates, and five benchmark
     * indices (DAX, CDAX, EURO STOXX 50, ATX …).
     */
    public record Figures(List<FiscalYear> balanceSheet, List<FiscalYear> fundamentals,
            List<FiscalYear> growth, List<FiscalYear> financials,
            TechnicalPanel technical, List<BenchmarkFit> benchmarks) {

        /** The estimate years only, nearest first. */
        public List<FiscalYear> estimates() {
            return fundamentals.stream().filter(FiscalYear::estimate).toList();
        }

        /** The reported years only, oldest first. */
        public List<FiscalYear> actuals() {
            return fundamentals.stream().filter(f -> !f.estimate()).toList();
        }

        /** The most recent REPORTED fiscal year, empty when a name has none. */
        public Optional<FiscalYear> lastActual() {
            List<FiscalYear> a = actuals();
            return a.isEmpty() ? Optional.empty() : Optional.of(a.get(a.size() - 1));
        }

        /** The benchmark onvista itself treats as the primary reference. */
        public Optional<BenchmarkFit> defaultBenchmark() {
            return benchmarks.stream().filter(BenchmarkFit::isDefault).findFirst();
        }
    }

    /** Package-private, network-free. */
    static Optional<Figures> parseFigures(JsonNode root) {
        if (root == null) return Optional.empty();
        List<FiscalYear> balance = parseFiscalYears(root, "stocksBalanceSheetList");
        List<FiscalYear> fundamentals = parseFiscalYears(root, "stocksCnFundamentalList");
        List<FiscalYear> growth = parseFiscalYears(root, "stocksCnGrowthList");
        List<FiscalYear> financials = parseFiscalYears(root, "stocksCnFinancialList");
        TechnicalPanel technical = parseTechnical(root.path("stocksCnTechnical"));
        List<BenchmarkFit> benchmarks = parseBenchmarks(root);
        if (balance.isEmpty() && fundamentals.isEmpty() && technical == null
                && benchmarks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Figures(balance, fundamentals, growth, financials,
                technical, benchmarks));
    }

    /** Package-private, network-free: a fiscal-year list under {@code field}. */
    static List<FiscalYear> parseFiscalYears(JsonNode root, String field) {
        String parentCurrency = root == null ? null
                : OnvistaApi.text(root.path(field), "isoCurrency");
        List<FiscalYear> out = new ArrayList<>();
        for (JsonNode r : OnvistaApi.listOf(root, field)) {
            String label = OnvistaApi.text(r, "label");
            if (label == null) continue;
            Map<String, Double> figures = new LinkedHashMap<>();
            r.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) figures.put(e.getKey(), e.getValue().asDouble());
            });
            String currency = OnvistaApi.text(r, "isoCurrency");
            out.add(new FiscalYear(label, OnvistaApi.intOr(r, "idYear", -1),
                    // onvista marks projections with a trailing 'e' on the label
                    // ("2026e", "26/27e") — that suffix is the ONLY estimate marker.
                    label.endsWith("e"),
                    currency != null ? currency : parentCurrency, Map.copyOf(figures)));
        }
        return List.copyOf(out);
    }

    /** Package-private, network-free. */
    static TechnicalPanel parseTechnical(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Map<String, Double> figures = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isNumber()
                    && !"idNotation".equals(e.getKey()) && !"idInstrument".equals(e.getKey())) {
                figures.put(e.getKey(), e.getValue().asDouble());
            }
        });
        if (figures.isEmpty()) return null;
        return new TechnicalPanel(node.path("idNotation").asLong(0),
                OnvistaApi.instant(node, "datetimeCalculation"), Map.copyOf(figures));
    }

    /** Package-private, network-free. */
    static List<BenchmarkFit> parseBenchmarks(JsonNode root) {
        List<BenchmarkFit> out = new ArrayList<>();
        for (JsonNode b : OnvistaApi.listOf(root, "stocksBenchmarkList")) {
            OnvistaEntity idx = OnvistaApi.entityOf(b.path("instrument"));
            if (idx == null) continue;
            out.add(new BenchmarkFit(idx, b.path("default").asBoolean(false),
                    OnvistaApi.num(b, "cnBeta30"), OnvistaApi.num(b, "cnBeta250"),
                    OnvistaApi.num(b, "cnKor30"), OnvistaApi.num(b, "cnKor250"),
                    OnvistaApi.num(b, "cnOpb1W"), OnvistaApi.num(b, "cnOpb1M"),
                    OnvistaApi.num(b, "cnOpb1Y")));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------ company snapshot

    /** One shareholder and its stake. {@code Streubesitz} rides along as a row. */
    public record Shareholder(String name, double percentage) {}

    /**
     * One board member. {@code bio} is EQS's HTML biography where onvista has
     * one (management and supervisory board), otherwise null.
     */
    public record BoardMember(String fullName, String title, String function,
            String bio, String photoUrl) {}

    /** A stake the company itself holds in another listed company. */
    public record Participation(String companyName, double sharePercentage,
            OnvistaEntity instrument) {}

    /**
     * One EQS report PDF: the primary source, dated and directly downloadable.
     * German and English editions appear as separate rows with the same date.
     */
    public record CompanyDocument(String title, String url, Instant date) {}

    /**
     * The company snapshot: who owns it, who runs it, what it holds, and its
     * filed reports.
     */
    public record CompanySnapshot(String companyName, String officialName,
            String companyType, String country, String branch, String sector,
            String website, String profileText,
            double numShares, double marketCap, String isoCurrency,
            double freeFloatPct, String fiscalYearEnd, int lastBalanceYear,
            List<Shareholder> shareholders,
            List<BoardMember> managingBoard, List<BoardMember> supervisoryBoard,
            List<Participation> participations,
            List<CompanyDocument> documents) {

        /** The free-float row of the shareholder table, if onvista carries one. */
        public Optional<Shareholder> freeFloat() {
            return shareholders.stream()
                    .filter(s -> s.name() != null && s.name().toLowerCase().contains("streubesitz"))
                    .findFirst();
        }

        /** The named holders only — the shareholder structure without free float. */
        public List<Shareholder> namedHolders() {
            return shareholders.stream()
                    .filter(s -> s.name() == null || !s.name().toLowerCase().contains("streubesitz"))
                    .toList();
        }

        /** The report PDFs, newest first. */
        public List<CompanyDocument> documentsNewestFirst() {
            List<CompanyDocument> copy = new ArrayList<>(documents);
            copy.sort((a, b) -> {
                if (a.date() == null && b.date() == null) return 0;
                if (a.date() == null) return 1;
                if (b.date() == null) return -1;
                return b.date().compareTo(a.date());
            });
            return List.copyOf(copy);
        }
    }

    /** Package-private, network-free. */
    static Optional<CompanySnapshot> parseCompanySnapshot(JsonNode root) {
        if (root == null) return Optional.empty();
        JsonNode desc = root.path("companyDescriptor");
        JsonNode company = desc.path("company");
        JsonNode branch = company.path("branch");
        String name = OnvistaApi.text(company, "name");
        String official = OnvistaApi.text(desc, "nameCompanyFull");
        if (name == null && official == null) return Optional.empty();

        List<Shareholder> holders = new ArrayList<>();
        for (JsonNode h : OnvistaApi.listOf(root, "companyOwnerList")) {
            String hn = OnvistaApi.text(h, "name");
            if (hn != null) holders.add(new Shareholder(hn, OnvistaApi.num(h, "percentage")));
        }

        JsonNode eqs = root.path("eqsCompanyInfo");
        List<BoardMember> managing = parseBoard(root.path("managingCommittee"),
                eqs.path("managingCommittee"));
        List<BoardMember> supervisory = parseBoard(root.path("supervisorBoard"),
                eqs.path("supervisorBoard"));

        List<Participation> participations = new ArrayList<>();
        for (JsonNode p : OnvistaApi.listOf(root, "companyParticipationList")) {
            String pn = OnvistaApi.text(p.path("company"), "name");
            if (pn == null) continue;
            participations.add(new Participation(pn, OnvistaApi.num(p, "sharePercentage"),
                    OnvistaApi.entityOf(p.path("instrument"))));
        }

        List<CompanyDocument> docs = new ArrayList<>();
        for (JsonNode d : OnvistaApi.listOf(eqs, "companyDocumentList")) {
            String url = OnvistaApi.text(d, "url");
            if (url == null) continue;
            docs.add(new CompanyDocument(OnvistaApi.text(d, "title"), url,
                    OnvistaApi.instant(d, "date")));
        }

        String profile = null;
        JsonNode profileNode = root.path("profile");
        if (profileNode.isArray() && profileNode.size() > 0) {
            profile = OnvistaApi.text(profileNode.get(0), "value");
        }

        return Optional.of(new CompanySnapshot(name, official,
                OnvistaApi.text(desc, "nameCompanyType"), OnvistaApi.text(company, "nameCountry"),
                OnvistaApi.text(branch, "name"), OnvistaApi.text(branch.path("sector"), "name"),
                OnvistaApi.text(desc, "url"), profile,
                OnvistaApi.num(desc, "numShares"), OnvistaApi.num(desc, "marketCap"),
                OnvistaApi.text(desc, "isoCurrency"),
                OnvistaApi.num(desc, "freeFloat"), OnvistaApi.text(desc, "yearEndFiscal"),
                OnvistaApi.intOr(desc, "yearLastBalance", -1),
                List.copyOf(holders), managing, supervisory,
                List.copyOf(participations), List.copyOf(docs)));
    }

    /**
     * Board members from the plain list, enriched with the EQS biography where
     * the names line up. onvista serves the same board twice: a structured list
     * ({@code nameFirst}/{@code nameLast}/{@code nameFunction}) and an EQS list
     * that adds {@code description} (an HTML bio) and a portrait.
     */
    private static List<BoardMember> parseBoard(JsonNode plain, JsonNode eqs) {
        Map<String, JsonNode> bios = new LinkedHashMap<>();
        for (JsonNode e : OnvistaApi.listOf(eqs, "list")) {
            String full = OnvistaApi.text(e, "fullName");
            if (full != null) bios.put(full.toLowerCase(), e);
        }
        List<BoardMember> out = new ArrayList<>();
        for (JsonNode m : OnvistaApi.listOf(plain, "list")) {
            String full = OnvistaApi.text(m, "fullName");
            if (full == null) {
                String first = OnvistaApi.text(m, "nameFirst");
                String last = OnvistaApi.text(m, "nameLast");
                full = first == null ? last : (last == null ? first : first + " " + last);
            }
            if (full == null) continue;
            JsonNode bio = bios.remove(full.toLowerCase());
            out.add(new BoardMember(full, OnvistaApi.text(m, "title"),
                    OnvistaApi.text(m, "nameFunction"),
                    bio == null ? null : OnvistaApi.text(bio, "description"),
                    bio == null ? null : OnvistaApi.text(bio.path("photo"), "photoUrl")));
        }
        // EQS-only members (present in the bio list, absent from the plain one).
        for (JsonNode e : bios.values()) {
            out.add(new BoardMember(OnvistaApi.text(e, "fullName"), OnvistaApi.text(e, "title"),
                    OnvistaApi.text(e, "nameFunction"), OnvistaApi.text(e, "description"),
                    OnvistaApi.text(e.path("photo"), "photoUrl")));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------ the board

    /**
     * One onvista message-board thread. {@code hitsToday} is the interesting
     * one: it separates a thread that is alive TODAY from a big old thread.
     */
    public record ForumThread(int position, String subject, String url,
            int answersTotal, int hitsToday, int rating, Instant lastPost) {}

    /** The message board's threads for a stock, with today's traffic. */
    public record ForumBoard(String forumUrl, List<ForumThread> threads) {

        /** Combined hits across all listed threads today — a crude attention gauge. */
        public int hitsToday() {
            return threads.stream().mapToInt(ForumThread::hitsToday).sum();
        }
    }

    /** Package-private, network-free. */
    static Optional<ForumBoard> parseForum(JsonNode root) {
        if (root == null) return Optional.empty();
        List<ForumThread> out = new ArrayList<>();
        JsonNode items = root.path("items");
        if (!items.isArray()) items = root.path("list");
        if (items.isArray()) {
            for (JsonNode t : items) {
                String subject = OnvistaApi.text(t, "subject");
                if (subject == null) continue;
                out.add(new ForumThread(OnvistaApi.intOr(t, "position", -1), subject,
                        OnvistaApi.text(t, "url"),
                        OnvistaApi.intOr(t, "answersTotal", 0), OnvistaApi.intOr(t, "hitsToday", 0),
                        OnvistaApi.intOr(t, "rating", 0), OnvistaApi.instant(t, "lastPostingDate")));
            }
        }
        if (out.isEmpty() && OnvistaApi.text(root, "urlForum") == null) return Optional.empty();
        return Optional.of(new ForumBoard(OnvistaApi.text(root, "urlForum"), List.copyOf(out)));
    }

    // ------------------------------------------------------- holders (funds)

    /** One fund/ETF holding the share, with the weight it gives it. */
    public record FundHolder(OnvistaEntity fund, String issuer, double investmentPct,
            double ongoingCharges, double performancePct1Y, double volumeFundEuro) {}

    /** Package-private, network-free. */
    static List<FundHolder> parseFundHolders(JsonNode root) {
        List<FundHolder> out = new ArrayList<>();
        for (JsonNode f : OnvistaApi.listOf(root, "list")) {
            OnvistaEntity fund = OnvistaApi.entityOf(f.path("instrument"));
            if (fund == null) continue;
            out.add(new FundHolder(fund, OnvistaApi.text(f.path("fundsIssuer"), "name"),
                    OnvistaApi.num(f, "investmentPct"), OnvistaApi.num(f, "ongoingCharges"),
                    OnvistaApi.num(f, "performancePct1Y"), OnvistaApi.num(f, "volumeFundEuro")));
        }
        return List.copyOf(out);
    }
}
