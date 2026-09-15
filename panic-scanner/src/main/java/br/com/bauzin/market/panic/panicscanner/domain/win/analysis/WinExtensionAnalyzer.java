package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

public class WinExtensionAnalyzer {

    public boolean overextendedUp(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return context.rsi9().compareTo(config.rsi().overbought()) >= 0
                && context.closeAboveEma21()
                && context.atrExtension().compareTo(config.extension().maximumEma21DistanceAtr()) >= 0
                && context.consecutiveBullishCandles() >= config.extension().consecutiveCandles();
    }

    public boolean overextendedDown(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return context.rsi9().compareTo(config.rsi().oversold()) <= 0
                && context.closeBelowEma21()
                && context.atrExtension().compareTo(config.extension().maximumEma21DistanceAtr()) >= 0
                && context.consecutiveBearishCandles() >= config.extension().consecutiveCandles();
    }

    public boolean overextendedFor(WinMarketState state, WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return switch (state) {
            case STRONG_UPTREND, UPTREND -> overextendedUp(context, config);
            case STRONG_DOWNTREND, DOWNTREND -> overextendedDown(context, config);
            default -> false;
        };
    }
}
