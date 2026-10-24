package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.win.profile.VolumeProfile;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VolumeProfileControllerTest {
    @Test void snapshotAndReadoutSerializeOnlyObservedVolumeAndExplicitCoverage() throws Exception {
        var service=new Mt5VolumeProfileService("09:00","18:00","",true,0,true);
        long time=LocalDate.of(2026,10,20).atTime(10,0).atZone(VolumeProfile.ZONE).toInstant().toEpochMilli();
        service.connectionStatus(true,time);
        service.onTrade(new MarketTrade("WINZ26",time,100,2,AggressorSide.SELL),new Mt5TradeStreamClient.Cursor(time,1));
        var mvc=MockMvcBuilders.standaloneSetup(new VolumeProfileController(service)).build();
        mvc.perform(get("/api/mt5/volume-profile")).andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.profile.status").value("PARTIAL"))
            .andExpect(jsonPath("$.profile.totalVolume").value(2))
            .andExpect(jsonPath("$.profile.delta").value(-2));
        mvc.perform(get("/api/mt5/volume-profile/level").param("price","105")).andExpect(status().isOk())
            .andExpect(jsonPath("$.level").doesNotExist())
            .andExpect(jsonPath("$.distanceToPoc").value(5));
        service.disconnected();
        mvc.perform(get("/api/mt5/volume-profile")).andExpect(jsonPath("$.profile.status").value("INCOMPLETE"))
            .andExpect(jsonPath("$.profile.totalVolume").value(2));
    }
}
