package br.com.bauzin.market.panic.panicscanner.application.replay;

import br.com.bauzin.market.panic.panicscanner.application.*;
import br.com.bauzin.market.panic.panicscanner.application.engine.TechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.application.entry.EntryAnalyzer;
import br.com.bauzin.market.panic.panicscanner.application.usecase.*;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.RankingMode;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.*;

import java.time.*;
import java.util.*;

/** End-of-session signal audit. No execution, positions or performance simulation. */
public final class PanicHistoricalReplayRunner {
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");
    private final MarketDataClient historical;
    private final TechnicalAnalysisEngine engine;
    private final ScannerStrategy strategy;
    private final EntryAnalyzer entry;
    private final PanicScannerProperties properties;

    public PanicHistoricalReplayRunner(MarketDataClient historical, TechnicalAnalysisEngine engine,
                                      ScannerStrategy strategy, EntryAnalyzer entry, PanicScannerProperties properties) {
        this.historical = historical;
        this.engine = engine;
        this.strategy = strategy;
        this.entry = entry;
        this.properties = properties;
    }

    public record Request(LocalDate from, LocalDate to, List<String> tickers, RankingMode rankingMode,
                          String historicalProvider) {
        public Request {
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
            tickers = tickers == null ? List.of() : tickers.stream()
                    .map(t -> t.trim().toUpperCase(Locale.ROOT)).distinct().toList();
            if (tickers.stream().anyMatch(t -> !t.matches("[A-Z]{4}[0-9]{1,2}")))
                throw new IllegalArgumentException("Invalid B3 ticker");
            rankingMode = rankingMode == null ? RankingMode.MOMENTUM : rankingMode;
            if (!List.of("mt5", "brapi").contains(historicalProvider))
                throw new IllegalArgumentException("historical provider must be mt5 or brapi");
        }
    }

    public record Row(LocalDate simulatedDate, OffsetDateTime simulatedAt, String historicalProvider,
                      RankingMode rankingMode, Integer selectedRank, ScannerResult result,
                      List<String> blockingChecks, String mainReason, List<String> statusChanges) {}

    public List<Row> run(Request request) {
        List<StockSummary> universe = request.tickers().isEmpty()
                ? historical.listStocks().stream().filter(PanicHistoricalReplayRunner::eligibleEquity)
                    .toList()
                : request.tickers().stream().map(t -> new StockSummary(t, t, "STOCK", true)).toList();
        Map<String, List<Candle>> datasets = new LinkedHashMap<>();
        SortedSet<LocalDate> sessions = new TreeSet<>();
        for (StockSummary stock : universe) {
            String ticker = stock.ticker().trim().toUpperCase(Locale.ROOT);
            if (datasets.containsKey(ticker)) continue;
            List<Candle> candles = historical.getDailyCandles(ticker,
                    request.from().minusDays(properties.lookbackDays()), request.to()).stream()
                    .filter(c -> c.status() == CandleStatus.CLOSED)
                    .filter(c -> !c.date().isBefore(request.from().minusDays(properties.lookbackDays()))
                            && !c.date().isAfter(request.to()))
                    .sorted(Comparator.comparing(Candle::date)).toList();
            for (int i = 1; i < candles.size(); i++) {
                if (candles.get(i - 1).date().equals(candles.get(i).date()))
                    throw new IllegalArgumentException("Duplicate session for " + ticker);
            }
            datasets.put(ticker, candles);
            candles.stream().map(Candle::date).filter(d -> !d.isBefore(request.from())).forEach(sessions::add);
        }
        List<Row> rows = new ArrayList<>();
        Map<String, ScannerResult> previous = new HashMap<>();
        for (LocalDate session : sessions) {
            Clock clock = Clock.fixed(session.atTime(LocalTime.MAX).atZone(MARKET_ZONE).toInstant(), MARKET_ZONE);
            MarketDataClient bounded = bounded(datasets, session, clock);
            AnalyzeStockUseCase analyze = new AnalyzeStockUseCase(bounded, engine, strategy, entry, properties, clock);
            var scan = new ScanB3MarketUseCase(bounded, analyze, clock).execute(new MarketScanCriteria(
                    properties.minAverageFinancialVolume20(), properties.minimumQualifiedScore(), 0,
                    List.of(), request.rankingMode(), properties.maxMarketScanResults()));
            Map<String, Integer> ranks = new HashMap<>();
            for (int i = 0; i < scan.results().size(); i++) ranks.put(scan.results().get(i).ticker(), i + 1);
            for (String ticker : datasets.keySet()) {
                ScannerResult result = analyze.execute(ticker);
                List<String> blockers = blockingChecks(result);
                List<String> changes = changes(previous.get(ticker), result);
                String reason = result.status() == SignalStatus.QUALIFIED ? ""
                        : !result.checks().historyAccepted() ? String.join(";", result.reasons())
                        : !blockers.isEmpty() ? String.join(";", blockers)
                        : result.score() < properties.minimumQualifiedScore() ? "momentumScoreBelowMinimum"
                        : String.join(";", result.reasons());
                rows.add(new Row(session, OffsetDateTime.now(clock), request.historicalProvider(),
                        request.rankingMode(), ranks.get(ticker), result, blockers, reason, changes));
                previous.put(ticker, result);
            }
        }
        return List.copyOf(rows);
    }

    private static boolean eligibleEquity(StockSummary stock) {
        if (stock.ticker() == null || !stock.ticker().trim().toUpperCase(Locale.ROOT).matches("[A-Z]{4}[0-9]{1,2}")
                || Boolean.FALSE.equals(stock.active())) return false;
        if (stock.type() == null || stock.type().isBlank()) return true;
        String type = stock.type().trim().toUpperCase(Locale.ROOT);
        return List.of("ETF", "FII", "BDR", "FUND", "FUNDO").stream().noneMatch(type::contains)
                && List.of("STOCK", "EQUITY", "ACAO", "AÇÃO").stream().anyMatch(type::contains);
    }

    /** The only client visible to analysis/ranking: upper bound is enforced even if source ignores it. */
    static MarketDataClient bounded(Map<String, List<Candle>> datasets, LocalDate session, Clock clock) {
        return new MarketDataClient() {
            public List<StockSummary> listStocks() {
                return datasets.keySet().stream().map(t -> new StockSummary(t, t, "STOCK", true)).toList();
            }
            public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
                LocalDate upper = to.isBefore(session) ? to : session;
                return datasets.getOrDefault(ticker, List.of()).stream()
                        .filter(c -> c.status() == CandleStatus.CLOSED && !c.date().isBefore(from) && !c.date().isAfter(upper))
                        .map(c -> new Candle(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume(),
                                CandleStatus.CLOSED, OffsetDateTime.now(clock)))
                        .sorted(Comparator.comparing(Candle::date)).toList();
            }
        };
    }

    private static List<String> blockingChecks(ScannerResult r) {
        var c = r.checks();
        List<String> blocked = new ArrayList<>();
        if (!c.closeAboveSma21()) blocked.add("closeAboveSma21");
        if (!c.sma9AboveSma21()) blocked.add("sma9AboveSma21");
        if (!c.rsiInsideRange()) blocked.add("rsiInsideRange");
        if (!c.adxAccepted()) blocked.add("adxAccepted");
        if (!c.liquidityAccepted()) blocked.add("liquidityAccepted");
        if (!c.historyAccepted()) blocked.add("historyAccepted");
        return List.copyOf(blocked);
    }

    private static List<String> changes(ScannerResult before, ScannerResult now) {
        if (before == null) return List.of();
        List<String> changes = new ArrayList<>();
        if (before.status() != now.status()) changes.add("SignalStatus:" + before.status() + "->" + now.status());
        var oldEntry = before.entryAnalysis(); var newEntry = now.entryAnalysis();
        String oldStatus = oldEntry == null ? "NONE" : oldEntry.status().name();
        String newStatus = newEntry == null ? "NONE" : newEntry.status().name();
        if (!oldStatus.equals(newStatus)) changes.add("entryStatus:" + oldStatus + "->" + newStatus);
        return List.copyOf(changes);
    }
}
