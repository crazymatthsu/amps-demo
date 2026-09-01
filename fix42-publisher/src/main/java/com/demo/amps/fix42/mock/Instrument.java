package com.demo.amps.fix42.mock;

/**
 * The instrument fields a realistic order carries, held together so a mock
 * order cannot claim AAPL's symbol with MSFT's identifier.
 *
 * @param symbol           tag 55
 * @param securityId       tag 48
 * @param securityIdSource tag 22 (1 = CUSIP, 4 = ISIN)
 * @param currency         tag 15
 * @param exDestination    tag 100, also used as LastMkt (tag 30) on fills
 */
public record Instrument(String symbol, String securityId, String securityIdSource,
                         String currency, String exDestination) {

    public static final Instrument AAPL =
            new Instrument("AAPL", "US0378331005", "4", "USD", "XNAS");
    public static final Instrument MSFT =
            new Instrument("MSFT", "US5949181045", "4", "USD", "XNAS");
    public static final Instrument GOOG =
            new Instrument("GOOG", "US02079K1079", "4", "USD", "XNAS");
    public static final Instrument TSLA =
            new Instrument("TSLA", "US88160R1014", "4", "USD", "XNAS");
    public static final Instrument NVDA =
            new Instrument("NVDA", "US67066G1040", "4", "USD", "XNAS");
    public static final Instrument AMZN =
            new Instrument("AMZN", "US0231351067", "4", "USD", "XNAS");
    public static final Instrument META =
            new Instrument("META", "US30303M1027", "4", "USD", "XNAS");
}
