package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.LegacyShadowCapture;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Opt-in loopback-only diagnostic tap; does not load rates or modify LIVE state. */
@RestController
@RequestMapping("/api/mt5/shadow-capture")
@ConditionalOnProperty(name = "canonical.shadow.capture-enabled", havingValue = "true")
public class CanonicalShadowCaptureController {
    private final LegacyShadowCapture capture;
    public CanonicalShadowCaptureController(Ta4jMt5Sma9Adapter adapter) { capture = adapter.shadowCapture(); }

    @PostMapping
    public Map<String, String> open(HttpServletRequest request) {
        local(request);
        try { return Map.of("token", capture.open()); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
    }
    @GetMapping
    public LegacyShadowCapture.Batch poll(HttpServletRequest request, @RequestHeader("X-Shadow-Token") String token) {
        local(request);
        try { return capture.poll(token); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.GONE, ex.getMessage()); }
    }
    @DeleteMapping
    public void close(HttpServletRequest request, @RequestHeader("X-Shadow-Token") String token) {
        local(request);
        try { capture.close(token); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.GONE, ex.getMessage()); }
    }
    private void local(HttpServletRequest request) {
        try {
            if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return;
        } catch (java.net.UnknownHostException ignored) { }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Shadow capture requires loopback");
    }
}
