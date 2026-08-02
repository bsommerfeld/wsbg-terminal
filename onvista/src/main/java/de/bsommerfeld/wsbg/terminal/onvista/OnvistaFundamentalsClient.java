package de.bsommerfeld.wsbg.terminal.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * onvista's COMPANY side: the analyst consensus, the full figures panel (ten
 * balance-sheet years, fundamentals out to the estimate years, growth, margins,
 * moving averages, RSI, volatility, beta and correlation against several
 * indices), the company snapshot (shareholder structure with percentages,
 * board members with biographies, holdings, and the EQS report-PDF library),
 * the theScreener rating in plain German, the onvista message board, the funds
 * that hold the share, and article full text. Keyless JSON, probed live
 * 2026-08-02 against SAP ({@code STOCK/82849}).
 *
 * <p>The strongest leg for a research pipeline is
 * {@link #companySnapshot(OnvistaEntity)}: its {@code eqsCompanyInfo.companyDocumentList}
 * carries direct EQS PDF links to annual and quarterly reports — 56 dated
 * documents for SAP, German and English side by side — i.e. primary sources
 * addressable by ISIN without scraping an investor-relations site.
 *
 * @see OnvistaApi for transport, the {@code Crawl-delay: 20} discipline and the
 *      terms-of-service note
 */
@Singleton
public class OnvistaFundamentalsClient extends OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaFundamentalsClient.class);

    /** Test/default: plain direct transport. */
    public OnvistaFundamentalsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam. */
    @Inject
    public OnvistaFundamentalsClient(@DirectFirst WebFetcher fetcher) {
        super(fetcher);
    }

    // ------------------------------------------------- analyst recommendations

    /**
     * The sell-side consensus: the rating tally, how many analysts moved since
     * the last count, and the target-price band.
     */
    public record AnalystConsensus(double consensus, int numTotal,
            int numStrongBuy, int numBuy, int numHold, int numSell, int numStrongSell,
            int numPositive, int numNegative,
            int numUpgraded, int numDowngraded, int numUnchanged,
            double ratioPositiveToNegative,
            double avgTargetPrice, double minTargetPrice, double maxTargetPrice,
            String targetCurrency) {

        /** Upside of the average target over {@code price}, in percent; NaN when unknown. */
        public double upsidePct(double price) {
            if (!Double.isFinite(avgTargetPrice) || !Double.isFinite(price) || price <= 0) {
                return Double.NaN;
            }
            return (avgTargetPrice - price) / price * 100.0;
        }

        /** Net analyst drift since the previous count: positive = upgrades outweigh cuts. */
        public int netRevisions() {
            return numUpgraded - numDowngraded;
        }
    }

    /** The analyst consensus for a stock. Empty on any failure. */
    public Optional<AnalystConsensus> analystConsensus(OnvistaEntity entity) {
        if (entity == null) return Optional.empty();
        return parseAnalystConsensus(
                getJson("v1/stocks/" + entity.entityValue() + "/analyzer_recommendations"));
    }

    /** Package-private, network-free. */
    static Optional<AnalystConsensus> parseAnalystConsensus(JsonNode root) {
        if (root == null) return Optional.empty();
        int total = intOr(root, "numTotal", -1);
        if (total < 0) return Optional.empty();
        return Optional.of(new AnalystConsensus(num(root, "recommendationConsensus"), total,
                intOr(root, "numStrongBuy", 0), intOr(root, "numBuy", 0),
                intOr(root, "numHold", 0), intOr(root, "numSell", 0),
                intOr(root, "numStrongSell", 0), intOr(root, "numPositive", 0),
                intOr(root, "numNegative", 0), intOr(root, "numUpgraded", 0),
                intOr(root, "numDowngraded", 0), intOr(root, "numUnchanged", 0),
                num(root, "ratioPositiveToNegative"), num(root, "avgTargetPrice"),
                num(root, "minTargetPrice"), num(root, "maxTargetPrice"),
                text(root, "idCurrencyTargetPrice")));
    }

    // ------------------------------------------------------------- figures

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

    /** The figures panel for a stock. Empty on any failure. */
    public Optional<Figures> figures(OnvistaEntity entity) {
        if (entity == null) return Optional.empty();
        Optional<Figures> f = parseFigures(getJson("v1/stocks/" + entity.entityValue() + "/figures"));
        f.ifPresent(x -> LOG.debug("[onvista] {} figures → {} fiscal year(s), {} estimate(s)",
                entity.pathPair(), x.fundamentals().size(), x.estimates().size()));
        return f;
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
        String parentCurrency = root == null ? null : text(root.path(field), "isoCurrency");
        List<FiscalYear> out = new ArrayList<>();
        for (JsonNode r : listOf(root, field)) {
            String label = text(r, "label");
            if (label == null) continue;
            Map<String, Double> figures = new LinkedHashMap<>();
            r.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) figures.put(e.getKey(), e.getValue().asDouble());
            });
            String currency = text(r, "isoCurrency");
            out.add(new FiscalYear(label, intOr(r, "idYear", -1),
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
                instant(node, "datetimeCalculation"), Map.copyOf(figures));
    }

    /** Package-private, network-free. */
    static List<BenchmarkFit> parseBenchmarks(JsonNode root) {
        List<BenchmarkFit> out = new ArrayList<>();
        for (JsonNode b : listOf(root, "stocksBenchmarkList")) {
            OnvistaEntity idx = entityOf(b.path("instrument"));
            if (idx == null) continue;
            out.add(new BenchmarkFit(idx, b.path("default").asBoolean(false),
                    num(b, "cnBeta30"), num(b, "cnBeta250"),
                    num(b, "cnKor30"), num(b, "cnKor250"),
                    num(b, "cnOpb1W"), num(b, "cnOpb1M"), num(b, "cnOpb1Y")));
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

    /** The company snapshot for a stock. Empty on any failure. */
    public Optional<CompanySnapshot> companySnapshot(OnvistaEntity entity) {
        if (entity == null) return Optional.empty();
        Optional<CompanySnapshot> s = parseCompanySnapshot(
                getJson("v1/stocks/" + entity.entityValue() + "/company_snapshot"));
        s.ifPresent(x -> LOG.debug("[onvista] {} company → {} holder(s), {} report PDF(s)",
                entity.pathPair(), x.shareholders().size(), x.documents().size()));
        return s;
    }

    /** Package-private, network-free. */
    static Optional<CompanySnapshot> parseCompanySnapshot(JsonNode root) {
        if (root == null) return Optional.empty();
        JsonNode desc = root.path("companyDescriptor");
        JsonNode company = desc.path("company");
        JsonNode branch = company.path("branch");
        String name = text(company, "name");
        String official = text(desc, "nameCompanyFull");
        if (name == null && official == null) return Optional.empty();

        List<Shareholder> holders = new ArrayList<>();
        for (JsonNode h : listOf(root, "companyOwnerList")) {
            String hn = text(h, "name");
            if (hn != null) holders.add(new Shareholder(hn, num(h, "percentage")));
        }

        JsonNode eqs = root.path("eqsCompanyInfo");
        List<BoardMember> managing = parseBoard(root.path("managingCommittee"),
                eqs.path("managingCommittee"));
        List<BoardMember> supervisory = parseBoard(root.path("supervisorBoard"),
                eqs.path("supervisorBoard"));

        List<Participation> participations = new ArrayList<>();
        for (JsonNode p : listOf(root, "companyParticipationList")) {
            String pn = text(p.path("company"), "name");
            if (pn == null) continue;
            participations.add(new Participation(pn, num(p, "sharePercentage"),
                    entityOf(p.path("instrument"))));
        }

        List<CompanyDocument> docs = new ArrayList<>();
        for (JsonNode d : listOf(eqs, "companyDocumentList")) {
            String url = text(d, "url");
            if (url == null) continue;
            docs.add(new CompanyDocument(text(d, "title"), url, instant(d, "date")));
        }

        String profile = null;
        JsonNode profileNode = root.path("profile");
        if (profileNode.isArray() && profileNode.size() > 0) {
            profile = text(profileNode.get(0), "value");
        }

        return Optional.of(new CompanySnapshot(name, official,
                text(desc, "nameCompanyType"), text(company, "nameCountry"),
                text(branch, "name"), text(branch.path("sector"), "name"),
                text(desc, "url"), profile,
                num(desc, "numShares"), num(desc, "marketCap"), text(desc, "isoCurrency"),
                num(desc, "freeFloat"), text(desc, "yearEndFiscal"),
                intOr(desc, "yearLastBalance", -1),
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
        for (JsonNode e : listOf(eqs, "list")) {
            String full = text(e, "fullName");
            if (full != null) bios.put(full.toLowerCase(), e);
        }
        List<BoardMember> out = new ArrayList<>();
        for (JsonNode m : listOf(plain, "list")) {
            String full = text(m, "fullName");
            if (full == null) {
                String first = text(m, "nameFirst");
                String last = text(m, "nameLast");
                full = first == null ? last : (last == null ? first : first + " " + last);
            }
            if (full == null) continue;
            JsonNode bio = bios.remove(full.toLowerCase());
            out.add(new BoardMember(full, text(m, "title"), text(m, "nameFunction"),
                    bio == null ? null : text(bio, "description"),
                    bio == null ? null : text(bio.path("photo"), "photoUrl")));
        }
        // EQS-only members (present in the bio list, absent from the plain one).
        for (JsonNode e : bios.values()) {
            out.add(new BoardMember(text(e, "fullName"), text(e, "title"),
                    text(e, "nameFunction"), text(e, "description"),
                    text(e.path("photo"), "photoUrl")));
        }
        return List.copyOf(out);
    }

    // --------------------------------------------------- theScreener rating

    /**
     * One theScreener verdict item. {@code shortText} is the headline verdict,
     * {@code longText} its reasoning — both in plain German, written for a
     * reader rather than for a chart.
     */
    public record RatingItem(String typeItem, String typeDescription, double value,
            int stars, String shortText, String longText) {}

    /** The theScreener rating, split the way onvista splits it. */
    public record ScreenerRating(String fundamentalTitle, List<RatingItem> fundamental,
            String riskTitle, List<RatingItem> risk) {

        /** One item by its onvista {@code typeItem} key. */
        public Optional<RatingItem> item(String typeItem) {
            for (RatingItem i : fundamental) if (i.typeItem().equals(typeItem)) return Optional.of(i);
            for (RatingItem i : risk) if (i.typeItem().equals(typeItem)) return Optional.of(i);
            return Optional.empty();
        }
    }

    /** The theScreener rating for a stock. Empty on any failure. */
    public Optional<ScreenerRating> screenerRating(OnvistaEntity entity) {
        if (entity == null) return Optional.empty();
        return parseScreenerRating(
                getJson("v1/stocks/" + entity.entityValue() + "/the_screener_rating"));
    }

    /** Package-private, network-free. */
    static Optional<ScreenerRating> parseScreenerRating(JsonNode root) {
        if (root == null) return Optional.empty();
        List<RatingItem> fundamental = parseRatingItems(root.path("fundamentalList"));
        List<RatingItem> risk = parseRatingItems(root.path("riskList"));
        if (fundamental.isEmpty() && risk.isEmpty()) return Optional.empty();
        return Optional.of(new ScreenerRating(
                text(root.path("fundamentalList"), "descriptionItemList"), fundamental,
                text(root.path("riskList"), "descriptionItemList"), risk));
    }

    private static List<RatingItem> parseRatingItems(JsonNode listNode) {
        List<RatingItem> out = new ArrayList<>();
        for (JsonNode i : listOf(listNode, "list")) {
            String type = text(i, "typeItem");
            if (type == null) continue;
            out.add(new RatingItem(type, text(i, "typeDescription"), num(i, "value"),
                    intOr(i, "numberStars", 0), text(i, "shortText"), text(i, "longText")));
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

    /**
     * The onvista message board for a stock.
     *
     * <p>Currently unused, stands ready.
     */
    public Optional<ForumBoard> forumThreads(OnvistaEntity entity) {
        if (entity == null) return Optional.empty();
        return parseForum(getJson("v1/forum/" + entity.entityType() + "/"
                + entity.entityValue() + "/forum_thread_lists"));
    }

    /** Package-private, network-free. */
    static Optional<ForumBoard> parseForum(JsonNode root) {
        if (root == null) return Optional.empty();
        List<ForumThread> out = new ArrayList<>();
        JsonNode items = root.path("items");
        if (!items.isArray()) items = root.path("list");
        if (items.isArray()) {
            for (JsonNode t : items) {
                String subject = text(t, "subject");
                if (subject == null) continue;
                out.add(new ForumThread(intOr(t, "position", -1), subject, text(t, "url"),
                        intOr(t, "answersTotal", 0), intOr(t, "hitsToday", 0),
                        intOr(t, "rating", 0), instant(t, "lastPostingDate")));
            }
        }
        if (out.isEmpty() && text(root, "urlForum") == null) return Optional.empty();
        return Optional.of(new ForumBoard(text(root, "urlForum"), List.copyOf(out)));
    }

    // ------------------------------------------------------- holders (funds)

    /** One fund/ETF holding the share, with the weight it gives it. */
    public record FundHolder(OnvistaEntity fund, String issuer, double investmentPct,
            double ongoingCharges, double performancePct1Y, double volumeFundEuro) {}

    /**
     * Which funds and ETFs hold this share, weight-ranked as onvista serves them
     * (100 rows for SAP, probed 2026-08-02) — the "who is forced to own this"
     * view that explains index-driven flow.
     *
     * <p>Currently unused, stands ready.
     */
    public List<FundHolder> fundHolders(OnvistaEntity entity) {
        if (entity == null) return List.of();
        return parseFundHolders(getJson("v1/funds/top_holding_to_funds?entityType="
                + entity.entityType() + "&entityValue=" + entity.entityValue()));
    }

    /** Package-private, network-free. */
    static List<FundHolder> parseFundHolders(JsonNode root) {
        List<FundHolder> out = new ArrayList<>();
        for (JsonNode f : listOf(root, "list")) {
            OnvistaEntity fund = entityOf(f.path("instrument"));
            if (fund == null) continue;
            out.add(new FundHolder(fund, text(f.path("fundsIssuer"), "name"),
                    num(f, "investmentPct"), num(f, "ongoingCharges"),
                    num(f, "performancePct1Y"), num(f, "volumeFundEuro")));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------- article full text

    /**
     * One article as onvista serves it: the metadata, the body assembled from
     * the paragraph content items, and every instrument onvista linked to it.
     */
    public record Article(String articleId, String headline, String publisher,
            String url, Instant published, String language, int wordCount,
            boolean premium, String subType, List<String> paragraphs,
            List<OnvistaEntity> instruments) {

        /** The body as one text block, paragraphs separated by blank lines. */
        public String body() {
            return String.join("\n\n", paragraphs);
        }
    }

    /**
     * The FULL TEXT of one article as JSON — no HTML scraping, no paywall
     * gymnastics for the open items. The id comes from the archive finder the
     * {@link OnvistaClient} news leg already walks ({@code entityValue} of an
     * article teaser). Non-text content items (charts, videos) are skipped;
     * {@code premium} flags the ones onvista serves truncated.
     */
    public Optional<Article> article(String articleId) {
        if (articleId == null || articleId.isBlank()) return Optional.empty();
        return parseArticle(getJson("v1/articles/" + articleId.trim()));
    }

    /** Package-private, network-free. */
    static Optional<Article> parseArticle(JsonNode root) {
        if (root == null) return Optional.empty();
        String headline = text(root, "headline");
        if (headline == null) return Optional.empty();
        JsonNode pub = root.path("publisher");
        String publisher = pub.isTextual() ? pub.asText() : text(pub, "name");

        List<String> paragraphs = new ArrayList<>();
        for (JsonNode c : root.path("contentItems")) {
            // Only the text-bearing item types carry a "value"; charts and
            // videos ride in the same array and are skipped.
            String value = text(c, "value");
            if (value != null) paragraphs.add(value);
        }
        List<OnvistaEntity> instruments = new ArrayList<>();
        for (JsonNode i : root.path("instruments")) {
            OnvistaEntity e = entityOf(i);
            if (e != null) instruments.add(e);
        }
        return Optional.of(new Article(text(root, "entityValue"), headline, publisher,
                text(root.path("urls"), "WEBSITE"), instant(root, "datetimePublication"),
                text(root, "language"), intOr(root, "wordCount", -1),
                root.path("premium").asBoolean(false), text(root, "articleSubTypeName"),
                List.copyOf(paragraphs), List.copyOf(instruments)));
    }
}
