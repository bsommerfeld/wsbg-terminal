package de.bsommerfeld.wsbg.terminal.fool;

/**
 * The instrument profile a Motley Fool quote page ships server-rendered
 * ({@code www.fool.com/quote/<exchange>/<symbol>/}, live-probed 2026-08-02).
 *
 * <p>The research note called the fundamentals there "thin (only
 * {@code currentPrice})". That reading picked up the site-wide ticker bar —
 * a fixed strip of indices and mega caps that is byte-identical on every quote
 * page. The page's OWN instrument sits in a separate {@code "ticker"} /
 * {@code "quote"} pair inside the Next.js payload and is anything but thin:
 * sector, industry, a prose business description, market cap, P/E, EPS,
 * dividend yield and the 52-week range all come keyless in one request.
 *
 * <p>Every numeric field is boxed on purpose: the page omits what it doesn't
 * have (funds carry an expense ratio but no EPS), and {@code null} says
 * "not reported" where {@code 0} would lie.
 *
 * @param symbol      the exchange symbol ({@code NVDA})
 * @param exchange    the venue as Fool names it ({@code NASDAQ}, {@code NYSE},
 *                    {@code CRYPTO})
 * @param name        the short company name ({@code Nvidia}, {@code Coca-Cola}) —
 *                    Fool's own short form, not the legal name
 * @param sector      GICS-style sector, or {@code null}
 * @param industry    GICS-style industry, or {@code null}
 * @param description a paragraph of business prose, or {@code null}
 * @param currency    ISO currency code of every amount below, or {@code null}
 * @param price       last price
 * @param changePct   session change as a FRACTION ({@code -0.0102} = -1.02 %),
 *                    not a percentage figure — Fool ships it that way
 * @param marketCap   market capitalisation
 * @param peRatio     trailing price/earnings
 * @param eps         trailing earnings per share
 * @param dividendYield annual dividend yield as a fraction ({@code 0.0237})
 * @param week52Low   52-week low
 * @param week52High  52-week high
 * @param volume      session volume
 */
public record FoolQuote(
        String symbol,
        String exchange,
        String name,
        String sector,
        String industry,
        String description,
        String currency,
        Double price,
        Double changePct,
        Double marketCap,
        Double peRatio,
        Double eps,
        Double dividendYield,
        Double week52Low,
        Double week52High,
        Long volume) {
}
