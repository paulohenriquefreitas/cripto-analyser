package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mt5/candles")
public class Mt5CandlesController {
    private static final Logger LOG = LoggerFactory.getLogger(Mt5CandlesController.class);
    private final GetMt5CandlesUseCase useCase;

    private final Mt5TickWebSocketHandler ticks;

    public Mt5CandlesController(GetMt5CandlesUseCase useCase, Mt5TickWebSocketHandler ticks) {
        this.useCase = useCase;
        this.ticks = ticks;
    }

    @GetMapping
    public ResponseEntity<?> candles() {
        try {
            List<Mt5ChartCandle> candles = useCase.execute();
            ticks.refreshSma9();
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(candles);
        } catch (IOException ex) {
            LOG.warn("Falha ao consultar candles do MT5", ex);
            return ResponseEntity.status(502).body(Map.of("message", "Não foi possível obter os candles do MT5."));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("Consulta de candles do MT5 interrompida", ex);
            return ResponseEntity.status(503).body(Map.of("message", "Consulta ao MT5 interrompida."));
        }
    }
}
