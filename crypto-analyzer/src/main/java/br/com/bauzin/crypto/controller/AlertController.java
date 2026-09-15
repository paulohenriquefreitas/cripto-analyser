package br.com.bauzin.crypto.controller;

import br.com.bauzin.crypto.model.ReversalAlert;
import br.com.bauzin.crypto.service.ReversalAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final ReversalAlertService reversalAlertService;

    @GetMapping("/reversal")
    public List<ReversalAlert> findReversalAlerts(
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "20") Integer alertWindowSeconds,
            @RequestHeader(value = "X-Lox-Cookie", required = false) String loxCookie) {

        return reversalAlertService.findActiveAlerts(interval, alertWindowSeconds, loxCookie);
    }
}
