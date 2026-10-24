package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.win.profile.VolumeProfile;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5VolumeProfileService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mt5/volume-profile")
public final class VolumeProfileController {
    private final Mt5VolumeProfileService service;
    public VolumeProfileController(Mt5VolumeProfileService service) { this.service = service; }
    @GetMapping public Mt5VolumeProfileService.View snapshot() { return service.snapshot(); }
    @GetMapping("/level") public VolumeProfile.Readout read(@RequestParam BigDecimal price) { return service.read(price); }
}
