package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every RSS feed published on
 * <a href="https://www.finanznachrichten.de/service/rss.htm">finanznachrichten.de/service/rss.htm</a>
 * (134 of them as of 2026-06-05), modelled as an enum so callers can hand a
 * selection to {@link FnMonitorService} as varargs:
 *
 * <pre>{@code
 * monitor.start(FnFeed.AKTIEN_NACHRICHTEN, FnFeed.DAX_40_NACHRICHTEN_1);
 * // or a whole bucket:
 * monitor.start(FnFeed.of(FnCategory.BRANCHE));
 * // or everything:
 * monitor.start();   // no args == all feeds
 * }</pre>
 *
 * <p>Each constant carries its URL {@link #slug() slug} (the last path segment,
 * e.g. {@code rss-dax-40-nachrichten-1}) and a {@link #category()}. The full
 * feed {@link #url()} and a human-readable {@link #label()} are derived from the
 * slug.
 *
 * <h3>Terms of use (researched 2026-06-05)</h3>
 * finanznachrichten.de's RSS page states (paraphrased from the German original):
 * <blockquote>
 * "FinanzNachrichten.de gestattet Ihnen (widerruflich) die kostenfreie Übernahme
 * seiner News-Überschriften mit aktiven Links auf die News-Artikel
 * (<b>Aktualisierungsfrequenz: 10 Minuten</b>). Diese dürfen jedoch nicht in
 * Frames dargestellt werden, sondern müssen ein neues Browser-Fenster öffnen.
 * FinanzNachrichten.de behält sich das Recht vor, anderen Websites ohne Angabe
 * von Gründen die Übernahme von Inhalten zu untersagen."
 * </blockquote>
 * In short: free, revocable reuse of <em>headlines with an active link back to
 * the article</em> is permitted; the content must not be shown inside a frame;
 * and the feeds refresh roughly <b>every 10 minutes</b>. The 10-minute cadence
 * is why {@link FinanznachrichtenConfig} defaults to (and floors) the poll
 * interval well above a minute — polling faster fetches nothing new and is
 * needlessly impolite to a free service that may revoke access "ohne Angabe von
 * Gründen".
 */
public enum FnFeed {

    // ---- generated from finanznachrichten.de/service/rss.htm (134 feeds) ----
    AEX_NACHRICHTEN_43("rss-aex-nachrichten-43", FnCategory.INDEX),
    AFRIKA_NACHRICHTEN_69("rss-afrika-nachrichten-69", FnCategory.INDEX),
    AKTIEN_ADHOC("rss-aktien-adhoc", FnCategory.NEWS),
    AKTIEN_ANALYSEN("rss-aktien-analysen", FnCategory.ANALYSE),
    AKTIEN_EMPFEHLUNGEN_HALTEN("rss-aktien-empfehlungen-halten", FnCategory.EMPFEHLUNG),
    AKTIEN_EMPFEHLUNGEN_KAUFEN("rss-aktien-empfehlungen-kaufen", FnCategory.EMPFEHLUNG),
    AKTIEN_EMPFEHLUNGEN_VERKAUFEN("rss-aktien-empfehlungen-verkaufen", FnCategory.EMPFEHLUNG),
    AKTIEN_NACHRICHTEN("rss-aktien-nachrichten", FnCategory.NEWS),
    AMX_NACHRICHTEN_95("rss-amx-nachrichten-95", FnCategory.INDEX),
    ANLEIHEN_NACHRICHTEN_101("rss-anleihen-nachrichten-101", FnCategory.INDEX),
    ASIEN_NACHRICHTEN_36("rss-asien-nachrichten-36", FnCategory.INDEX),
    ATX_NACHRICHTEN_37("rss-atx-nachrichten-37", FnCategory.INDEX),
    AUSTRALIEN_NACHRICHTEN_67("rss-australien-nachrichten-67", FnCategory.INDEX),
    BEL_20_NACHRICHTEN_45("rss-bel-20-nachrichten-45", FnCategory.INDEX),
    BEL_MID_NACHRICHTEN_97("rss-bel-mid-nachrichten-97", FnCategory.INDEX),
    BRANCHE_BAU_INFRASTRUKTUR_2("rss-branche-bau-infrastruktur-2", FnCategory.BRANCHE),
    BRANCHE_BEKLEIDUNG_TEXTIL_3("rss-branche-bekleidung-textil-3", FnCategory.BRANCHE),
    BRANCHE_BIOTECHNOLOGIE_4("rss-branche-biotechnologie-4", FnCategory.BRANCHE),
    BRANCHE_CHEMIE_5("rss-branche-chemie-5", FnCategory.BRANCHE),
    BRANCHE_DIENSTLEISTUNGEN_6("rss-branche-dienstleistungen-6", FnCategory.BRANCHE),
    BRANCHE_EISEN_STAHL_8("rss-branche-eisen-stahl-8", FnCategory.BRANCHE),
    BRANCHE_ELEKTROTECHNOLOGIE_9("rss-branche-elektrotechnologie-9", FnCategory.BRANCHE),
    BRANCHE_ERNEUERBARE_ENERGIEN_11("rss-branche-erneuerbare-energien-11", FnCategory.BRANCHE),
    BRANCHE_FAHRZEUGE_12("rss-branche-fahrzeuge-12", FnCategory.BRANCHE),
    BRANCHE_FINANZDIENSTLEISTUNGEN_13("rss-branche-finanzdienstleistungen-13", FnCategory.BRANCHE),
    BRANCHE_FREIZEITPRODUKTE_14("rss-branche-freizeitprodukte-14", FnCategory.BRANCHE),
    BRANCHE_GESUNDHEITSWESEN_33("rss-branche-gesundheitswesen-33", FnCategory.BRANCHE),
    BRANCHE_GETRAENKE_TABAK_15("rss-branche-getraenke-tabak-15", FnCategory.BRANCHE),
    BRANCHE_HALBLEITER_16("rss-branche-halbleiter-16", FnCategory.BRANCHE),
    BRANCHE_HANDEL_E_COMMERCE_18("rss-branche-handel-e-commerce-18", FnCategory.BRANCHE),
    BRANCHE_HARDWARE_19("rss-branche-hardware-19", FnCategory.BRANCHE),
    BRANCHE_HOLZ_PAPIER_38("rss-branche-holz-papier-38", FnCategory.BRANCHE),
    BRANCHE_HOTELS_TOURISMUS_46("rss-branche-hotels-tourismus-46", FnCategory.BRANCHE),
    BRANCHE_IMMOBILIEN_50("rss-branche-immobilien-50", FnCategory.BRANCHE),
    BRANCHE_INDUSTRIE_MISCHKONZERNE_34("rss-branche-industrie-mischkonzerne-34", FnCategory.BRANCHE),
    BRANCHE_INTERNET_20("rss-branche-internet-20", FnCategory.BRANCHE),
    BRANCHE_IT_DIENSTLEISTUNGEN_22("rss-branche-it-dienstleistungen-22", FnCategory.BRANCHE),
    BRANCHE_KONSUMGUETER_24("rss-branche-konsumgueter-24", FnCategory.BRANCHE),
    BRANCHE_KOSMETIK_25("rss-branche-kosmetik-25", FnCategory.BRANCHE),
    BRANCHE_KUNSTSTOFFE_VERPACKUNGEN_27("rss-branche-kunststoffe-verpackungen-27", FnCategory.BRANCHE),
    BRANCHE_LOGISTIK_TRANSPORT_29("rss-branche-logistik-transport-29", FnCategory.BRANCHE),
    BRANCHE_LUFTFAHRT_RUESTUNG_30("rss-branche-luftfahrt-ruestung-30", FnCategory.BRANCHE),
    BRANCHE_MASCHINENBAU_31("rss-branche-maschinenbau-31", FnCategory.BRANCHE),
    BRANCHE_MEDIEN_32("rss-branche-medien-32", FnCategory.BRANCHE),
    BRANCHE_NAHRUNGSMITTEL_AGRAR_35("rss-branche-nahrungsmittel-agrar-35", FnCategory.BRANCHE),
    BRANCHE_NANOTECHNOLOGIE_51("rss-branche-nanotechnologie-51", FnCategory.BRANCHE),
    BRANCHE_NETZWERKTECHNIK_36("rss-branche-netzwerktechnik-36", FnCategory.BRANCHE),
    BRANCHE_OEL_GAS_37("rss-branche-oel-gas-37", FnCategory.BRANCHE),
    BRANCHE_PHARMA_40("rss-branche-pharma-40", FnCategory.BRANCHE),
    BRANCHE_ROHSTOFFE_41("rss-branche-rohstoffe-41", FnCategory.BRANCHE),
    BRANCHE_SOFTWARE_42("rss-branche-software-42", FnCategory.BRANCHE),
    BRANCHE_SONSTIGE_TECHNOLOGIE_43("rss-branche-sonstige-technologie-43", FnCategory.BRANCHE),
    BRANCHE_TELEKOM_45("rss-branche-telekom-45", FnCategory.BRANCHE),
    BRANCHE_UNTERHALTUNG_48("rss-branche-unterhaltung-48", FnCategory.BRANCHE),
    BRANCHE_VERSORGER_49("rss-branche-versorger-49", FnCategory.BRANCHE),
    BSE_SENSEX_NACHRICHTEN_83("rss-bse-sensex-nachrichten-83", FnCategory.INDEX),
    CAC_40_NACHRICHTEN_6("rss-cac-40-nachrichten-6", FnCategory.INDEX),
    CAC_MID_60_NACHRICHTEN_57("rss-cac-mid-60-nachrichten-57", FnCategory.INDEX),
    CAC_NEXT_20_NACHRICHTEN_91("rss-cac-next-20-nachrichten-91", FnCategory.INDEX),
    CECE_COMPOSITE_INDEX_NACHRICHTEN_103("rss-cece-composite-index-nachrichten-103", FnCategory.INDEX),
    CHARTANALYSEN("rss-chartanalysen", FnCategory.ANALYSE),
    CHARTANALYSEN_TOP("rss-chartanalysen-top", FnCategory.ANALYSE),
    CSE_25_NACHRICHTEN_102("rss-cse-25-nachrichten-102", FnCategory.INDEX),
    DAX_40_NACHRICHTEN_1("rss-dax-40-nachrichten-1", FnCategory.INDEX),
    DJ_INDUSTRIAL_NACHRICHTEN_4("rss-dj-industrial-nachrichten-4", FnCategory.INDEX),
    DJ_TRANSPORTATION_NACHRICHTEN_65("rss-dj-transportation-nachrichten-65", FnCategory.INDEX),
    DJ_UTILITIES_NACHRICHTEN_66("rss-dj-utilities-nachrichten-66", FnCategory.INDEX),
    EMPFEHLUNGEN_MEISTGELESEN("rss-empfehlungen-meistgelesen", FnCategory.EMPFEHLUNG),
    EURO_STOXX_50_NACHRICHTEN_38("rss-euro-stoxx-50-nachrichten-38", FnCategory.INDEX),
    EURONEXT_100_NACHRICHTEN_34("rss-euronext-100-nachrichten-34", FnCategory.INDEX),
    FTSE_100_NACHRICHTEN_41("rss-ftse-100-nachrichten-41", FnCategory.INDEX),
    FTSE_250_NACHRICHTEN_51("rss-ftse-250-nachrichten-51", FnCategory.INDEX),
    FTSE_ASEAN_40_NACHRICHTEN_77("rss-ftse-asean-40-nachrichten-77", FnCategory.INDEX),
    FTSE_ATHEX_LARGE_CAP_NACHRICHTEN_76("rss-ftse-athex-large-cap-nachrichten-76", FnCategory.INDEX),
    FTSE_CHINA_50_NACHRICHTEN_74("rss-ftse-china-50-nachrichten-74", FnCategory.INDEX),
    FTSE_ITALIA_MID_CAP_NACHRICHTEN_98("rss-ftse-italia-mid-cap-nachrichten-98", FnCategory.INDEX),
    FTSE_MIB_NACHRICHTEN_5("rss-ftse-mib-nachrichten-5", FnCategory.INDEX),
    FTSE_TECHMARK_FOCUS_NACHRICHTEN_59("rss-ftse-techmark-focus-nachrichten-59", FnCategory.INDEX),
    GENERAL_STANDARD_NACHRICHTEN_62("rss-general-standard-nachrichten-62", FnCategory.INDEX),
    GEX_NACHRICHTEN_72("rss-gex-nachrichten-72", FnCategory.INDEX),
    HANG_SENG_NACHRICHTEN_49("rss-hang-seng-nachrichten-49", FnCategory.INDEX),
    IBEX_35_NACHRICHTEN_44("rss-ibex-35-nachrichten-44", FnCategory.INDEX),
    IBEX_MEDIUM_CAP_NACHRICHTEN_99("rss-ibex-medium-cap-nachrichten-99", FnCategory.INDEX),
    ISEQ_20_NACHRICHTEN_82("rss-iseq-20-nachrichten-82", FnCategory.INDEX),
    JAPAN_NACHRICHTEN_20("rss-japan-nachrichten-20", FnCategory.INDEX),
    KAZAKH_TRADED_INDEX_NACHRICHTEN_81("rss-kazakh-traded-index-nachrichten-81", FnCategory.INDEX),
    LATEINAMERIKA_NACHRICHTEN_75("rss-lateinamerika-nachrichten-75", FnCategory.INDEX),
    MARKTBERICHTE("rss-marktberichte", FnCategory.NEWS),
    MDAX_50_NACHRICHTEN_8("rss-mdax-50-nachrichten-8", FnCategory.INDEX),
    NACHRICHTEN_AKTIEN_ASIEN("rss-nachrichten-aktien-asien", FnCategory.NEWS),
    NACHRICHTEN_AKTIEN_DEUTSCHLAND("rss-nachrichten-aktien-deutschland", FnCategory.NEWS),
    NACHRICHTEN_AKTIEN_EUROPA("rss-nachrichten-aktien-europa", FnCategory.NEWS),
    NACHRICHTEN_AKTIEN_USA("rss-nachrichten-aktien-usa", FnCategory.NEWS),
    NACHRICHTEN_ANLEIHEN("rss-nachrichten-anleihen", FnCategory.NEWS),
    NACHRICHTEN_BESTBEWERTET("rss-nachrichten-bestbewertet", FnCategory.NEWS),
    NACHRICHTEN_DEVISEN("rss-nachrichten-devisen", FnCategory.NEWS),
    NACHRICHTEN_FONDS("rss-nachrichten-fonds", FnCategory.NEWS),
    NACHRICHTEN_IPO("rss-nachrichten-ipo", FnCategory.NEWS),
    NACHRICHTEN_MEISTGELESEN("rss-nachrichten-meistgelesen", FnCategory.NEWS),
    NACHRICHTEN_ROHSTOFFE("rss-nachrichten-rohstoffe", FnCategory.NEWS),
    NACHRICHTEN_WIRTSCHAFT_KONJUNKTUR("rss-nachrichten-wirtschaft-konjunktur", FnCategory.NEWS),
    NACHRICHTEN_ZERTIFIKATE("rss-nachrichten-zertifikate", FnCategory.NEWS),
    NASDAQ_100_NACHRICHTEN_9("rss-nasdaq-100-nachrichten-9", FnCategory.INDEX),
    NASDAQ_BIOTECH_NACHRICHTEN_60("rss-nasdaq-biotech-nachrichten-60", FnCategory.INDEX),
    NEWS("rss-news", FnCategory.NEWS),
    NIKKEI_225_NACHRICHTEN_48("rss-nikkei-225-nachrichten-48", FnCategory.INDEX),
    OBX_NACHRICHTEN_58("rss-obx-nachrichten-58", FnCategory.INDEX),
    OMX_BALTIC_10_NACHRICHTEN_90("rss-omx-baltic-10-nachrichten-90", FnCategory.INDEX),
    OMX_COPENHAGEN_25_NACHRICHTEN_52("rss-omx-copenhagen-25-nachrichten-52", FnCategory.INDEX),
    OMX_HELSINKI_25_NACHRICHTEN_54("rss-omx-helsinki-25-nachrichten-54", FnCategory.INDEX),
    OMX_ICELAND_15_NACHRICHTEN_89("rss-omx-iceland-15-nachrichten-89", FnCategory.INDEX),
    OMX_STOCKHOLM_30_NACHRICHTEN_55("rss-omx-stockholm-30-nachrichten-55", FnCategory.INDEX),
    OSTEUROPA_NACHRICHTEN_71("rss-osteuropa-nachrichten-71", FnCategory.INDEX),
    PRIME_STANDARD_NACHRICHTEN_61("rss-prime-standard-nachrichten-61", FnCategory.INDEX),
    PSI_NACHRICHTEN_78("rss-psi-nachrichten-78", FnCategory.INDEX),
    RENIXX_NACHRICHTEN_80("rss-renixx-nachrichten-80", FnCategory.INDEX),
    RUSSLAND_NACHRICHTEN_47("rss-russland-nachrichten-47", FnCategory.INDEX),
    S_P_100_NACHRICHTEN_15("rss-s-p-100-nachrichten-15", FnCategory.INDEX),
    S_P_500_NACHRICHTEN_46("rss-s-p-500-nachrichten-46", FnCategory.INDEX),
    S_P_ASX_50_NACHRICHTEN_11("rss-s-p-asx-50-nachrichten-11", FnCategory.INDEX),
    S_P_BMV_IPC_NACHRICHTEN_86("rss-s-p-bmv-ipc-nachrichten-86", FnCategory.INDEX),
    S_P_MIDCAP_400_NACHRICHTEN_94("rss-s-p-midcap-400-nachrichten-94", FnCategory.INDEX),
    S_P_SMALLCAP_600_NACHRICHTEN_100("rss-s-p-smallcap-600-nachrichten-100", FnCategory.INDEX),
    S_P_TSX_60_NACHRICHTEN_53("rss-s-p-tsx-60-nachrichten-53", FnCategory.INDEX),
    SCALE_NACHRICHTEN_73("rss-scale-nachrichten-73", FnCategory.INDEX),
    SDAX_NACHRICHTEN_7("rss-sdax-nachrichten-7", FnCategory.INDEX),
    SINGAPUR_NACHRICHTEN_79("rss-singapur-nachrichten-79", FnCategory.INDEX),
    SMI_MID_NACHRICHTEN_96("rss-smi-mid-nachrichten-96", FnCategory.INDEX),
    SMI_NACHRICHTEN_42("rss-smi-nachrichten-42", FnCategory.INDEX),
    SONSTIGE_NACHRICHTEN_50("rss-sonstige-nachrichten-50", FnCategory.INDEX),
    STOXX_EUROPE_50_NACHRICHTEN_63("rss-stoxx-europe-50-nachrichten-63", FnCategory.INDEX),
    STOXX_EUROPE_600_NACHRICHTEN_104("rss-stoxx-europe-600-nachrichten-104", FnCategory.INDEX),
    TECDAX_NACHRICHTEN_2("rss-tecdax-nachrichten-2", FnCategory.INDEX),
    TOP_EMPFEHLUNGEN("rss-top-empfehlungen", FnCategory.EMPFEHLUNG);
    // ---- end generated ----

    /** Host all feeds live under; combined with the slug to form {@link #url()}. */
    public static final String BASE_URL = "https://www.finanznachrichten.de";

    private final String slug;
    private final FnCategory category;

    FnFeed(String slug, FnCategory category) {
        this.slug = slug;
        this.category = category;
    }

    /** The URL path segment, e.g. {@code rss-dax-40-nachrichten-1}. */
    public String slug() {
        return slug;
    }

    /** Which {@link FnCategory} bucket this feed belongs to. */
    public FnCategory category() {
        return category;
    }

    /** The fully-qualified feed URL, e.g. {@code https://www.finanznachrichten.de/rss-dax-40-nachrichten-1/}. */
    public String url() {
        return BASE_URL + "/" + slug + "/";
    }

    /**
     * A human-readable name derived from the slug: the {@code rss-} prefix and
     * the trailing numeric feed id are dropped, dashes become spaces and each
     * word is capitalised — e.g. {@code rss-dax-40-nachrichten-1} &rarr;
     * "Dax 40 Nachrichten".
     */
    public String label() {
        String body = slug.substring("rss-".length()).replaceAll("-\\d+$", "");
        String[] tokens = body.split("-");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(token.charAt(0)))
              .append(token.substring(1).toLowerCase(Locale.GERMAN));
        }
        return sb.toString();
    }

    /** All feeds belonging to {@code category}, in declaration order. */
    public static List<FnFeed> of(FnCategory category) {
        return Arrays.stream(values())
                .filter(f -> f.category == category)
                .toList();
    }

    /** Finds the feed for a raw slug (e.g. {@code rss-news}), if any. */
    public static Optional<FnFeed> bySlug(String slug) {
        return Arrays.stream(values())
                .filter(f -> f.slug.equals(slug))
                .findFirst();
    }
}
