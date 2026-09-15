package br.com.bauzin.market.panic.panicscanner.application.win;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;

import java.util.List;

public interface WinMarketDataClient {

    List<WinCandle> getIntradayCandles(String contract, String timeframe);

    default WinFlowSnapshot getFlowSnapshot(String contract) {
        return WinFlowSnapshot.unavailable();
    }
}
