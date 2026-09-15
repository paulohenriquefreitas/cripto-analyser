package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinSignal;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTradePlan;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class WinTradePlanCalculator {

    public WinTradePlan calculate(WinSignal signal, WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        if (signal == WinSignal.WAIT) {
            return WinTradePlan.empty();
        }
        BigDecimal entry = context.recentLow().add(context.recentHigh()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal stopBuffer = context.atr14().multiply(config.risk().atrStopBuffer());
        if (signal == WinSignal.BUY) {
            BigDecimal stop = context.recentLow().subtract(stopBuffer).setScale(4, RoundingMode.HALF_UP);
            BigDecimal risk = entry.subtract(stop).abs().setScale(4, RoundingMode.HALF_UP);
            return plan(entry, stop, risk, entry.add(context.atr14().multiply(config.risk().target1Atr())),
                    entry.add(context.atr14().multiply(config.risk().target2Atr())));
        }
        BigDecimal stop = context.recentHigh().add(stopBuffer).setScale(4, RoundingMode.HALF_UP);
        BigDecimal risk = stop.subtract(entry).abs().setScale(4, RoundingMode.HALF_UP);
        return plan(entry, stop, risk, entry.subtract(context.atr14().multiply(config.risk().target1Atr())),
                entry.subtract(context.atr14().multiply(config.risk().target2Atr())));
    }

    public boolean rewardRiskAccepted(WinTradePlan plan, PanicScannerWinProperties config) {
        return plan.valid()
                && plan.rewardRiskTarget1() != null
                && plan.rewardRiskTarget1().compareTo(config.risk().minimumRewardRisk()) >= 0;
    }

    private WinTradePlan plan(BigDecimal entry, BigDecimal stop, BigDecimal risk, BigDecimal target1, BigDecimal target2) {
        if (risk.signum() == 0) {
            return WinTradePlan.empty();
        }
        return new WinTradePlan(
                entry.setScale(4, RoundingMode.HALF_UP),
                stop.setScale(4, RoundingMode.HALF_UP),
                risk,
                target1.setScale(4, RoundingMode.HALF_UP),
                target2.setScale(4, RoundingMode.HALF_UP),
                target1.subtract(entry).abs().divide(risk, 4, RoundingMode.HALF_UP),
                target2.subtract(entry).abs().divide(risk, 4, RoundingMode.HALF_UP));
    }
}
