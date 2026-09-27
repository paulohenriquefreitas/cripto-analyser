package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.M5ReplaySession;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/replay/sessions")
public final class ReplaySessionController {
    private final ReplaySessionRegistry registry;
    private final ReplayHistoryProvider historyProvider;

    public ReplaySessionController(ReplaySessionRegistry registry, ReplayHistoryProvider historyProvider) {
        this.registry = registry;
        this.historyProvider = historyProvider;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest request) {
        LocalDate date = LocalDate.parse(request.date());
        M5ReplaySession session = registry.create(
                historyProvider.ensureAvailable(request.symbol(), date), request.symbol());
        return ResponseEntity.ok(Map.of(
                "replayId", session.replayId(),
                "status", session.status().name(),
                "currentTimeMsc", session.currentTimeMsc()));
    }

    public record CreateRequest(@NotBlank String symbol, @NotBlank String date) {
    }
}
