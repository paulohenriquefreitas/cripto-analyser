package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class Mt5MonitoringFlowServiceTest {
    @Test void healthyStreamWithoutTradesShowsNoTradesInsteadOfInventingVolume() {
        var service = new Mt5MonitoringFlowService();
        service.connectionStatus(true, System.currentTimeMillis());
        assertThat(service.snapshot().windows()).allMatch(w -> w.quality().equals("NO_TRADES")
                && w.events() == 0 && w.unknownPercent() == null && w.alert() == null);
    }
    @Test void invalidVolumeAndConnectionFailuresAreVisibleWithoutFabricatingContracts() {
        var service = new Mt5MonitoringFlowService();
        service.invalidVolume(System.currentTimeMillis());
        assertThat(service.snapshot().windows()).allMatch(w -> w.quality().equals("INVALID_VOLUME")
                && w.events() == 0 && w.buyVolume() == 0 && w.sellVolume() == 0 && w.unknownVolume() == 0);
        service.connectionStatus(false, System.currentTimeMillis());
        assertThat(service.snapshot().windows()).allMatch(w -> w.quality().equals("DISCONNECTED") && w.alert() == null);
    }
    @Test void oldWarmupTradesNeverAppearAsLiveAggression() {
        var service = new Mt5MonitoringFlowService();
        long previousDay = System.currentTimeMillis() - 86_400_000;
        service.onTrade(new br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade(
                "WINV26", previousDay, 100, 50,
                br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide.BUY));
        assertThat(service.snapshot().windows()).allMatch(w -> w.quality().equals("NO_TRADES")
                && w.events() == 0 && w.alert() == null);
    }
}
