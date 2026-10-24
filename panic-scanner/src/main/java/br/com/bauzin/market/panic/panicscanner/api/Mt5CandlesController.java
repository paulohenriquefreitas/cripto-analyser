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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol;

@RestController
@RequestMapping("/api/mt5/candles")
public class Mt5CandlesController {
    private static final Logger LOG = LoggerFactory.getLogger(Mt5CandlesController.class);
    private final GetMt5CandlesUseCase useCase;

    private final Mt5TickWebSocketHandler ticks;
    private final Mt5Symbol symbol;

    public Mt5CandlesController(GetMt5CandlesUseCase useCase, Mt5TickWebSocketHandler ticks) {
        this(useCase,ticks,Mt5Symbol.defaults());
    }
    @Autowired public Mt5CandlesController(GetMt5CandlesUseCase useCase, Mt5TickWebSocketHandler ticks, Mt5Symbol symbol) {
        this.useCase = useCase;
        this.ticks = ticks;
        this.symbol = symbol;
    }

    @GetMapping
    public ResponseEntity<?> candles(@RequestParam(required = false) String symbol) {
        if (symbol != null && !this.symbol.value().equals(symbol))
            return ResponseEntity.status(409).body(Map.of("message","Contrato alterado; recarregue a sessão do gráfico."));
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
