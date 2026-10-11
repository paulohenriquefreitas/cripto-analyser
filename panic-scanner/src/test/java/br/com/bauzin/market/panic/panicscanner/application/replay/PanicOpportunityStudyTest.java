package br.com.bauzin.market.panic.panicscanner.application.replay;

import br.com.bauzin.market.panic.panicscanner.application.*;
import br.com.bauzin.market.panic.panicscanner.application.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.*;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static br.com.bauzin.market.panic.panicscanner.application.replay.PanicOpportunityStudy.*;

class PanicOpportunityStudyTest {
    private final PanicScannerProperties props=new PanicScannerProperties(null,0,0,null,null,null,0,null,null,0);
    private final PanicScannerEntryProperties ep=new PanicScannerEntryProperties(0,0,0,null,null,null,0,null,null,null,null,0,null,null,null,null,null,null,0,null,null,null,null,0,null);
    private final LocalDate from=LocalDate.of(2026,8,3), to=LocalDate.of(2026,8,7);
    private int queries;
    private Settings settings(List<String> tickers) { return new Settings(from,to,"mt5",RankingMode.MOMENTUM,BigDecimal.TEN,BigDecimal.valueOf(15),40,tickers,props,ep,1); }
    private Candle candle(LocalDate d,double close) {
        var p=BigDecimal.valueOf(close);
        return new Candle(d,p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,BigDecimal.valueOf(5_000_000));
    }
    private List<Candle> history() {
        List<Candle> result=new ArrayList<>(); int i=0;
        for(var d=from.minusDays(200); !d.isAfter(to.plusDays(40)); d=d.plusDays(1)) {
            if(d.getDayOfWeek()!=DayOfWeek.SATURDAY && d.getDayOfWeek()!=DayOfWeek.SUNDAY)
                result.add(candle(d,10+(++i)*.03+Math.sin(i)*.2));
        }
        return result;
    }
    private PanicOpportunityStudy study(List<Candle> data) {
        MarketDataClient source=new MarketDataClient() {
            public List<StockSummary> listStocks() { throw new AssertionError("Explicit study must not discover stocks"); }
            public List<Candle> getDailyCandles(String ticker,LocalDate from,LocalDate to) {
                queries++; if(ticker.equals("ZZZZ3")) throw new MarketDataProviderException(ProviderErrorCode.TICKER_NOT_FOUND);
                return data;
            }
        };
        var config=new MomentumScoringConfig(props.minAverageFinancialVolume20(),props.minimumRsi(),props.maximumRsi(),props.minimumAdx(),props.minimumQualifiedScore(),props.maximumHealthyDistanceFromSma21Percent(),props.preferredRelativeVolume());
        return new PanicOpportunityStudy(source,new Ta4jTechnicalAnalysisAdapter(),new MomentumScannerStrategy(config,new ScoreCalculator()),
                new DefaultEntryAnalyzer(ep.toConfig(),new EntryScoreCalculator()),props);
    }

    @Test
    void futureLabelsChangeButEntireSignalsAndGlobalRankingDoNot() {
        var original=study(history()); var baseline=original.studyTicker("MGLU3",settings(List.of("MGLU3")));
        List<Candle> changed=history().stream().map(c->c.date().isAfter(to) ? candle(c.date(),2000) : c).toList();
        var altered=study(changed); var replay=altered.studyTicker("MGLU3",settings(List.of("MGLU3")));
        assertEquals(baseline.observations().stream().map(Observation::signal).toList(),replay.observations().stream().map(Observation::signal).toList());
        assertNotEquals(baseline.observations().getFirst().return10Percent(),replay.observations().getFirst().return10Percent());
        assertEquals(original.ranks(List.of(baseline),settings(List.of("MGLU3"))),altered.ranks(List.of(replay),settings(List.of("MGLU3"))));
        assertEquals(2,queries); // one query per ticker/study, never one query per day
    }

    private PanicHistoricalReplayRunner.Row signal(LocalDate d,boolean qualified) {
        var a=new TechnicalAnalysis("MGLU3",d,"1D",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.valueOf(9),BigDecimal.valueOf(qualified?60:80),BigDecimal.valueOf(50_000_000),Trend.UPTREND);
        var c=new TechnicalChecks(true,true,qualified,true,true,true);
        var r=new ScannerResult("MGLU3",d,"1D",BigDecimal.TEN,80,qualified?SignalStatus.QUALIFIED:SignalStatus.WATCH,Trend.UPTREND,a,c,List.of());
        return new PanicHistoricalReplayRunner.Row(d,d.atTime(23,59).atOffset(ZoneOffset.ofHours(-3)),"mt5",RankingMode.MOMENTUM,null,r,
                qualified?List.of():List.of("rsiInsideRange"),qualified?"":"rsiInsideRange",List.of());
    }
    @Test
    void labelsUseTradingSessionsAndAllFourCategoriesAreExplicit() {
        List<Candle> prices=new ArrayList<>();
        for(var d=from; prices.size()<20; d=d.plusDays(1)) {
            if(d.getDayOfWeek()!=DayOfWeek.SATURDAY && d.getDayOfWeek()!=DayOfWeek.SUNDAY) prices.add(candle(d,10+prices.size()*.4));
        }
        var rows=List.of(signal(prices.get(0).date(),true),signal(prices.get(1).date(),false));
        var evaluated=evaluate(rows,prices,settings(List.of("MGLU3")));
        assertEquals(Classification.DETECTED_EARLY,evaluated.get(0).classification());
        var lost=evaluated.get(1);
        assertEquals(Classification.LOST_AFTER_DETECTION,lost.classification());
        assertEquals(from,lost.lastQualifiedDate()); assertEquals(80,lost.lastQualifiedScore()); assertEquals(1,lost.sessionsSinceLastSignal());
        assertTrue(lost.lostExclusivelyByRsiAbove70());
        assertEquals(prices.get(5).date(),evaluated.get(0).date5());
        assertEquals(Classification.MISSED_OPPORTUNITY,evaluate(List.of(signal(from,false)),prices,settings(List.of("MGLU3"))).getFirst().classification());
        var flat=prices.stream().map(c->candle(c.date(),10)).toList();
        assertEquals(Classification.FALSE_POSITIVE,evaluate(List.of(signal(from,true)),flat,settings(List.of("MGLU3"))).getFirst().classification());
        assertEquals(Classification.INSUFFICIENT_FUTURE_DATA,evaluate(List.of(signal(from,true)),flat.subList(0,3),settings(List.of("MGLU3"))).getFirst().classification());
    }

    @Test
    void exportsProgressivelyContinuesUnavailableTickersAndResumesWithoutQueries(@TempDir Path output) throws Exception {
        var cfg=settings(List.of("MGLU3","ZZZZ3","PETR4")); var study=study(history());
        PanicOpportunityStudyCommand.execute(study,cfg,output,false);
        assertEquals(3,queries);
        for(var name:List.of("opportunities.csv","missed-opportunities.csv","detected-early.csv","lost-after-detection.csv","false-positives.csv","summary.md"))
            assertTrue(Files.size(output.resolve(name))>0,name);
        assertTrue(Files.readString(output.resolve("unavailable-tickers.csv")).contains("ZZZZ3"));
        String before=Files.readString(output.resolve("opportunities.csv"));
        PanicOpportunityStudyCommand.execute(study,cfg,output,true);
        assertEquals(3,queries); assertEquals(before,Files.readString(output.resolve("opportunities.csv")));
        assertTrue(Files.readString(output.resolve("summary.md")).contains("COMPLETE"));
    }

    @Test
    void changingThresholdChangesEvaluationOnly() {
        var low=settings(List.of("MGLU3"));
        var high=new Settings(from,to,"mt5",RankingMode.MOMENTUM,BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),40,List.of("MGLU3"),props,ep,1);
        var baseline=study(history()).studyTicker("MGLU3",low);
        var changed=study(history()).studyTicker("MGLU3",high);
        assertEquals(baseline.observations().stream().map(Observation::signal).toList(),changed.observations().stream().map(Observation::signal).toList());
    }

    @Test
    void opportunityThresholdIsInclusiveAndInsufficientFutureIsNotFalsePositive() {
        List<Candle> prices=new ArrayList<>();
        for(int i=0;i<11;i++) prices.add(candle(from.plusDays(i),i==5 ? 11 : 10));
        var rows=List.of(signal(from,true));
        var evaluated=evaluate(rows,prices,settings(List.of("MGLU3"))).getFirst();
        assertEquals(0,evaluated.return5Percent().compareTo(BigDecimal.TEN));
        assertEquals(Classification.DETECTED_EARLY,evaluated.classification());
        var high=new Settings(from,to,"mt5",RankingMode.MOMENTUM,BigDecimal.valueOf(11),BigDecimal.valueOf(15),40,List.of("MGLU3"),props,ep,1);
        assertEquals(Classification.FALSE_POSITIVE,evaluate(rows,prices,high).getFirst().classification());
        assertEquals(Classification.INSUFFICIENT_FUTURE_DATA,evaluate(rows,prices.subList(0,8),high).getFirst().classification());
    }

    @Test
    void persistedSettingsCanBeResumedButChangedThresholdsAreRejected(@TempDir Path output) throws Exception {
        var cfg=settings(List.of("MGLU3"));
        var file=output.resolve("settings.json");
        OpportunityStudyReport.checkpoint(file,cfg);
        var saved=OpportunityStudyReport.JSON.readTree(file.toFile());
        assertDoesNotThrow(()->PanicOpportunityStudyCommand.verifyResumeSettings(saved,cfg));
        var changed=new Settings(from,to,"mt5",RankingMode.MOMENTUM,BigDecimal.valueOf(11),BigDecimal.valueOf(15),40,List.of("MGLU3"),props,ep,1);
        assertThrows(IllegalArgumentException.class,()->PanicOpportunityStudyCommand.verifyResumeSettings(saved,changed));
    }
}
