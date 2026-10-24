package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public final class Mt5LiveConfigController {
    private final Mt5Symbol symbol;
    public Mt5LiveConfigController(Mt5Symbol symbol) { this.symbol = symbol; }
    @GetMapping("/api/mt5/config") public Map<String,String> configuration() { return Map.of("symbol",symbol.value()); }
}
