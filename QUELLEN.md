# Quellen-Register

**Stand: 2026-08-12** · 6 Module · 192 Quellen-Einträge (davon 9 kuratierte Feed-Zeilen) · 208 verschiedene Hosts

> ⚠️ **PFLEGE-MANDAT:** Dieses Register ist die einzige vollständige Übersicht über alles, was
> das Terminal nach außen ruft. Es muss bei **jeder** Quellen-Änderung mitgezogen werden -
> neue Quelle, entfernte Quelle, umgezogener Host, geänderter Zweck. Eine Quelle, die hier
> fehlt, existiert für die Planung nicht.
>
> Prüfbefehl für die Vollständigkeit (findet jede Klasse mit einem ausgehenden Host - PLUS
> die kuratierte Feed-Liste, deren URLs in der CSV statt in Java stehen):
> ```bash
> grep -rEho 'https?://[A-Za-z0-9._-]+' --include='*.java' */src/main | sort -u
> grep -Eho 'https?://[A-Za-z0-9._-]+' web-impl/src/main/resources/web/sources.csv | sort -u
> ```

**Kategorien:** `Reddit` · `Sentiment` (Foren, Social, Community) · `News` · `Börse` (Kurse,
Handelsdaten) · `Filings` (Regulatorik, Meldepflichten) · `Kalender` · `Makro` (Statistik,
Zentralbanken) · `Welt` (Weltsignale/Kontext) · `Krypto` · `Auflösung` (Suche, Identität)
· `Infra` (kein Datenbezug)

**Module:** Die gesamte Quellwelt lebt in `web-impl` (Klassen unter
`web-impl/src/main/java/.../web/impl/sources/**`, kuratierte Feeds als Zeilen in
`web-impl/src/main/resources/web/sources.csv`, gefahren vom generischen
CuratedFeedSource). Daneben: `reddit` (Reddit-Zugang), `instrument-corpus`
(Ticker-/Instrumentenlisten), `agent` (Recherche-Werkzeuge), `terminal`
(Browser-Transport, Infra), `updater` (Releases).

---

## A

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| 4chan /biz/ | Sentiment | `boards.4chan.org`, `a.4cdn.org` | web-impl · sources.fourchan.FourChanBizSource |
| 4investors | News | `www.4investors.de` | web-impl · sources.briefing.MarketPressClient |
| ADS-B Flugverkehr | Welt | `api.adsb.lol` | web-impl · sources.briefing.FlightClient |
| Al Jazeera (arabisch) | News | `www.aljazeera.net` | web-impl · sources.briefing.MarketPressClient |
| ApeWisdom | Sentiment | `apewisdom.io` | web-impl · sources.briefing.ApeWisdomClient |
| ArcGIS PortWatch (Häfen) | Welt | `services9.arcgis.com` | web-impl · sources.briefing.PortWatchClient |
| Ariva Analysen | News | `www.ariva.de` | web-impl · sources.csv (Zeile ariva-analysten) |
| Ariva Forum | Sentiment | `www.ariva.de` | web-impl · sources.csv (Zeile ariva-forum) |
| arXiv | Welt | `export.arxiv.org` | agent · ArxivSearchClient |
| ASX (Australien) | Börse | `www.asx.com.au`, `asx.api.markitdigital.com`, `content.markitcdn.com` | web-impl · sources.asx.AsxMarketClient |
| Aurora-Oval (NOAA SWPC) | Welt | `services.swpc.noaa.gov` | web-impl · sources.briefing.AuroraOvalClient |
| Autobahn-Verkehr | Welt | `verkehr.autobahn.de` | web-impl · sources.briefing.AutobahnClient, TrafficClient |

## B

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| BaFin Directors' Dealings | Filings | `portal.mvp.bafin.de` | web-impl · sources.bafin.BafinInsiderDealingsSource |
| Bankier.pl (Polen) | News | `www.bankier.pl` | web-impl · sources.briefing.MarketPressClient |
| BEA (US-Wirtschaftsdaten) | Makro | `apps.bea.gov` | web-impl · sources.briefing.StatsReleaseCalendarClient |
| Benzinga | News | `www.benzinga.com` | web-impl · sources.csv (Zeile benzinga) |
| Binance Futures | Krypto | `fapi.binance.com` | web-impl · sources.briefing.CryptoDerivsClient |
| Bing Websuche | Auflösung | `www.bing.com` | web-impl · sources.websearch.BingWebSearchSource; agent · BingNewsSearchClient |
| Bloomberg Feeds | News | `feeds.bloomberg.com` | web-impl · sources.csv (Zeile bloomberg); web-impl · sources.briefing.MarketPressClient |
| Bluesky | Sentiment | `bsky.app`, `api.bsky.app` | web-impl · sources.bluesky.BlueskySource |
| boerse.de | News | `www.boerse.de` | web-impl · sources.boersede.BoerseDeNewsClient |
| boerse.de Kurse | Börse | `www.boerse.de` | web-impl · sources.boersede.BoerseDeMarketClient |
| Borsa Italiana Termine | Kalender | `www.borsaitaliana.it` | web-impl · sources.briefing.BorsaItalianaEventsClient |
| Börse Frankfurt / Xetra Historie | Börse | `api.boerse-frankfurt.de` | web-impl · sources.boersefrankfurt.XetraHistorySource, BoerseFrankfurtOrderBookSource |
| Börse Online | News | `www.boerse-online.de` | web-impl · sources.boersenmedien.BoersenmedienNewsClient |
| BrightSky (DWD-Wetter) | Welt | `api.brightsky.dev` | web-impl · sources.briefing.CuriositiesClient |
| Bundesanzeiger Leerverkäufe | Filings | `www.bundesanzeiger.de` | web-impl · sources.bundesanzeiger.BundesanzeigerShortInterestSource |
| Bundesbank-Statistiken | Makro | `api.statistiken.bundesbank.de` | web-impl · sources.briefing.BundYieldClient |

## C

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Cboe Put/Call-Ratio | Börse | `cdn.cboe.com` | web-impl · sources.briefing.CboePutCallClient |
| CBRates (Leitzinsen) | Makro | `www.cbrates.com` | web-impl · sources.briefing.CentralBankCalendarClient |
| Celestrak (Satellitenbahnen) | Welt | `celestrak.org` | web-impl · sources.briefing.SatelliteClient |
| Cinco Días (Spanien) | News | `feeds.elpais.com` | web-impl · sources.briefing.MarketPressClient |
| CISA KEV (Cyber) | Welt | `www.cisa.gov` | web-impl · sources.briefing.CisaKevClient |
| CNBC Kurse & Earnings | Börse | `quote.cnbc.com`, `gdsapi.cnbc.com`, `gds-earnings.cnbc.com` | web-impl · sources.cnbc.CnbcQuoteClient |
| CNBC News | News | `www.cnbc.com`, `api.queryly.com`, `search.cnbc.com` | web-impl · sources.csv (Zeile cnbc); web-impl · sources.cnbc.CnbcSearchSource; web-impl · sources.briefing.MarketPressClient |
| CNN Fear & Greed | Sentiment | `production.dataviz.cnn.io`, `edition.cnn.com` | web-impl · sources.feargreed.FearGreedClient |
| CoinGecko | Krypto | `api.coingecko.com` | web-impl · sources.briefing.CoinGeckoClient |
| comdirect Community | Sentiment | `community.comdirect.de` | web-impl · sources.csv (Zeile comdirect-community) |
| Consorsbank | Börse | `www.consorsbank.de` | web-impl · sources.consorsbank.ConsorsbankSource |
| Crypto Fear & Greed | Sentiment | `api.alternative.me` | web-impl · sources.feargreed.CryptoFearGreedClient |

## D

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Dagens Industri (Schweden) | News | `www.di.se` | web-impl · sources.briefing.MarketPressClient |
| DAWUM (Wahlumfragen) | Welt | `api.dawum.de` | web-impl · sources.briefing.DawumClient |
| der aktionär | News | `www.deraktionaer.de` | web-impl · sources.boersenmedien.BoersenmedienNewsClient |
| Der Standard (Österreich) | News | `www.derstandard.at` | web-impl · sources.briefing.MarketPressClient |
| Deribit | Krypto | `www.deribit.com` | web-impl · sources.briefing.CryptoDerivsClient |
| Destatis | Makro | `www.destatis.de` | web-impl · sources.briefing.MacroPressClient |
| Deutsche Börse Handelskalender | Kalender | `www.cashmarket.deutsche-boerse.com` | web-impl · sources.briefing.TradingCalendarClient |
| DIVI-Intensivregister | Welt | `www.intensivregister.de` | web-impl · sources.briefing.DiviClient |
| Dow Jones Feeds | News | `feeds.content.dowjones.io` | web-impl · sources.briefing.MarketPressClient |
| DWD Warnkarten | Welt | `maps.dwd.de` | web-impl · sources.briefing.GermanWeatherAlertClient |

## E

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| EarningsWhispers | Kalender | `www.earningswhispers.com` | web-impl · sources.briefing.EarningsWhispersClient |
| Eastmoney (China) | News | `rss.eastmoney.com` | web-impl · sources.briefing.MarketPressClient |
| ECB Daten & Feeds | Makro | `data-api.ecb.europa.eu`, `www.ecb.europa.eu` | web-impl · sources.briefing.EcbFeedsClient, CentralBankCalendarClient |
| Economic Times (Indien) | News | `economictimes.indiatimes.com` | web-impl · sources.briefing.MarketPressClient |
| EDGAR (SEC) | Filings | `data.sec.gov`, `www.sec.gov` | web-impl · sources.edgar.EdgarClient |
| EDGAR Volltextsuche | Filings | `efts.sec.gov`, `www.sec.gov` | web-impl · sources.edgar.EdgarFullTextSource |
| EIA Ölbericht (WPSR) | Makro | `ir.eia.gov` | web-impl · sources.briefing.EiaWpsrClient |
| Energy-Charts | Welt | `api.energy-charts.info` | web-impl · sources.briefing.EnergyChartsClient |
| ENSO / El Niño (NOAA) | Welt | `www.cpc.ncep.noaa.gov` | web-impl · sources.briefing.EnsoClient |
| EONET (NASA-Naturereignisse) | Welt | `eonet.gsfc.nasa.gov` | web-impl · sources.briefing.EonetClient |
| EQS Ad-hoc & Termine | Filings · Kalender | `www.eqs-news.com` | web-impl · sources.briefing.EqsEventsClient, EqsNewsArchiveClient |
| EU-Kommission Presscorner | Welt | `ec.europa.eu` | web-impl · sources.briefing.EuPresscornerClient, StatsReleaseCalendarClient |
| Eurex Handelskalender | Kalender | `www.eurex.com` | web-impl · sources.briefing.TradingCalendarClient |
| Eurex Open Interest | Börse | `www.eurex.com` | web-impl · sources.briefing.EurexOpenInterestClient |
| Euronext | Börse | `live.euronext.com` | web-impl · sources.euronext.EuronextClient |
| Eurostat | Makro | `ec.europa.eu` | web-impl · sources.briefing.EurostatClient |
| EUWAX Sentiment (via Onvista-Quote) | Sentiment | `api.onvista.de` | web-impl · sources.onvista.EuwaxSentimentClient |
| Expansión (Spanien) | News | `e00-expansion.uecdn.es` | web-impl · sources.briefing.MarketPressClient |

## F

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| FAA Luftraum-Status | Welt | `nasstatus.faa.gov` | web-impl · sources.briefing.GlobalHazardsClient |
| FCA Leerverkaufsregister (UK) | Filings | `www.fca.org.uk` | web-impl · sources.briefing.FcaShortPositionsClient |
| Federal Register | Welt | `www.federalregister.gov` | web-impl · sources.briefing.FederalRegisterClient |
| Federal Reserve | Makro | `www.federalreserve.gov` | web-impl · sources.briefing.FedFeedsClient, CentralBankCalendarClient |
| ferien-api.de | Kalender | `ferien-api.de` | web-impl · sources.briefing.HolidayCalendarClient |
| Financial Juice | News | `www.financialjuice.com` | web-impl · sources.financialjuice.FinancialJuiceSource |
| finanzen.net (Auflösung) | Auflösung | `www.finanzen.net`, `g.finanzen.net` | web-impl · sources.finanzennet.FinanzenNetResolver |
| finanzen.net (News) | News | `www.finanzen.net` | web-impl · sources.finanzennet.FinanzenNetNewsClient |
| finanzen.net Kurse | Börse | `www.finanzen.net` | web-impl · sources.finanzennet.FinanzenNetMarketClient |
| finanznachrichten.de | News | `www.finanznachrichten.de` | web-impl · sources.fnnews.FnInstrumentNewsClient; web-impl · sources.briefing.FnRssClient |
| FINRA Short Volume | Filings | `cdn.finra.org` | web-impl · sources.finra.FinraShortVolumeClient; web-impl · sources.briefing.FinraShortVolumeClient |
| Firmen-Websites (dynamisch) | News · Filings | <Firmen-Hostfamilie> | agent · CompanySiteCrawler, CompanyPressScout, CompanyLogoFetcher |
| FIRMS (NASA-Waldbrände) | Welt | `firms.modaps.eosdis.nasa.gov` | web-impl · sources.briefing.WildfireClient |
| Forex Factory Kalender | Kalender | `nfs.faireconomy.media` | web-impl · sources.briefing.EconCalendarClient; terminal · CefWebFetcher |
| Frankfurter (Wechselkurse) | Börse | `api.frankfurter.dev` | web-impl · sources.currency.EurUsdClient |
| FRED (St. Louis Fed) | Makro | `fred.stlouisfed.org` | web-impl · sources.briefing.FredSeriesClient |
| FT (Financial Times) | News | `www.ft.com` | web-impl · sources.briefing.MarketPressClient |
| FTMO Wirtschaftskalender | Kalender | `gw2.ftmo.com` | web-impl · sources.briefing.EconCalendarClient |
| FXStreet Kalender | Kalender | `calendar-api.fxstreet.com`, `www.fxstreet.com` | web-impl · sources.briefing.FxStreetCalendarClient |

## G

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| GDACS (Katastrophen) | Welt | `www.gdacs.org` | web-impl · sources.briefing.GdacsClient |
| GDELT Doc-Suche | Auflösung | `api.gdeltproject.org` | web-impl · sources.websearch.GdeltDocSource |
| GDELT Konflikt-Daten | Welt | `data.gdeltproject.org` | web-impl · sources.briefing.GdeltConflictClient |
| GDELT Weltindex (16 Sprachen) | News | `api.gdeltproject.org` | web-impl · sources.websearch.GdeltWorldSource |
| GitHub (Releases/Update) | Infra | `api.github.com`, `github.com` | terminal · GitHubReleases, LauncherUpdateService; updater · GitHubRepository |
| GitHub Raw (RKI-Daten) | Welt | `raw.githubusercontent.com` | web-impl · sources.briefing.RkiSurveillanceClient |
| Google News | News | `news.google.com` | web-impl · sources.googlenews.GoogleNewsSource; agent · GoogleNewsUrlResolver |
| Google News Weltausgaben (12 Editionen) | News | `news.google.com` | web-impl · sources.googlenews.GoogleNewsWorldSource |

## H

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Hacker News | Sentiment | `news.ycombinator.com`, `hn.algolia.com` | web-impl · sources.hackernews.HackerNewsSource |
| Handelsblatt | News | `www.handelsblatt.com`, `content.www.handelsblatt.com` | web-impl · sources.handelsblatt.HandelsblattNewsClient, HandelsblattBrand |
| Handelsblatt Kursdaten | Börse | `market.www.handelsblatt.com` | web-impl · sources.handelsblatt.HandelsblattMarketClient |
| Harper Petersen (Harpex) | Welt | `www.harperpetersen.com` | web-impl · sources.briefing.HarpexClient |
| Het Financieele Dagblad (NL) | News | `fd.nl` | web-impl · sources.briefing.MarketPressClient |
| HKEX (Hongkong) | Börse | `www.hkex.com.hk`, `www1.hkex.com.hk` | web-impl · sources.hkex.HkexClient |

## I

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| ifo-Institut | Makro | `www.ifo.de` | web-impl · sources.briefing.MacroPressClient |
| Il Sole 24 Ore (Italien) | News | `www.ilsole24ore.com` | web-impl · sources.briefing.MarketPressClient |
| InfoMoney (Brasilien) | News | `www.infomoney.com.br` | web-impl · sources.briefing.MarketPressClient |
| InsiderMonkey | Sentiment | `www.insidermonkey.com`, `www.sec.gov` | web-impl · sources.insidermonkey.InsiderMonkeySource |
| Interfax (Russland) | News | `www.interfax.ru` | web-impl · sources.briefing.MarketPressClient |
| Internet-Ausfälle (IODA) | Welt | `api.ioda.inetintel.cc.gatech.edu` | web-impl · sources.briefing.InternetOutageClient |
| Investegate (UK-RNS) | Filings · News | `www.investegate.co.uk` | web-impl · sources.briefing.InvestegateRnsClient |
| investing.com | News | `www.investing.com` | web-impl · sources.briefing.MarketPressClient |
| ISS-Position | Welt | `api.wheretheiss.at` | web-impl · sources.briefing.OrbitClient |

## K

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Kapitalmarktexperten | News | `www.kapitalmarktexperten.de` | web-impl · sources.kapitalmarktexperten.KapitalmarktexpertenClient |
| Kommersant (Russland) | News | `www.kommersant.ru` | web-impl · sources.briefing.MarketPressClient |

## L

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Lang & Schwarz | Börse | `www.ls-tc.de` | web-impl · sources.langschwarz.LangSchwarzSource |
| Launch-Kalender (Space Devs) | Welt | `ll.thespacedevs.com` | web-impl · sources.briefing.LaunchPadClient |
| Lemmy (Fediverse-Communities) | Sentiment | `feddit.org`, `lemmy.world` | web-impl · sources.lemmy.LemmySource |
| Les Echos (Frankreich) | News | `services.lesechos.fr` | web-impl · sources.briefing.MarketPressClient |

## M

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| MarketBeat | News | `www.marketbeat.com` | web-impl · sources.marketbeat.MarketBeatSource |
| MFN (Nordische Meldungen) | Filings | `mfn.se` | web-impl · sources.briefing.MfnDisclosureClient |
| Minkabu (Japan) | Börse | `mkdd.net` | web-impl · sources.minkabu.MinkabuClient |
| MVG München | Welt | `www.mvg.de` | web-impl · sources.briefing.TrafficClient |

## N

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| n-tv | News | `www.n-tv.de` | web-impl · sources.briefing.MarketPressClient |
| nager.at Feiertage | Kalender | `date.nager.at` | web-impl · sources.briefing.HolidayCalendarClient; terminal · HolidayProvider |
| Nasdaq (Unternehmen/Kurse) | Börse | `api.nasdaq.com`, `www.nasdaq.com` | web-impl · sources.nasdaq.NasdaqCompanySource; terminal · CefWebFetcher |
| Nasdaq Kalender | Kalender | `api.nasdaq.com`, `www.nasdaq.com` | web-impl · sources.briefing.NasdaqCalendarClient |
| Nasdaq News | News | `www.nasdaq.com` | web-impl · sources.nasdaq.NasdaqNewsRssSource |
| Nasdaq Nordic | Börse | `api.nasdaq.com` | web-impl · sources.nordic.NordicMarketClient |
| NHC (Hurrikane) | Welt | `www.nhc.noaa.gov` | web-impl · sources.briefing.GlobalHazardsClient |
| Nikkei (Japan) | News | `assets.wor.jp` | web-impl · sources.briefing.MarketPressClient |
| NYSE | Börse | `www.nyse.com` | web-impl · sources.nyse.NyseQuoteClient |
| NZZ (Schweiz) | News | `www.nzz.ch` | web-impl · sources.briefing.MarketPressClient |

## O

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| ONS (UK-Statistik) | Makro | `api.beta.ons.gov.uk` | web-impl · sources.briefing.StatsReleaseCalendarClient |
| Onvista | Börse · News | `api.onvista.de` | web-impl · sources.onvista.OnvistaApi, OnvistaFactsSource, OnvistaNewsSource |
| Onvista-Webseite (PageBundle/Entity-Resolver) | Börse · Auflösung | `www.onvista.de` | web-impl · sources.onvista.OnvistaPageBundle, OnvistaEntityResolver, OnvistaSections |
| Open-Meteo | Welt | `api.open-meteo.com`, `air-quality-api.open-meteo.com` | web-impl · sources.briefing.WorldWeatherClient, GlobalWeatherGridClient, AirQualityGridClient |
| OpenLigaDB | Welt | `api.openligadb.de` | web-impl · sources.briefing.SportsCalendarClient |

## P

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Pegelonline (Rhein/Wasserstände) | Welt | `www.pegelonline.wsv.de` | web-impl · sources.briefing.RhinePegelClient, WaterLevelClient |
| Polymarket | Sentiment | `gamma-api.polymarket.com` | web-impl · sources.briefing.PolymarketClient |
| Portfolio.hu (Ungarn) | News | `www.portfolio.hu` | web-impl · sources.briefing.MarketPressClient |
| PR Newswire UK | News | `www.prnewswire.co.uk` | web-impl · sources.csv (Zeile prnewswire-uk) |
| Presseportal | News | `www.presseportal.de` | web-impl · sources.briefing.PresseportalClient |

## R

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| RBC (Russland) | News | `rssexport.rbc.ru` | web-impl · sources.briefing.MarketPressClient |
| Reddit (via Browser-Joker) | Reddit | `www.reddit.com` | terminal · CefFetchClient, HeadlineJson |
| **Reddit - Kommentar-Strom (sub-weit)** | Reddit | `www.reddit.com`, `oauth.reddit.com` | reddit · RedditScraper#scanComments, RssRedditScraper#scanComments |
| **Reddit - Threads/Listing** | Reddit | `www.reddit.com`, `oauth.reddit.com` | reddit · RedditScraper, RssRedditScraper, OAuthRedditFetcher |
| Reuters | News | `www.reuters.com` | web-impl · sources.reuters.ReutersNewsSource; web-impl · sources.briefing.MarketPressClient |

## S

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Sanktionskarte (EU) | Welt | `www.sanctionsmap.eu` | web-impl · sources.briefing.SanctionsMapClient |
| Saudi Exchange (TASI) | Börse | `www.saudiexchange.sa` | web-impl · sources.briefing.WalledExchangeClient |
| SCMP (Hongkong) | News | `www.scmp.com` | web-impl · sources.briefing.MarketPressClient |
| SEC Form 4 (US-Insider) | Filings | `data.sec.gov`, `www.sec.gov` | web-impl · sources.edgar.EdgarInsiderClient |
| SEC Ticker-Liste | Auflösung | `www.sec.gov` | instrument-corpus · SecTickerSource |
| SEC XBRL Company Facts | Filings | `data.sec.gov` | web-impl · sources.edgar.EdgarFactsClient |
| SET Thailand | Börse | `www.set.or.th` | web-impl · sources.briefing.WalledExchangeClient |
| sharedeals.de | News | `www.sharedeals.de` | web-impl · sources.sharedeals.SharedealsClient |
| SIX (Schweiz) | Börse | `www.six-group.com` | web-impl · sources.six.SixMarketClient |
| Space Weather (NOAA SWPC) | Welt | `services.swpc.noaa.gov` | web-impl · sources.briefing.SpaceWeatherClient |
| Spiegel | News | `www.spiegel.de` | web-impl · sources.briefing.MarketPressClient |
| Spot.IM Kommentare | Sentiment | `api-2-0.spot.im` | web-impl · sources.yahooconversations.YahooConversationsSource; terminal · OpenWebConversationFetcher |
| Status-Seiten (Claude, OpenAI, Cloudflare, GitHub, Netlify, Vercel, Discord) | Welt | `status.claude.com`, `status.openai.com`, `www.cloudflarestatus.com`, `www.githubstatus.com`, `www.netlifystatus.com`, `www.vercel-status.com`, `discordstatus.com` | web-impl · sources.briefing.ServiceStatusClient |
| Stocknear | Sentiment | `stocknear.com`, `reddit.com` | web-impl · sources.stocknear.StocknearClient |
| StockTwits | Sentiment | `stocktwits.com`, `api.stocktwits.com` | web-impl · sources.stocktwits.StocktwitsSource |
| STOXX Europe 600 Supersektoren (via Onvista) | Börse | `api.onvista.de` | web-impl · sources.onvista.StoxxSectorClient |

## T

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Tagesschau | News | `www.tagesschau.de` | web-impl · sources.briefing.TagesschauClient |
| TASS (Russland) | News | `tass.ru` | web-impl · sources.briefing.MarketPressClient |
| Telegram-Kanäle | Sentiment | `t.me` | web-impl · sources.telegram.TelegramChannelSource |
| TMX (Kanada) | Börse | `app-money.tmx.com` | web-impl · sources.tmx.TmxMarketClient |
| Tradegate | Börse | `www.tradegate.de`, `www.tradegatebsx.com` | web-impl · sources.tradegate.TradegateQuoteSource; web-impl · sources.briefing.TradingCalendarClient |
| TradersUnion | News | `tradersunion.com` | web-impl · sources.tradersunion.TradersUnionNewsClient |
| TradersUnion Movers | Börse | `quotes.tradersunion.com`, `tradersunion.com` | web-impl · sources.tradersunion.TradersUnionMoversClient |
| TradingEconomics | Makro | `tradingeconomics.com` | web-impl · sources.briefing.TradingEconomicsClient |
| TradingView (Minds/Suche) | Sentiment · Börse | `www.tradingview.com`, `symbol-search.tradingview.com` | web-impl · sources.tradingview.TradingViewMindsSource, TradingViewSymbolSearch, TradingViewApi |
| TradingView Kalender | Kalender | `economic-calendar.tradingview.com`, `www.tradingview.com` | web-impl · sources.briefing.TradingViewCalendarClient |
| TradingView News | News | `news-headlines.tradingview.com`, `news-mediator.tradingview.com` | web-impl · sources.tradingview.TradingViewNewsSource |
| TradingView Scanner + Branchentafel | Börse | `scanner.tradingview.com` | web-impl · sources.tradingview.TradingViewScanner, SectorBoardClient |
| Treasury Fiscal Data | Makro | `api.fiscaldata.treasury.gov` | web-impl · sources.briefing.CuriositiesClient |

## U

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| USGS Erdbeben | Welt | `earthquake.usgs.gov` | web-impl · sources.briefing.GlobalHazardsClient |

## W

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| wallstreet-online (Board) | Sentiment | `www.wallstreet-online.de` | web-impl · sources.csv (Zeile wso-boards) |
| wallstreet-online (Kurse/ISIN) | Börse | `www.wallstreet-online.de` | web-impl · sources.wallstreetonline.WallstreetOnlineClient |
| wallstreet-online (News) | News | `www.wallstreet-online.de` | web-impl · sources.wallstreetonline.WsoNewsClient |
| wallstreet-online (Termine) | Kalender | `www.wallstreet-online.de` | web-impl · sources.briefing.WoCompanyCalendarClient |
| Weiße Haus (Aktionen) | Welt | `www.whitehouse.gov` | web-impl · sources.briefing.WhiteHouseActionsClient |
| Welt | News | `www.welt.de` | web-impl · sources.welt.WeltNewsClient |
| WHO Ausbrüche | Welt | `www.who.int` | web-impl · sources.briefing.WhoOutbreakClient |
| Wiener Börse | Börse | `www.wienerborse.at` | web-impl · sources.wienerboerse.WienerBoerseClient |
| Wikidata | Auflösung | `query.wikidata.org`, `www.wikidata.org`, `wikimedia.org`, `github.com` | web-impl · sources.briefing.WikidataClient |
| Wikipedia Current Events | Welt | `en.wikipedia.org`, `github.com` | web-impl · sources.briefing.WikipediaCurrentEventsClient |
| Wikipedia Suche | Welt | `de.wikipedia.org`, `en.wikipedia.org` | agent · WikipediaSearchClient |
| WirtschaftsWoche | News | `www.wiwo.de`, `content.www.wiwo.de` | web-impl · sources.handelsblatt.HandelsblattNewsClient, HandelsblattBrand; web-impl · sources.briefing.MarketPressClient |

## X

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Xetra Instrumentenliste | Auflösung | `www.xetra.com` | instrument-corpus · XetraSource |

## Y

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Motley Fool | News | `www.fool.com`, `api.fool.com`, `www.google.com` | web-impl · sources.csv (Zeile fool); web-impl · sources.fool.FoolNewsSource, FoolQuoteNewsSource |
| Yahoo Finance (Kommentare) | Sentiment | `finance.yahoo.com` | web-impl · sources.yahooconversations.YahooConversationsSource |
| Yahoo Finance (Kurse/Bars/Suche) | Börse | `query1.finance.yahoo.com`, `query2.finance.yahoo.com` | web-impl · sources.yahoofinance.YahooFinanceSource, YahooMarketClient; web-impl · sources.currency.EurUsdClient |
| Yonhap (Korea) | News | `www.yna.co.kr` | web-impl · sources.briefing.MarketPressClient |

---

## Infrastruktur (kein Datenbezug)

| Zweck | Host(s) | Modul · Klasse |
|---|---|---|
| Lokaler CEF-/Asset-Server, Command-Bridge | `127.0.0.1` | terminal · CefHost, AssetServer, CommandBridge, AppMain |
| Update-Prüfung & Release-Download | `api.github.com`, `github.com` | terminal · GitHubReleases, LauncherUpdateService; updater · GitHubRepository |

---

## Tote / entfernte Quellen (nicht wieder anbinden)

| Quelle | Grund |
|---|---|
| finanznachrichten (eigenes Modul) | ersetzt durch `sources.fnnews` + `sources.briefing.FnRssClient` |
| Google Finance | Anti-Bot-Wall |
| Stooq | Proof-of-Work-Wall |
| Xinhua + People's Daily (China) | Feeds tot: einer datiert nichts, der andere steht auf 2025 (gemessen 2026-08-11); ersetzt durch Eastmoney |

---

## Notizen zur Reichweite

- **Firmen-Websites haben keinen festen Host:** Die Firmensite-Kette (`CompanySiteCrawler`, `CompanyPressScout`, `CompanyLogoFetcher`) ruft die Domain an, die zur jeweiligen Firma aufgelöst wurde - sie taucht deshalb im Prüfbefehl NIE auf, weil im Quelltext keine URL steht. Sie zählt trotzdem als Quelle und steht darum als eigene Zeile im Register.
- **Kuratierte Feeds stehen in der CSV, nicht in Java:** `web-impl/src/main/resources/web/sources.csv` - eine Zeile je Quelle, gefahren vom generischen CuratedFeedSource. Der Java-Prüfbefehl allein ist darum NICHT mehr vollständig; die zweite Grep-Zeile über die CSV gehört immer dazu.
- **Handelsblatt/WiWo-Feeds laufen über Umleitung:** Die `www.*`-Feed-URLs antworten 301 auf die `feeds.cms.*`-Hosts; der Transport folgt der Umleitung. Im Code (und darum hier) stehen nur die `www.*`-Hosts.
- **Tradegate ist umgezogen:** Kalenderdaten laufen über `www.tradegatebsx.com`, Kurse weiter über `www.tradegate.de`.
- **Yahoo:** frei sind `v8/chart`, `v1/search`, `v7/spark`; `v7/quote` und `v10` sind crumb-locked. EUR/USD über `v8/chart` (der dedizierte Endpunkt liefert hart 401).
- **Reddit-Budget:** anonym ~100 Requests / 10 min pro IP. Der sub-weite Kommentar-Strom kostet **einen** Request pro Subreddit und Zyklus - gemessen am 2026-08-10 decken 100 Einträge 21 min (r/wallstreetbets) bzw. 41 min (r/wallstreetbetsGER) ab.
- **Browser-Joker:** Quellen mit JS-Wall laufen über den eingebetteten CEF (`CefWebFetcher`), nicht über den direkten HTTP-Pfad.
- **Google News ist pro Ausgabe eine eigene Quelle:** Die Heimatausgabe (`hl=de`) bedient die Wire, die zwölf Weltausgaben sind dossier-only und stempeln je Artikel Sprache und Sphäre.
- **GDELT hat ein hartes Tor:** ein Request alle 8 s, JVM-weit für BEIDE GDELT-Clients zusammen (`GdeltGate`). Ein Burst kostet minutenlange IP-Sperren. Die Query-Länge ist ebenfalls begrenzt - 16 `sourcelang`-Klauseln antworten „query too long", darum fragt der Weltindex in Gruppen zu vier Sprachen.
- **FRED war nie eine Wall, sondern ein Header-Problem:** Der Host verlangt den VOLLEN Browser-Headersatz UND `Accept-Encoding` - gemessen antwortet er auf beides zusammen mit 200, auf jede Hälfte allein mit einem HTTP/2-Stream-Reset. Seit `DirectWebFetcher` das mitschickt (und gzip auspackt), liefern alle 14 Reihen. Dieselbe Änderung hat Les Echos, Il Sole, Cinco Días, FD.nl, IDX und Investegate zurückgebracht.
- **RSS-Fallen, an der ganzen Presseschau gemessen (2026-08-11):** Ein UTF-8-BOM vor der XML-Deklaration kostet StAX das GANZE Dokument; ein striktes `Accept` ohne `*/*` beantworten manche Häuser mit 406; RSS 1.0 datiert über `dc:date` statt `pubDate`; die Zone „Z" ist kein gültiger RFC-1123-Zonenname; und ein Feed mit Artikel-Volltext im Item sprengt `getElementText`. Alle fünf sind in `Rss`/`MarketPressClient` behoben - jede kostete vorher einen Feed komplett und lautlos.
- **Eurex-Produkt-IDs:** `overallstatistics/<id>` beschreibt sich selbst (`meta.productCode`, `meta.isin` = Basiswert). Die IDs liegen dünn über einen weiten Raum verstreut; katalogisiert sind die Index- und Zinsbücher. Einzelaktien-Optionen brauchen einen einmal gescannten Index - der Mechanismus steht, der Index nicht.
- **UK-RNS IST angebunden:** über Investegates server-gerendertes Ankündigungs-Register (138 Meldungen über 3 Seiten, minutenfrisch). Die Tür ging erst auf, als der Direktweg den vollen Browser-Headersatz zu schicken begann - vorher 401/404. Die Komponente der Londoner Börse selbst bleibt zu (POST antwortet 200 mit `[]` für jede Parameterform); lse.co.uk, sharecast und advfn stehen hinter Cloudflare.
- **CNMV (ES) und AMF (FR) Leerverkäufe:** nur als HTML-Seite zu haben, kein CSV/JSON gefunden - Scraping-Kandidaten, nicht gebaut. CNMV liefert zusätzlich eine unvollständige Zertifikatskette (PKIX-Fehler auf dem Direktweg).
- 🃏 **Die Browser-Wand ist KEINE Automations-Erkennung** (gemessen 2026-08-11 mit echtem Chromium): Ein kaltes Profil bekommt die Erstbesuchs-Challenge, eine Sitzung, die sie einmal bestanden hat, wird normal bedient - mit und ohne jedes Anti-Automations-Flag. Genau diese Form hat der Joker: EINE verankerte Sitzung je Origin. Heißt: Jede Wand-Quelle ist erreichbar, sobald sie verdrahtet ist.
- 🃏 **Wand-Quellen aufnehmen, ohne zu raten:** Die Seite einmal mit echtem Chromium abziehen (`--headless=new --disable-gpu --disable-blink-features=AutomationControlled --window-size=1440,900 --user-agent=<echte UA> --virtual-time-budget=15000 --dump-dom`), Parser gegen das ECHTE Dokument schreiben, Client über `@DirectFirst` verdrahten, Smoke als joker-only markieren. So entstanden SET und TASI.
- **Korea (KRX) ist NICHT gebaut:** Die Seiten tragen Navigation und keinen Indexstand; die Zahlen liegen hinter einer POST-API, deren Request-Form sich von außen nicht feststellen lässt. Ein Client auf eine geratene Form wäre ein stiller Ausfall im Namen einer Quelle.
- **SEC-Archivpfade:** Die Volltextsuche liefert je Treffer die CIK des Einreichers - der Archiv-Pfad muss DIESE nutzen, nicht den führenden Block der Accession-Nummer (das ist die Kennung des Einreicher-Dienstleisters, der Pfad daraus antwortet 404).
