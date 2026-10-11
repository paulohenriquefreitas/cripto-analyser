package br.com.bauzin.market.panic.panicscanner.application.experimental;

import br.com.bauzin.market.panic.panicscanner.application.*;
import br.com.bauzin.market.panic.panicscanner.application.engine.TechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.application.usecase.AnalyzeStockUseCase;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.*;
import br.com.bauzin.market.panic.panicscanner.application.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ExperimentalEntryAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.ScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.MomentumScannerStrategy;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExperimentalEntryAnalyzerTest {
    final ExperimentalEntryAnalyzer analyzer = new ExperimentalEntryAnalyzer();
    final Ta4jTechnicalAnalysisAdapter real = new Ta4jTechnicalAnalysisAdapter();

    @Test void calculatesAdxAverageAndDirectionFromClosedPrefixOnly() {
        var candles=history(60);
        var a=real.analyze("TEST3","1D",candles);
        assertThat(a.adxSma5()).isNotNull();
        assertThat(a.adxRising()).isNotNull();
        assertThat(a.adxSma5Rising()).isNotNull();
        assertThat(a.plusDiAboveMinusDi()).isTrue();
        BigDecimal sum=BigDecimal.ZERO;
        for(int i=56;i<=60;i++) sum=sum.add(real.analyze("TEST3","1D",candles.subList(0,i)).adx14());
        assertThat(a.adxSma5().subtract(sum.divide(bd(5))).abs()).isLessThanOrEqualTo(bd(.0001));
        List<Candle> polluted=new ArrayList<>(candles);
        polluted.add(new Candle(candles.getLast().date().plusDays(1),bd(999),bd(999),bd(1),bd(999),bd(999999),CandleStatus.INTRADAY_PARTIAL,null));
        assertThat(real.analyze("TEST3","1D",polluted)).isEqualTo(a);
        assertThat(analyzer.analyze(a,polluted,real,BigDecimal.ONE)).isEqualTo(analyzer.analyze(a,candles,real,BigDecimal.ONE));
    }

    @Test void earlyReversalRequiresRecoveryMomentumAndAverageReaction() {
        Fixture f=fixture();
        assertThat(f.evaluate().patterns()).contains("EARLY_REVERSAL");
        f.current.put("rsi9",58);
        assertThat(f.evaluate().patterns()).doesNotContain("EARLY_REVERSAL");
    }
    @Test void adxRecoveryRequiresTwoRisesSmoothedRiseAndBullishDi() {
        Fixture f=fixture();
        assertThat(f.evaluate().patterns()).contains("ADX_RECOVERY");
        f.current.put("plusDiAboveMinusDi",false);
        assertThat(f.evaluate().patterns()).doesNotContain("ADX_RECOVERY");
        f.current.put("plusDiAboveMinusDi",true);f.previous.put("adxRising",false);
        assertThat(f.evaluate().patterns()).doesNotContain("ADX_RECOVERY");
        f.previous.put("adxRising",true);f.current.put("adxSma5Rising",false);
        assertThat(f.evaluate().patterns()).doesNotContain("ADX_RECOVERY");
    }
    @Test void pullbackNeedsEarlierRejectionThenClosedConfirmationAndPreservedLow() {
        Fixture f=fixture();
        assertThat(f.evaluate().patterns()).contains("PULLBACK_REENTRY");
        Candle last=f.candles.getLast();
        f.candles.set(34,new Candle(last.date(),last.open(),last.high(),bd(9.8),last.close(),last.volume()));
        assertThat(f.evaluate().patterns()).doesNotContain("PULLBACK_REENTRY");
    }
    @Test void sameBarSupportTouchCannotSubstituteForPreviousRejection() {
        Fixture f=fixture(); Candle p=f.candles.get(33);
        f.candles.set(33,new Candle(p.date(),bd(10.04),p.high(),p.low(),p.close(),p.volume()));
        assertThat(f.evaluate().patterns()).doesNotContain("PULLBACK_REENTRY");
    }
    @Test void lowLiquidityAndOverextensionBlockAllPatterns() {
        Fixture f=fixture();f.current.put("averageFinancialVolume20",0);
        assertThat(f.evaluate().signal()).isEqualTo("NONE");
        f.current.put("averageFinancialVolume20",100000000);f.current.put("distanceFromEma21Percent",7);
        assertThat(f.evaluate().signal()).isEqualTo("NONE");
    }
    @Test void shortHistoryAndMissingIndicatorsAreInconclusive() {
        var c=history(25);var a=real.analyze("TEST3","1D",c);
        assertThat(analyzer.analyze(a,c,real,BigDecimal.ONE).signal()).isEqualTo("INCONCLUSIVE");
    }
    @Test void futureCandlesCannotChangePastDecision() {
        Fixture f=fixture();var expected=f.evaluate();
        var last=f.candles.getLast();
        f.candles.add(new Candle(last.date().plusDays(1),bd(999),bd(999),bd(999),bd(999),bd(999999)));
        assertThat(f.evaluate()).isEqualTo(expected);
    }
    @Test void tickerDoesNotChangeExperimentalRules() {
        Fixture f=fixture();var expected=f.evaluate();
        f.current.put("ticker","MOVI3");f.previous.put("ticker","MOVI3");f.before.put("ticker","MOVI3");
        assertThat(f.evaluate()).isEqualTo(expected);
    }
    @Test void flagDefaultsOffAndScoresChecksAndEntryRemainIdentical() {
        List<Candle> c=history(60);LocalDate d=c.getLast().date();
        var clock=Clock.fixed(d.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC);
        MarketDataClient provider=new MarketDataClient() {
            public List<StockSummary> listStocks(){throw new AssertionError("No discovery");}
            public List<Candle> getDailyCandles(String t,LocalDate f,LocalDate end){return c;}
        };
        var props=new PanicScannerProperties(BigDecimal.ONE,90,20,null,null,null,0,null,null,0);
        var entryProps=ExperimentalComparisonCommand.JSON.convertValue(Map.of(),PanicScannerEntryProperties.class);
        var entry=new DefaultEntryAnalyzer(entryProps.toConfig(),new EntryScoreCalculator());
        var usecase=new AnalyzeStockUseCase(provider,real,new MomentumScannerStrategy(BigDecimal.ONE,new ScoreCalculator()),entry,props,clock);
        var off=usecase.execute("TEST3");
        var scoped=usecase.executeExperimental("TEST3");
        assertThat(scoped.experimentalEntry()).isNotNull();
        assertThat(usecase.execute("TEST3")).isEqualTo(off);
        var on=usecase.withExperimentalEntryEnabled(true).execute("TEST3");
        assertThat(off.experimentalEntry()).isNull();assertThat(on.experimentalEntry()).isNotNull();assertThat(off.entryAnalysis()).isNotNull();
        assertThat(on.withExperimentalEntry(null)).isEqualTo(off);
        List<Candle> polluted=new ArrayList<>(c);polluted.add(new Candle(d.plusDays(1),bd(999),bd(999),bd(999),bd(999),bd(999999)));
        MarketDataClient futureProvider=new MarketDataClient() {
            public List<StockSummary> listStocks(){throw new AssertionError();}
            public List<Candle> getDailyCandles(String t,LocalDate f,LocalDate end){return polluted;}
        };
        assertThat(new AnalyzeStockUseCase(futureProvider,real,new MomentumScannerStrategy(BigDecimal.ONE,new ScoreCalculator()),entry,props,clock)
                .withExperimentalEntryEnabled(true).execute("TEST3")).isEqualTo(on);
    }

    static BigDecimal bd(double d){return BigDecimal.valueOf(d);}
    static List<Candle> history(int n) {
        List<Candle> c=new ArrayList<>();
        for(int i=0;i<n;i++){double price=10+i*.01+Math.sin(i)*.02;c.add(new Candle(LocalDate.of(2026,5,1).plusDays(i),bd(price-.01),bd(price+.05),bd(price-.05),bd(price),bd(10000000)));}
        return c;
    }
    Fixture fixture() {
        List<Candle> c=history(35);LocalDate d=c.getLast().date();
        c.set(32,new Candle(d.minusDays(2),bd(10.15),bd(10.3),bd(10.01),bd(10.1),bd(10000000)));
        c.set(33,new Candle(d.minusDays(1),bd(9.98),bd(10.08),bd(9.95),bd(10.02),bd(10000000)));
        c.set(34,new Candle(d,bd(10.1),bd(10.25),bd(10),bd(10.2),bd(10000000)));
        ObjectNode current=ExperimentalComparisonCommand.JSON.valueToTree(real.analyze("TEST3","1D",c));
        ObjectNode previous=current.deepCopy(),before=current.deepCopy();
        for(ObjectNode node:List.of(current,previous,before)) {
            node.put("averageFinancialVolume20",100000000);node.put("adx14",18);node.put("adxSma5",16);
            node.put("adxRising",true);node.put("adxSma5Rising",true);node.put("plusDiAboveMinusDi",true);
            node.put("distanceFromEma21Percent",3);node.put("atr14",.5);node.put("movingAverageSlope21",1);
        }
        current.put("lastClose",10.2);current.put("rsi9",60);current.put("ema9",10.1);current.put("ema21",9.9);current.put("sma9",10.1);current.put("sma21",9.91);
        previous.put("lastClose",10.02);previous.put("rsi9",59);previous.put("ema9",10);previous.put("ema21",9.9);previous.put("sma9",10);previous.put("sma21",9.9);
        before.put("lastClose",9.95);before.put("rsi9",58);before.put("ema9",9.96);
        return new Fixture(c,current,previous,before);
    }
    class Fixture {
        final List<Candle> candles;final ObjectNode current,previous,before;
        Fixture(List<Candle> c,ObjectNode a,ObjectNode p,ObjectNode pp){candles=c;current=a;previous=p;before=pp;}
        ExperimentalEntryAnalysis evaluate() {
            TechnicalAnalysisEngine engine=new TechnicalAnalysisEngine(){
                public int minimumRequiredCandles(){return 21;}
                public TechnicalAnalysis analyze(String t,String f,List<Candle> c){return ExperimentalComparisonCommand.JSON.convertValue(c.size()==34?previous:before,TechnicalAnalysis.class);}
            };
            return analyzer.analyze(ExperimentalComparisonCommand.JSON.convertValue(current,TechnicalAnalysis.class),candles,engine,BigDecimal.ONE);
        }
    }
}
