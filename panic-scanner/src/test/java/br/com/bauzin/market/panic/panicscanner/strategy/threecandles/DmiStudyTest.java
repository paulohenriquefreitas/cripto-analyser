package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.DmiStudy;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DmiStudyTest {
    static final long OPEN=Instant.parse("2026-10-30T09:00:00Z").toEpochMilli();
    static List<CandleUpdate> bars(int direction) {
        var bars=new ArrayList<CandleUpdate>();
        for(int i=0;i<40;i++){ double c=200+direction*i*2; long t=OPEN+i*300000L;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN",t,c,c+2,c-2,c),5,true,t+300000)); }
        return bars;
    }
    @Test void wilderWarmupAndMonotonicDirections() {
        for(int direction:new int[]{-1,1}) {
            var bars=bars(direction); var s=DmiStudy.analyze("WIN",bars,bars.getLast().availableAtMsc());
            assertThat(s.confirmed().get(13).plusDi()).isNull();
            assertThat(s.confirmed().get(14).plusDi()).isEqualTo(direction>0?50.0:0.0);
            assertThat(s.confirmed().get(26).adx()).isNull();
            assertThat(s.confirmed().get(27).adx()).isEqualTo(100.0);
            assertThat(s.confirmed().get(27).availableAtMsc()).isEqualTo(OPEN+28*300000L);
            assertThat(s.confirmed().getLast().regime()).isEqualTo(direction>0?"UP":"DOWN");
        }
    }
    @Test void mixedMovesAndGapsMatchIndependentWilderReference() {
        var input=new ArrayList<CandleUpdate>(); var random=new Random(42); double price=200;
        for(int i=0;i<80;i++) { price+=random.nextDouble()*20-10; long t=OPEN+i*300000L;
            input.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN",t,price,price+3,price-4,price+1),5,true,t+300000)); }
        var points=DmiStudy.analyze("WIN",input,input.getLast().availableAtMsc()).confirmed();
        double atr=0, plus=0, minus=0; var dx=new ArrayList<Double>(); double referenceAdx=0;
        for(int i=1;i<input.size();i++) {
            var c=input.get(i).candle(); var prev=input.get(i-1).candle();
            double range=Math.max(c.high(),prev.close())-Math.min(c.low(),prev.close());
            double up=c.high()-prev.high(),down=prev.low()-c.low();
            double pdm=up>Math.max(0,down)?up:0,mdm=down>Math.max(0,up)?down:0;
            if(i<=14) {atr+=range/14;plus+=pdm/14;minus+=mdm/14;}
            else {atr=(atr*13+range)/14;plus=(plus*13+pdm)/14;minus=(minus*13+mdm)/14;}
            if(i<14) continue;
            double pdi=100*plus/atr,mdi=100*minus/atr;
            assertThat(points.get(i).plusDi()).isCloseTo(pdi,within(1e-10));
            assertThat(points.get(i).minusDi()).isCloseTo(mdi,within(1e-10));
            dx.add(100*Math.abs(pdi-mdi)/(pdi+mdi));
            if(dx.size()==14) referenceAdx=dx.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            else if(dx.size()>14) referenceAdx=(referenceAdx*13+dx.getLast())/14;
            if(dx.size()>=14) assertThat(points.get(i).adx()).isCloseTo(referenceAdx,within(1e-10));
        }
    }
    @Test void classificationUsesTwentyAndHandlesEqualDiWithoutInventingDirection() {
        assertThat(DmiStudy.classify(35.0,10.0,19.99)).isEqualTo("FLAT");
        assertThat(DmiStudy.classify(35.0,10.0,20.0)).isEqualTo("UP");
        assertThat(DmiStudy.classify(10.0,35.0,20.0)).isEqualTo("DOWN");
        assertThat(DmiStudy.classify(10.0,10.0,25.0)).isEqualTo("INDEFINITE");
        assertThat(DmiStudy.classify(35.0,10.0,null)).isEqualTo("UNAVAILABLE");
    }
    @Test void flatMarketHasZeroStrength() {
        var s=DmiStudy.analyze("WIN",bars(0),OPEN+40*300000L);
        assertThat(s.confirmed().getLast().adx()).isZero();
        assertThat(s.confirmed().getLast().regime()).isEqualTo("FLAT");
    }
    @Test void futureAndPartialCannotChangeConfirmedHistory() {
        var bars=bars(1); long cutoff=bars.get(29).availableAtMsc();
        assertThat(DmiStudy.analyze("WIN",bars,cutoff)).isEqualTo(DmiStudy.analyze("WIN",bars.subList(0,30),cutoff));
        var prefix=new ArrayList<>(bars.subList(0,30)); var next=bars.get(30);
        prefix.add(new CandleUpdate(next.candle(),5,false,next.availableAtMsc()-60000));
        var partial=DmiStudy.analyze("WIN",prefix,next.availableAtMsc()-60000);
        assertThat(partial.confirmed()).isEqualTo(DmiStudy.analyze("WIN",bars,cutoff).confirmed());
        assertThat(partial.provisional().provisional()).isTrue();
        prefix.add(next); var closed=DmiStudy.analyze("WIN",prefix,next.availableAtMsc());
        assertThat(closed.provisional()).isNull();
        assertThat(closed.confirmed().getLast().adx()).isEqualTo(partial.provisional().adx());
    }
    @Test void previousSessionAndOvernightGapCannotChangeCurrentSessionValues() {
        for(int direction:new int[]{-1,1}) for(double shift:new double[]{-100,10000}) {
            var today=bars(direction);var withHistory=new ArrayList<CandleUpdate>();
            for(var b:bars(-direction)) {
                var c=b.candle();long t=c.bucketStartTimeMsc()-86400000L;
                withHistory.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN",t,c.open()+shift,c.high()+shift,c.low()+shift,c.close()+shift),5,true,t+300000));
            }
            withHistory.addAll(today);
            for(int i=0;i<today.size();i++) {
                long cutoff=today.get(i).availableAtMsc();
                assertThat(DmiStudy.analyze("WIN",withHistory,cutoff))
                    .as("session prefix %s direction %s",i,direction)
                    .isEqualTo(DmiStudy.analyze("WIN",today,cutoff));
            }
        }
    }
    @Test void firstAvailableSessionBarIsTheAnchorEvenWhenOpeningBarIsMissing() {
        var today=bars(1).subList(2,40);var input=new ArrayList<CandleUpdate>();
        long preopen=OPEN-300000;
        input.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN",preopen,10000,10010,9990,10000),5,true,OPEN));
        input.addAll(today);long cutoff=today.getLast().availableAtMsc();
        assertThat(DmiStudy.analyze("WIN",input,cutoff)).isEqualTo(DmiStudy.analyze("WIN",today,cutoff));
        assertThat(DmiStudy.analyze("WIN",input,cutoff).confirmed().getFirst().plusDi()).isNull();
    }
    @Test void openingPartialCannotBorrowYesterdayWarmupAndNextDayStartsEmpty() {
        var yesterday=bars(-1);var input=new ArrayList<CandleUpdate>();
        for(var b:yesterday) {
            var c=b.candle();long t=c.bucketStartTimeMsc()-86400000L;
            input.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN",t,c.open(),c.high(),c.low(),c.close()),5,true,t+300000));
        }
        input.add(new CandleUpdate(bars(1).getFirst().candle(),5,false,OPEN+60000));
        var first=DmiStudy.analyze("WIN",input,OPEN+60000);
        assertThat(first.confirmed()).isEmpty();assertThat(first.provisional().adx()).isNull();
        assertThat(first.provisional().regime()).isEqualTo("UNAVAILABLE");
        var nextDay=DmiStudy.analyze("WIN",yesterday,OPEN+86400000L+9*3600000L);
        assertThat(nextDay.confirmed()).isEmpty();assertThat(nextDay.provisional()).isNull();
    }

    @Test void histogramEstimatesStartAtTheOpeningWithoutAdvancingWilderConfirmation() {
        for(int direction:new int[]{-1,1}) {
            var input=bars(direction);var points=DmiStudy.analyze("WIN",input,input.getLast().availableAtMsc()).confirmed();
            var opening=points.getFirst();
            assertThat(opening.candleTimeMsc()).isEqualTo(OPEN);
            assertThat(opening.warmupPlusDi()).isZero();assertThat(opening.warmupMinusDi()).isZero();
            var second=points.get(1);
            assertThat(second.warmupPlusDi()).isEqualTo(direction>0?50.0:0.0);
            assertThat(second.warmupMinusDi()).isEqualTo(direction<0?50.0:0.0);
            assertThat(second.plusDi()).isNull();assertThat(second.adx()).isNull();
            assertThat(second.regime()).isEqualTo("UNAVAILABLE");
            assertThat(points.get(14).warmupPlusDi()).isEqualTo(points.get(14).plusDi());
            assertThat(points.get(26).adx()).isNull();
            assertThat(points.get(27).adx()).isEqualTo(100);
            assertThat(points.get(27).warmupPlusDi()).isNull();assertThat(points.get(27).warmupMinusDi()).isNull();
        }
    }

}
