# Quellen-Register

**Stand: 2026-08-11** · 60 Module · 179 ausgehende Klassen · 213 verschiedene Hosts

> ⚠️ **PFLEGE-MANDAT:** Dieses Register ist die einzige vollständige Übersicht über alles, was
> das Terminal nach außen ruft. Es muss bei **jeder** Quellen-Änderung mitgezogen werden -
> neue Quelle, entfernte Quelle, umgezogener Host, geänderter Zweck. Eine Quelle, die hier
> fehlt, existiert für die Planung nicht.
>
> Prüfbefehl für die Vollständigkeit (findet jede Klasse mit einem ausgehenden Host):
> ```bash
> grep -rEho 'https?://[A-Za-z0-9._-]+' --include='*.java' */src/main | sort -u
> ```

**Kategorien:** `Reddit` · `Sentiment` (Foren, Social, Community) · `News` · `Börse` (Kurse,
Handelsdaten) · `Filings` (Regulatorik, Meldepflichten) · `Kalender` · `Makro` (Statistik,
Zentralbanken) · `Welt` (Weltsignale/Kontext) · `Krypto` · `Auflösung` (Suche, Identität)
· `Infra` (kein Datenbezug)

---

## A

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| 4chan /biz/ | Sentiment | `boards.4chan.org`, `a.4cdn.org` | fourchan · FourChanBizClient |
| 4investors | News | `www.4investors.de` | briefing · MarketPressClient |
| ADS-B Flugverkehr | Welt | `api.adsb.lol` | briefing · FlightClient |
| Al Jazeera (arabisch) | News | `www.aljazeera.net` | briefing · MarketPressClient |
| ApeWisdom | Sentiment | `apewisdom.io` | briefing · ApeWisdomClient |
| ArcGIS PortWatch (Häfen) | Welt | `services9.arcgis.com` | briefing · PortWatchClient |
| Ariva Analysen | News | `www.ariva.de` | ariva · ArivaAnalystRssClient |
| Ariva Forum | Sentiment | `www.ariva.de` | ariva · ArivaForumRssClient |
| arXiv | Welt | `export.arxiv.org` | agent · ArxivSearchClient |
| ASX (Australien) | Börse | `www.asx.com.au`, `asx.api.markitdigital.com`, `content.markitcdn.com` | asx · AsxMarketClient |
| Aurora-Oval (NOAA SWPC) | Welt | `services.swpc.noaa.gov` | briefing · AuroraOvalClient |
| Autobahn-Verkehr | Welt | `verkehr.autobahn.de` | briefing · AutobahnClient, TrafficClient |

## B

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| BaFin Directors' Dealings | Filings | `portal.mvp.bafin.de` | bafin · InsiderDealingsClient |
| Bankier.pl (Polen) | News | `www.bankier.pl` | briefing · MarketPressClient |
| BEA (US-Wirtschaftsdaten) | Makro | `apps.bea.gov` | briefing · StatsReleaseCalendarClient |
| Benzinga | News | `www.benzinga.com` | benzinga · BenzingaNewsClient |
| Binance Futures | Krypto | `fapi.binance.com` | briefing · CryptoDerivsClient |
| Bing Websuche | Auflösung | `www.bing.com` | websearch · BingWebSearchClient; agent · BingNewsSearchClient |
| Bloomberg Feeds | News | `feeds.bloomberg.com` | bloomberg · BloombergNewsClient; briefing · MarketPressClient |
| Bluesky | Sentiment | `bsky.app`, `api.bsky.app` | bluesky · BlueskyNewsClient |
| boerse.de | Börse · News | `www.boerse.de` | boerse-de · BoerseDeMarketClient, BoerseDeNewsClient |
| Borsa Italiana Termine | Kalender | `www.borsaitaliana.it` | briefing · BorsaItalianaEventsClient |
| Börse Frankfurt / Xetra Historie | Börse | `api.boerse-frankfurt.de` | boerse-frankfurt · XetraHistoryClient, OrderBookClient |
| Börse Online | News | `www.boerse-online.de` | boersenmedien · BoersenmedienNewsClient |
| BrightSky (DWD-Wetter) | Welt | `api.brightsky.dev` | briefing · CuriositiesClient |
| Bundesanzeiger Leerverkäufe | Filings | `www.bundesanzeiger.de` | bundesanzeiger · ShortInterestClient |
| Bundesbank-Statistiken | Makro | `api.statistiken.bundesbank.de` | briefing · BundYieldClient |

## C

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Cboe Put/Call-Ratio | Börse | `cdn.cboe.com` | briefing · CboePutCallClient |
| CBRates (Leitzinsen) | Makro | `www.cbrates.com` | briefing · CentralBankCalendarClient |
| Celestrak (Satellitenbahnen) | Welt | `celestrak.org` | briefing · SatelliteClient |
| Cinco Días (Spanien) | News | `feeds.elpais.com` | briefing · MarketPressClient |
| CISA KEV (Cyber) | Welt | `www.cisa.gov` | briefing · CisaKevClient |
| CNBC Kurse & Earnings | Börse | `quote.cnbc.com`, `gdsapi.cnbc.com`, `gds-earnings.cnbc.com` | cnbc · CnbcQuoteClient |
| CNBC News | News | `www.cnbc.com`, `api.queryly.com`, `search.cnbc.com` | cnbc · CnbcNewsClient; briefing · MarketPressClient |
| CNN Fear & Greed | Sentiment | `production.dataviz.cnn.io`, `edition.cnn.com` | fear-greed · FearGreedClient |
| CoinGecko | Krypto | `api.coingecko.com` | briefing · CoinGeckoClient |
| comdirect Community | Sentiment | `community.comdirect.de` | comdirect · ComdirectCommunityClient |
| Consorsbank | Börse | `www.consorsbank.de` | consorsbank · ConsorsbankClient |
| Crypto Fear & Greed | Sentiment | `api.alternative.me` | fear-greed · CryptoFearGreedClient |

## D

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Dagens Industri (Schweden) | News | `www.di.se` | briefing · MarketPressClient |
| DAWUM (Wahlumfragen) | Welt | `api.dawum.de` | briefing · DawumClient |
| der aktionär | News | `www.deraktionaer.de` | boersenmedien · BoersenmedienNewsClient |
| Der Standard (Österreich) | News | `www.derstandard.at` | briefing · MarketPressClient |
| Deribit | Krypto | `www.deribit.com` | briefing · CryptoDerivsClient |
| Destatis | Makro | `www.destatis.de` | briefing · MacroPressClient |
| Deutsche Börse Handelskalender | Kalender | `www.cashmarket.deutsche-boerse.com` | briefing · TradingCalendarClient |
| DIVI-Intensivregister | Welt | `www.intensivregister.de` | briefing · DiviClient |
| Dow Jones Feeds | News | `feeds.content.dowjones.io` | briefing · MarketPressClient |
| DWD Warnkarten | Welt | `maps.dwd.de` | briefing · GermanWeatherAlertClient |

## E

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| EarningsWhispers | Kalender | `www.earningswhispers.com` | briefing · EarningsWhispersClient |
| Eastmoney (China) | News | `rss.eastmoney.com` | briefing · MarketPressClient |
| ECB Daten & Feeds | Makro | `data-api.ecb.europa.eu`, `www.ecb.europa.eu` | briefing · EcbFeedsClient, CentralBankCalendarClient |
| Economic Times (Indien) | News | `economictimes.indiatimes.com` | briefing · MarketPressClient |
| EDGAR (SEC) | Filings | `data.sec.gov`, `www.sec.gov` | edgar · EdgarClient |
| EDGAR Volltextsuche | Filings | `efts.sec.gov`, `www.sec.gov` | edgar · EdgarFullTextClient |
| EIA Ölbericht (WPSR) | Makro | `ir.eia.gov` | briefing · EiaWpsrClient |
| Energy-Charts | Welt | `api.energy-charts.info` | briefing · EnergyChartsClient |
| ENSO / El Niño (NOAA) | Welt | `www.cpc.ncep.noaa.gov` | briefing · EnsoClient |
| EONET (NASA-Naturereignisse) | Welt | `eonet.gsfc.nasa.gov` | briefing · EonetClient |
| EQS Ad-hoc & Termine | Filings · Kalender | `www.eqs-news.com` | briefing · EqsEventsClient, EqsNewsArchiveClient |
| EU-Kommission Presscorner | Welt | `ec.europa.eu` | briefing · EuPresscornerClient, StatsReleaseCalendarClient |
| Eurex Handelskalender | Kalender | `www.eurex.com` | briefing · TradingCalendarClient |
| Eurex Open Interest | Börse | `www.eurex.com` | briefing · EurexOpenInterestClient |
| Euronext | Börse | `live.euronext.com` | euronext · EuronextClient |
| Eurostat | Makro | `ec.europa.eu` | briefing · EurostatClient |
| EUWAX Sentiment | Sentiment | `api.onvista.de` | onvista · EuwaxSentimentClient |
| Expansión (Spanien) | News | `e00-expansion.uecdn.es` | briefing · MarketPressClient |

## F

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| FAA Luftraum-Status | Welt | `nasstatus.faa.gov` | briefing · GlobalHazardsClient |
| FCA Leerverkaufsregister (UK) | Filings | `www.fca.org.uk` | briefing · FcaShortPositionsClient |
| Federal Register | Welt | `www.federalregister.gov` | briefing · FederalRegisterClient |
| Federal Reserve | Makro | `www.federalreserve.gov` | briefing · FedFeedsClient, CentralBankCalendarClient |
| ferien-api.de | Kalender | `ferien-api.de` | briefing · HolidayCalendarClient |
| Financial Juice | News | `www.financialjuice.com` | financial-juice · FjScraper |
| finanzen.net (Kurse) | Börse | `www.finanzen.net`, `g.finanzen.net` | finanzen-net · FinanzenNetMarketClient, FinanzenNetResolver, FinanzenNetHttp |
| finanzen.net (News) | News | `www.finanzen.net` | finanzen-net · FinanzenNetNewsClient |
| finanznachrichten.de | News | `www.finanznachrichten.de` | fn-news · FnInstrumentNewsClient; briefing · FnRssClient |
| FINRA Short Volume | Filings | `cdn.finra.org` | finra · FinraShortVolumeClient; briefing · FinraShortVolumeClient |
| Firmen-Websites (dynamisch) | News · Filings | <Firmen-Hostfamilie> | agent · CompanySiteCrawler, CompanyPressScout, CompanyLogoFetcher |
| FIRMS (NASA-Waldbrände) | Welt | `firms.modaps.eosdis.nasa.gov` | briefing · WildfireClient |
| Forex Factory Kalender | Kalender | `nfs.faireconomy.media` | briefing · EconCalendarClient; terminal · CefWebFetcher |
| Frankfurter (Wechselkurse) | Börse | `api.frankfurter.dev` | currency · EurUsdClient |
| FRED (St. Louis Fed) | Makro | `fred.stlouisfed.org` | briefing · FredSeriesClient |
| FT (Financial Times) | News | `www.ft.com` | briefing · MarketPressClient |
| FTMO Wirtschaftskalender | Kalender | `gw2.ftmo.com` | briefing · EconCalendarClient |
| FXStreet Kalender | Kalender | `calendar-api.fxstreet.com`, `www.fxstreet.com` | briefing · FxStreetCalendarClient |

## G

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| GDACS (Katastrophen) | Welt | `www.gdacs.org` | briefing · GdacsClient |
| GDELT Doc-Suche | Auflösung | `api.gdeltproject.org` | websearch · GdeltDocClient |
| GDELT Konflikt-Daten | Welt | `data.gdeltproject.org` | briefing · GdeltConflictClient |
| GDELT Weltindex (16 Sprachen) | News | `api.gdeltproject.org` | websearch · GdeltWorldClient |
| GitHub (Releases/Update) | Infra | `api.github.com`, `github.com` | terminal · GitHubReleases, LauncherUpdateService; updater · GitHubRepository |
| GitHub Raw (RKI-Daten) | Welt | `raw.githubusercontent.com` | briefing · RkiSurveillanceClient |
| Google News | News | `news.google.com` | google-news · GoogleNewsClient; agent · GoogleNewsUrlResolver |
| Google News Weltausgaben (12 Editionen) | News | `news.google.com` | google-news · GoogleNewsWorldClient |

## H

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Hacker News | Sentiment | `news.ycombinator.com`, `hn.algolia.com` | hackernews · HackerNewsClient |
| Handelsblatt | News | `www.handelsblatt.com`, `feeds.cms.handelsblatt.com`, `content.www.handelsblatt.com`, `market.www.handelsblatt.com` | handelsblatt · HandelsblattNewsClient, HandelsblattMarketClient, HandelsblattBrand |
| Harper Petersen (Harpex) | Welt | `www.harperpetersen.com` | briefing · HarpexClient |
| Het Financieele Dagblad (NL) | News | `fd.nl` | briefing · MarketPressClient |
| HKEX (Hongkong) | Börse | `www.hkex.com.hk`, `www1.hkex.com.hk` | hkex · HkexClient |

## I

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| ifo-Institut | Makro | `www.ifo.de` | briefing · MacroPressClient |
| Il Sole 24 Ore (Italien) | News | `www.ilsole24ore.com` | briefing · MarketPressClient |
| InfoMoney (Brasilien) | News | `www.infomoney.com.br` | briefing · MarketPressClient |
| InsiderMonkey | Sentiment | `www.insidermonkey.com`, `www.sec.gov` | insidermonkey · InsiderMonkeyClient |
| Interfax (Russland) | News | `www.interfax.ru` | briefing · MarketPressClient |
| Internet-Ausfälle (IODA) | Welt | `api.ioda.inetintel.cc.gatech.edu` | briefing · InternetOutageClient |
| Investegate (UK-RNS) | Filings · News | `www.investegate.co.uk` | briefing · InvestegateRnsClient |
| investing.com | News | `www.investing.com` | briefing · MarketPressClient |
| ISS-Position | Welt | `api.wheretheiss.at` | briefing · OrbitClient |

## K

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Kapitalmarktexperten | News | `www.kapitalmarktexperten.de` | kapitalmarktexperten · KapitalmarktexpertenClient |
| Kommersant (Russland) | News | `www.kommersant.ru` | briefing · MarketPressClient |

## L

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Lang & Schwarz | Börse | `www.ls-tc.de` | lang-schwarz · LangSchwarzClient |
| Launch-Kalender (Space Devs) | Welt | `ll.thespacedevs.com` | briefing · LaunchPadClient |
| Les Echos (Frankreich) | News | `services.lesechos.fr` | briefing · MarketPressClient |

## M

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| MarketBeat | News | `www.marketbeat.com` | marketbeat · MarketBeatClient |
| MFN (Nordische Meldungen) | Filings | `mfn.se` | briefing · MfnDisclosureClient |
| Minkabu (Japan) | Börse | `mkdd.net` | minkabu · MinkabuClient |
| MVG München | Welt | `www.mvg.de` | briefing · TrafficClient |

## N

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| n-tv | News | `www.n-tv.de` | briefing · MarketPressClient |
| nager.at Feiertage | Kalender | `date.nager.at` | briefing · HolidayCalendarClient; terminal · HolidayProvider |
| Nasdaq (Unternehmen/Kurse) | Börse | `api.nasdaq.com`, `www.nasdaq.com` | nasdaq · NasdaqCompanyClient; terminal · CefWebFetcher |
| Nasdaq Kalender | Kalender | `api.nasdaq.com`, `www.nasdaq.com` | briefing · NasdaqCalendarClient |
| Nasdaq News | News | `www.nasdaq.com` | nasdaq · NasdaqNewsRssClient |
| Nasdaq Nordic | Börse | `api.nasdaq.com` | nordic · NordicMarketClient |
| NHC (Hurrikane) | Welt | `www.nhc.noaa.gov` | briefing · GlobalHazardsClient |
| Nikkei (Japan) | News | `assets.wor.jp` | briefing · MarketPressClient |
| NYSE | Börse | `www.nyse.com` | nyse · NyseQuoteClient |
| NZZ (Schweiz) | News | `www.nzz.ch` | briefing · MarketPressClient |

## O

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| ONS (UK-Statistik) | Makro | `api.beta.ons.gov.uk` | briefing · StatsReleaseCalendarClient |
| Onvista | Börse | `api.onvista.de`, `www.onvista.de` | onvista · OnvistaClient, OnvistaApi, OnvistaPageBundle, OnvistaEntityResolver |
| Open-Meteo | Welt | `api.open-meteo.com`, `air-quality-api.open-meteo.com` | briefing · WorldWeatherClient, GlobalWeatherGridClient, AirQualityGridClient |
| OpenLigaDB | Welt | `api.openligadb.de` | briefing · SportsCalendarClient |

## P

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Pegelonline (Rhein/Wasserstände) | Welt | `www.pegelonline.wsv.de` | briefing · RhinePegelClient, WaterLevelClient |
| People's Daily (China) | News | `www.people.com.cn` | briefing · MarketPressClient |
| Polymarket | Sentiment | `gamma-api.polymarket.com` | briefing · PolymarketClient |
| Portfolio.hu (Ungarn) | News | `www.portfolio.hu` | briefing · MarketPressClient |
| PR Newswire UK | News | `www.prnewswire.co.uk` | prnewswire · PrNewswireUkClient |
| Presseportal | News | `www.presseportal.de` | briefing · PresseportalClient |

## R

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| RBC (Russland) | News | `rssexport.rbc.ru` | briefing · MarketPressClient |
| Reddit (via Browser-Joker) | Reddit | `www.reddit.com` | terminal · CefFetchClient, HeadlineJson |
| **Reddit - Kommentar-Strom (sub-weit)** | Reddit | `www.reddit.com`, `oauth.reddit.com` | reddit · RedditScraper#scanComments, RssRedditScraper#scanComments |
| **Reddit - Threads/Listing** | Reddit | `www.reddit.com`, `oauth.reddit.com` | reddit · RedditScraper, RssRedditScraper, OAuthRedditFetcher |
| Reuters | News | `www.reuters.com` | reuters · ReutersNewsClient; briefing · MarketPressClient |

## S

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Sanktionskarte (EU) | Welt | `www.sanctionsmap.eu` | briefing · SanctionsMapClient |
| Saudi Exchange (TASI) | Börse | `www.saudiexchange.sa` | briefing · WalledExchangeClient |
| SCMP (Hongkong) | News | `www.scmp.com` | briefing · MarketPressClient |
| SEC Form 4 (US-Insider) | Filings | `data.sec.gov`, `www.sec.gov` | edgar · EdgarInsiderClient |
| SEC Ticker-Liste | Auflösung | `www.sec.gov` | instrument-corpus · SecTickerSource |
| SEC XBRL Company Facts | Filings | `data.sec.gov` | edgar · EdgarFactsClient |
| SET Thailand | Börse | `www.set.or.th` | briefing · WalledExchangeClient |
| sharedeals.de | News | `www.sharedeals.de` | sharedeals · SharedealsClient |
| SIX (Schweiz) | Börse | `www.six-group.com` | six · SixMarketClient |
| Space Weather (NOAA SWPC) | Welt | `services.swpc.noaa.gov` | briefing · SpaceWeatherClient |
| Spiegel | News | `www.spiegel.de` | briefing · MarketPressClient |
| Spot.IM Kommentare | Sentiment | `api-2-0.spot.im` | yahoo-conversations · YahooConversationsClient; terminal · OpenWebConversationFetcher |
| Status-Seiten (Claude, OpenAI, Cloudflare, GitHub, Netlify, Vercel, Discord) | Welt | `status.claude.com`, `status.openai.com`, `www.cloudflarestatus.com`, `www.githubstatus.com`, `www.netlifystatus.com`, `www.vercel-status.com`, `discordstatus.com` | briefing · ServiceStatusClient |
| Stocknear | Sentiment | `stocknear.com`, `reddit.com` | stocknear · StocknearClient |
| StockTwits | Sentiment | `stocktwits.com`, `api.stocktwits.com` | stocktwits · StocktwitsClient |
| STOXX Europe 600 Supersektoren | Börse | `api.onvista.de` | onvista · StoxxSectorClient |

## T

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Tagesschau | News | `www.tagesschau.de` | briefing · TagesschauClient |
| TASS (Russland) | News | `tass.ru` | briefing · MarketPressClient |
| Telegram-Kanäle | Sentiment | `t.me` | telegram · TelegramChannelClient |
| TMX (Kanada) | Börse | `app-money.tmx.com` | tmx · TmxMarketClient |
| Tradegate | Börse | `www.tradegate.de`, `www.tradegatebsx.com` | tradegate · TradegateQuoteClient; briefing · TradingCalendarClient |
| TradersUnion | News · Börse | `tradersunion.com`, `quotes.tradersunion.com` | tradersunion · TradersUnionNewsClient, TradersUnionMoversClient |
| TradingEconomics | Makro | `tradingeconomics.com` | briefing · TradingEconomicsClient |
| TradingView (Minds/Scanner/Suche) | Sentiment · Börse | `www.tradingview.com`, `scanner.tradingview.com`, `symbol-search.tradingview.com` | tradingview · TradingViewMindsClient, TradingViewScanner, TradingViewSymbolSearch, TradingViewApi, SectorBoardClient |
| TradingView Kalender | Kalender | `economic-calendar.tradingview.com`, `www.tradingview.com` | briefing · TradingViewCalendarClient |
| TradingView News | News | `news-headlines.tradingview.com`, `news-mediator.tradingview.com` | tradingview · TradingViewNewsClient |
| Treasury Fiscal Data | Makro | `api.fiscaldata.treasury.gov` | briefing · CuriositiesClient |

## U

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| USGS Erdbeben | Welt | `earthquake.usgs.gov` | briefing · GlobalHazardsClient |

## W

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| wallstreet-online (Board) | Sentiment | `www.wallstreet-online.de` | wallstreet-online · WsoBoardRssClient |
| wallstreet-online (Kurse/ISIN) | Börse | `www.wallstreet-online.de` | wallstreet-online · WallstreetOnlineClient |
| wallstreet-online (News) | News | `www.wallstreet-online.de` | wallstreet-online · WsoNewsClient |
| wallstreet-online (Termine) | Kalender | `www.wallstreet-online.de` | briefing · WoCompanyCalendarClient |
| Weiße Haus (Aktionen) | Welt | `www.whitehouse.gov` | briefing · WhiteHouseActionsClient |
| Welt | News | `www.welt.de` | welt · WeltNewsClient |
| WHO Ausbrüche | Welt | `www.who.int` | briefing · WhoOutbreakClient |
| Wiener Börse | Börse | `www.wienerborse.at` | wienerboerse · WienerBoerseClient |
| Wikidata | Auflösung | `query.wikidata.org`, `www.wikidata.org`, `wikimedia.org`, `github.com` | briefing · WikidataClient |
| Wikipedia Current Events | Welt | `en.wikipedia.org`, `github.com` | briefing · WikipediaCurrentEventsClient |
| Wikipedia Suche | Welt | `de.wikipedia.org`, `en.wikipedia.org` | agent · WikipediaSearchClient |
| WirtschaftsWoche | News | `www.wiwo.de`, `feeds.cms.wiwo.de`, `content.www.wiwo.de` | handelsblatt · HandelsblattNewsClient, HandelsblattBrand; briefing · MarketPressClient |

## X

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Xetra Instrumentenliste | Auflösung | `www.xetra.com` | instrument-corpus · XetraSource |
| Xinhua (China) | News | `www.xinhuanet.com` | briefing · MarketPressClient |

## Y

| Quelle | Kat. | Host(s) | Modul · Klasse |
|---|---|---|---|
| Motley Fool | News | `www.fool.com`, `api.fool.com`, `www.google.com` | fool · FoolNewsClient |
| Yahoo Finance (Kommentare) | Sentiment | `finance.yahoo.com` | yahoo-conversations · YahooConversationsClient |
| Yahoo Finance (Kurse/Bars/Suche) | Börse | `query1.finance.yahoo.com`, `query2.finance.yahoo.com` | yahoo-finance · YahooFinanceClient; currency · EurUsdClient |
| Yonhap (Korea) | News | `www.yna.co.kr` | briefing · MarketPressClient |

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
| finanznachrichten (eigenes Modul) | ersetzt durch `fn-news` + `briefing/FnRssClient` |
| Google Finance | Anti-Bot-Wall |
| Stooq | Proof-of-Work-Wall |

---

## Notizen zur Reichweite

- **Firmen-Websites haben keinen festen Host:** Die Firmensite-Kette (`CompanySiteCrawler`, `CompanyPressScout`, `CompanyLogoFetcher`) ruft die Domain an, die zur jeweiligen Firma aufgelöst wurde - sie taucht deshalb im Prüfbefehl NIE auf, weil im Quelltext keine URL steht. Sie zählt trotzdem als Quelle und steht darum als eigene Zeile im Register.
- **Tradegate ist umgezogen:** Kalenderdaten laufen über `www.tradegatebsx.com`, Kurse weiter über `www.tradegate.de`.
- **Yahoo:** frei sind `v8/chart`, `v1/search`, `v7/spark`; `v7/quote` und `v10` sind crumb-locked. EUR/USD über `v8/chart` (der dedizierte Endpunkt liefert hart 401).
- **Reddit-Budget:** anonym ~100 Requests / 10 min pro IP. Der sub-weite Kommentar-Strom kostet **einen** Request pro Subreddit und Zyklus - gemessen am 2026-08-10 decken 100 Einträge 21 min (r/wallstreetbets) bzw. 41 min (r/wallstreetbetsGER) ab.
- **Browser-Joker:** Quellen mit JS-Wall laufen über den eingebetteten CEF (`CefWebFetcher`), nicht über den direkten HTTP-Pfad.
- **Google News ist pro Ausgabe eine eigene Quelle:** Die Heimatausgabe (`hl=de`) bedient die Wire, die zwölf Weltausgaben sind dossier-only und stempeln je Artikel Sprache und Sphäre.
- **GDELT hat ein hartes Tor:** ein Request alle 8 s, JVM-weit für BEIDE GDELT-Clients zusammen (`GdeltGate`). Ein Burst kostet minutenlange IP-Sperren. Die Query-Länge ist ebenfalls begrenzt - 16 `sourcelang`-Klauseln antworten „query too long", darum fragt der Weltindex in Gruppen zu vier Sprachen.
- **FRED war nie eine Wall, sondern ein Header-Problem:** Der Host verlangt den VOLLEN Browser-Headersatz UND `Accept-Encoding` - gemessen antwortet er auf beides zusammen mit 200, auf jede Hälfte allein mit einem HTTP/2-Stream-Reset. Seit `DirectWebFetcher` das mitschickt (und gzip auspackt), liefern alle 14 Reihen. Dieselbe Änderung hat Les Echos, Il Sole, Cinco Días, FD.nl, IDX und Investegate zurückgebracht.
- **RSS-Fallen, an der ganzen Presseschau gemessen (2026-08-11):** Ein UTF-8-BOM vor der XML-Deklaration kostet StAX das GANZE Dokument; ein striktes `Accept` ohne `*/*` beantworten manche Häuser mit 406; RSS 1.0 datiert über `dc:date` statt `pubDate`; die Zone „Z" ist kein gültiger RFC-1123-Zonenname; und ein Feed mit Artikel-Volltext im Item sprengt `getElementText`. Alle fünf sind in `Rss`/`MarketPressClient` behoben - jede kostete vorher einen Feed komplett und lautlos.
- **Sektor ≠ Branche:** Die Sektorfrage beantwortet die Branchentafel (`SectorBoardClient`, Median über die gelisteten Titel EINER Branche, Titelzahl immer dabei) plus der europäische Supersektor-Index. Der alte US-Sektor-ETF-Stellvertreter bleibt daneben stehen, er trägt die Preisreihe.
- **Eurex-Produkt-IDs:** `overallstatistics/<id>` beschreibt sich selbst (`meta.productCode`, `meta.isin` = Basiswert). Die IDs liegen dünn über einen weiten Raum verstreut; katalogisiert sind die Index- und Zinsbücher. Einzelaktien-Optionen brauchen einen einmal gescannten Index - der Mechanismus steht, der Index nicht.
- **UK-RNS IST angebunden:** über Investegates server-gerendertes Ankündigungs-Register (138 Meldungen über 3 Seiten, minutenfrisch). Die Tür ging erst auf, als der Direktweg den vollen Browser-Headersatz zu schicken begann - vorher 401/404. Die Komponente der Londoner Börse selbst bleibt zu (POST antwortet 200 mit `[]` für jede Parameterform); lse.co.uk, sharecast und advfn stehen hinter Cloudflare.
- **EUWAX Sentiment IST angebunden - über die Seitentür:** boerse-stuttgart.de steht hinter einer Cloudflare-Challenge, der Index ist aber ein gelistetes Instrument (`DE000A1MAA56`) und wird über onvista gequotet. Gleiche Zahl, offene Tür, ein Request. Für eine Seite, die man nie gesehen hat, schreibt das Haus keinen Parser.
- **CNMV (ES) und AMF (FR) Leerverkäufe:** nur als HTML-Seite zu haben, kein CSV/JSON gefunden - Scraping-Kandidaten, nicht gebaut. CNMV liefert zusätzlich eine unvollständige Zertifikatskette (PKIX-Fehler auf dem Direktweg).
- 🃏 **Die Browser-Wand ist KEINE Automations-Erkennung** (gemessen 2026-08-11 mit echtem Chromium): Ein kaltes Profil bekommt die Erstbesuchs-Challenge, eine Sitzung, die sie einmal bestanden hat, wird normal bedient - mit und ohne jedes Anti-Automations-Flag. Genau diese Form hat der Joker: EINE verankerte Sitzung je Origin. Heißt: Jede Wand-Quelle ist erreichbar, sobald sie verdrahtet ist.
- 🃏 **Wand-Quellen aufnehmen, ohne zu raten:** Die Seite einmal mit echtem Chromium abziehen (`--headless=new --disable-gpu --disable-blink-features=AutomationControlled --window-size=1440,900 --user-agent=<echte UA> --virtual-time-budget=15000 --dump-dom`), Parser gegen das ECHTE Dokument schreiben, Client über `@DirectFirst` verdrahten, Smoke als joker-only markieren. So entstanden SET und TASI.
- **Korea (KRX) ist NICHT gebaut:** Die Seiten tragen Navigation und keinen Indexstand; die Zahlen liegen hinter einer POST-API, deren Request-Form sich von außen nicht feststellen lässt. Ein Client auf eine geratene Form wäre ein stiller Ausfall im Namen einer Quelle.
- **SEC-Archivpfade:** Die Volltextsuche liefert je Treffer die CIK des Einreichers - der Archiv-Pfad muss DIESE nutzen, nicht den führenden Block der Accession-Nummer (das ist die Kennung des Einreicher-Dienstleisters, der Pfad daraus antwortet 404).
