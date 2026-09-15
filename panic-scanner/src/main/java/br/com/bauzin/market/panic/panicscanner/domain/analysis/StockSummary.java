package br.com.bauzin.market.panic.panicscanner.domain.analysis;

/** Basic market-listed stock metadata independent from data-provider DTOs. */
public record StockSummary(
        String ticker,
        String name,
        String type,
        Boolean active) {

    public StockSummary(String ticker, String name, String type) {
        this(ticker, name, type, true);
    }
}
