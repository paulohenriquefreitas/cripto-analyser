package br.com.bauzin.market.panic.panicscanner.application.win;

import br.com.bauzin.market.panic.panicscanner.domain.win.TradeLocation;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinExecutionStatus;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinSetupType;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinSignal;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzeWinUseCaseTest {

    @Test
    void shouldWaitWhenStrongDowntrendIsHeavilyOversoldAndFarFromEma21() {
        WinAnalysis analysis = analyze(bearish().rsi(18).atrExtension("2.8").bearishCandles(5).build(), microBearish());

        assertThat(analysis.marketState()).isEqualTo(WinMarketState.STRONG_DOWNTREND);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.OVEREXTENDED_DOWN);
        assertThat(analysis.tradeLocation()).isEqualTo(TradeLocation.VERY_POOR);
    }

    @Test
    void shouldDetectSellSetupOnDowntrendPullbackToEma9WithRejection() {
        WinAnalysis analysis = analyze(bearish().rsi(48).atrExtension("1.0").upperWick(52).lowerHigh(true).lowerLow(true).build(), neutralMicro());

        assertThat(analysis.marketState()).isEqualTo(WinMarketState.STRONG_DOWNTREND);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.SELL_SETUP);
        assertThat(analysis.setupType()).isEqualTo(WinSetupType.SELL_PULLBACK);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
    }

    @Test
    void shouldTriggerSellWhenOneMinuteConfirmsResumption() {
        WinAnalysis analysis = analyze(bearish().rsi(48).atrExtension("1.0").upperWick(52).lowerHigh(true).lowerLow(true).build(), microBearish());

        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.SELL_TRIGGERED);
        assertThat(analysis.signal()).isEqualTo(WinSignal.SELL);
        assertThat(analysis.tradePlan().technicalStop()).isNotNull();
    }

    @Test
    void shouldDetectDowntrendWeakeningOrReversalCandidateUp() {
        WinAnalysis analysis = analyze(bearish().slope9("0.03").rsi(40).higherLow(true).sellerExhaustionShape().build(), neutralMicro());

        assertThat(analysis.marketState()).isIn(WinMarketState.DOWNTREND_WEAKENING, WinMarketState.STRONG_DOWNTREND);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.REVERSAL_CANDIDATE_UP);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
    }

    @Test
    void shouldConfirmBullishReversalOnlyAfterStructureAndExecutionConfirm() {
        WinAnalysis analysis = analyze(bullishReversalContext(), microBullish());

        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.REVERSAL_CONFIRMED_UP);
        assertThat(analysis.signal()).isEqualTo(WinSignal.BUY);
    }

    @Test
    void shouldSupportStrongUptrendSymmetricalBuyTrigger() {
        WinAnalysis analysis = analyze(bullish().rsi(52).atrExtension("1.0").lowerWick(54).higherLow(true).higherHigh(true).build(), microBullish());

        assertThat(analysis.marketState()).isEqualTo(WinMarketState.STRONG_UPTREND);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.BUY_TRIGGERED);
        assertThat(analysis.signal()).isEqualTo(WinSignal.BUY);
    }

    @Test
    void shouldReturnNoTradeForConsolidation() {
        WinAnalysis analysis = analyze(neutral().consolidationWidth("30").atr("20").slope9("0.01").slope21("0.01").build(), neutralMicro());

        assertThat(analysis.marketState()).isEqualTo(WinMarketState.CONSOLIDATION);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.NO_TRADE);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
    }

    @Test
    void shouldClassifyBreakdownAfterConsolidationAsWaitRetest() {
        WinAnalysis analysis = analyze(bearish().breakoutDistance("8").atr("40").relativeVolume("1.4").lowerLow(true).lowerHigh(true).build(), microBearish());

        assertThat(analysis.setupType()).isEqualTo(WinSetupType.WAIT_RETEST);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.WATCH_SELL);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
    }

    @Test
    void shouldPromoteSuccessfulRetestToSellSetup() {
        WinAnalysis analysis = analyze(bearish().breakoutDistance("0").rsi(49).upperWick(55).lowerHigh(true).lowerLow(true).build(), neutralMicro());

        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.SELL_SETUP);
        assertThat(analysis.setupType()).isEqualTo(WinSetupType.SELL_PULLBACK);
    }

    @Test
    void shouldRejectFalseBreakdownAsNoTrade() {
        WinAnalysis analysis = analyze(bearish().distanceEma21("0.20").lowerWick(60).distanceSessionLow("80").atr("40").build(), microBullish());

        assertThat(analysis.setupType()).isEqualTo(WinSetupType.FALSE_BREAKOUT_DOWN);
        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.NO_TRADE);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
    }

    @Test
    void shouldWaitWhenRewardRiskIsPoor() {
        PanicScannerWinProperties strictRisk = new PanicScannerWinProperties(null, null, null, null, null, null, null,
                new PanicScannerWinProperties.Risk(new BigDecimal("10.0"), null, null, null), null, null);
        WinAnalysis analysis = analyze(strictRisk, bearish().rsi(48).atrExtension("1.0").upperWick(52).lowerHigh(true).lowerLow(true).build(), microBearish());

        assertThat(analysis.executionStatus()).isEqualTo(WinExecutionStatus.SELL_TRIGGERED);
        assertThat(analysis.signal()).isEqualTo(WinSignal.WAIT);
        assertThat(analysis.tradePlan().suggestedEntry()).isNull();
    }

    @Test
    void shouldWorkWithoutFlowDataAndNotFakeAggression() {
        WinAnalysis analysis = analyze(bearish().rsi(48).atrExtension("1.0").upperWick(52).lowerHigh(true).lowerLow(true).build(), microBearish());

        assertThat(analysis.flow().available()).isFalse();
        assertThat(analysis.flow().buyAggression()).isNull();
        assertThat(analysis.flow().sellAggression()).isNull();
        assertThat(analysis.reasons()).anyMatch(reason -> reason.contains("fluxo"));
    }

    private WinAnalysis analyze(WinTechnicalSnapshot context, WinTechnicalSnapshot execution) {
        return analyze(new PanicScannerWinProperties(null, null, null, null, null, null, null, null, null, null), context, execution);
    }

    private WinAnalysis analyze(PanicScannerWinProperties properties, WinTechnicalSnapshot context, WinTechnicalSnapshot execution) {
        AnalyzeWinUseCase useCase = new AnalyzeWinUseCase(null, new FakeTechnicalEngine(context, execution), properties);
        return useCase.analyze("WINV26", "5m", "1m", candles(), candles(), WinFlowSnapshot.unavailable());
    }

    private List<WinCandle> candles() {
        return List.of(new WinCandle(
                OffsetDateTime.parse("2026-09-08T10:00:00-03:00"),
                bd("100"), bd("110"), bd("90"), bd("100"), bd("1000")));
    }

    private WinTechnicalSnapshot microBearish() {
        return bearish().rsi(45).lowerLow(true).lowerHigh(true).relativeVolume("1.2").build();
    }

    private WinTechnicalSnapshot microBullish() {
        return bullish().rsi(55).higherHigh(true).higherLow(true).relativeVolume("1.2").build();
    }

    private WinTechnicalSnapshot neutralMicro() {
        return neutral().build();
    }

    private WinTechnicalSnapshot bullishReversalContext() {
        return bearish()
                .ema9("101").ema21("100").sma9("99").sma21("101")
                .distanceEma21("0.40").sellerExhaustionShape().rsi(54).atrExtension("1.0").slope9("0.04")
                .higherLow(true).higherHigh(true)
                .build();
    }

    private SnapshotBuilder bearish() {
        return neutral()
                .ema9("98").ema21("100").sma9("98").sma21("100")
                .slope9("-0.20").slope21("-0.12")
                .distanceEma21("-0.50")
                .lowerHigh(true)
                .lowerLow(true)
                .rsi(42);
    }

    private SnapshotBuilder bullish() {
        return neutral()
                .ema9("102").ema21("100").sma9("102").sma21("100")
                .slope9("0.20").slope21("0.12")
                .distanceEma21("0.50")
                .higherHigh(true)
                .higherLow(true)
                .rsi(58);
    }

    private SnapshotBuilder neutral() {
        return new SnapshotBuilder();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private final class SnapshotBuilder {
        private BigDecimal sma9 = bd("100");
        private BigDecimal sma21 = bd("100");
        private BigDecimal ema9 = bd("100");
        private BigDecimal ema21 = bd("100");
        private BigDecimal rsi9 = bd("50");
        private BigDecimal atr14 = bd("40");
        private BigDecimal adx14 = bd("28");
        private BigDecimal relativeVolume = bd("1.0");
        private BigDecimal distanceFromEma9Percent = bd("0.05");
        private BigDecimal distanceFromEma21Percent = bd("0.05");
        private BigDecimal distanceFromVwapPercent = bd("0.05");
        private BigDecimal distanceFromSessionLow = bd("40");
        private BigDecimal upperWickPercent = bd("10");
        private BigDecimal lowerWickPercent = bd("10");
        private int consecutiveBullishCandles = 0;
        private int consecutiveBearishCandles = 0;
        private BigDecimal slope9 = bd("0.00");
        private BigDecimal slope21 = bd("0.00");
        private BigDecimal pullbackDepth = bd("20");
        private BigDecimal consolidationWidth = bd("200");
        private BigDecimal breakoutDistance = bd("0");
        private BigDecimal atrExtension = bd("0.8");
        private boolean higherHigh;
        private boolean higherLow;
        private boolean lowerHigh;
        private boolean lowerLow;

        SnapshotBuilder sma9(String value) { this.sma9 = bd(value); return this; }
        SnapshotBuilder sma21(String value) { this.sma21 = bd(value); return this; }
        SnapshotBuilder ema9(String value) { this.ema9 = bd(value); return this; }
        SnapshotBuilder ema21(String value) { this.ema21 = bd(value); return this; }
        SnapshotBuilder rsi(int value) { this.rsi9 = BigDecimal.valueOf(value); return this; }
        SnapshotBuilder atr(String value) { this.atr14 = bd(value); return this; }
        SnapshotBuilder relativeVolume(String value) { this.relativeVolume = bd(value); return this; }
        SnapshotBuilder distanceEma21(String value) { this.distanceFromEma21Percent = bd(value); return this; }
        SnapshotBuilder distanceSessionLow(String value) { this.distanceFromSessionLow = bd(value); return this; }
        SnapshotBuilder upperWick(int value) { this.upperWickPercent = BigDecimal.valueOf(value); return this; }
        SnapshotBuilder lowerWick(int value) { this.lowerWickPercent = BigDecimal.valueOf(value); return this; }
        SnapshotBuilder bearishCandles(int value) { this.consecutiveBearishCandles = value; return this; }
        SnapshotBuilder slope9(String value) { this.slope9 = bd(value); return this; }
        SnapshotBuilder slope21(String value) { this.slope21 = bd(value); return this; }
        SnapshotBuilder consolidationWidth(String value) { this.consolidationWidth = bd(value); return this; }
        SnapshotBuilder breakoutDistance(String value) { this.breakoutDistance = bd(value); return this; }
        SnapshotBuilder atrExtension(String value) { this.atrExtension = bd(value); return this; }
        SnapshotBuilder higherHigh(boolean value) { this.higherHigh = value; return this; }
        SnapshotBuilder higherLow(boolean value) { this.higherLow = value; return this; }
        SnapshotBuilder lowerHigh(boolean value) { this.lowerHigh = value; return this; }
        SnapshotBuilder lowerLow(boolean value) { this.lowerLow = value; return this; }
        SnapshotBuilder sellerExhaustionShape() {
            this.rsi9 = bd("35");
            this.relativeVolume = bd("0.8");
            this.atrExtension = bd("2.2");
            this.lowerWickPercent = bd("42");
            this.consecutiveBearishCandles = 4;
            return this;
        }

        WinTechnicalSnapshot build() {
            return new WinTechnicalSnapshot(
                    sma9, sma21, ema9, ema21, rsi9, atr14, adx14, bd("1000"), relativeVolume,
                    bd("1000"), bd("900"), bd("990"), bd("910"), bd("950"),
                    distanceFromEma9Percent, distanceFromEma21Percent, distanceFromVwapPercent,
                    bd("40"), distanceFromSessionLow, bd("40"), upperWickPercent, lowerWickPercent,
                    consecutiveBullishCandles, consecutiveBearishCandles, slope9, slope21,
                    bd("-10"), bd("30"), pullbackDepth, consolidationWidth, breakoutDistance,
                    atrExtension, higherHigh, higherLow, lowerHigh, lowerLow);
        }
    }

    private static final class FakeTechnicalEngine implements WinTechnicalAnalysisEngine {
        private final Queue<WinTechnicalSnapshot> snapshots = new ArrayDeque<>();

        private FakeTechnicalEngine(WinTechnicalSnapshot context, WinTechnicalSnapshot execution) {
            snapshots.add(context);
            snapshots.add(execution);
        }

        @Override
        public int minimumRequiredCandles() {
            return 1;
        }

        @Override
        public WinTechnicalSnapshot analyze(String timeframe, List<WinCandle> candles) {
            return snapshots.remove();
        }
    }
}
