package br.com.bauzin.crypto.controller;

import br.com.bauzin.crypto.model.SupportedAsset;
import br.com.bauzin.crypto.model.CryptoPriceSyncResult;
import org.springframework.web.bind.annotation.*;
import br.com.bauzin.crypto.service.StatisticsService;

@RestController
@RequestMapping("/crypto")
public class CriptoController {

    private final StatisticsService service;

    public CriptoController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/candles")
    public Object candles(
            @RequestParam(defaultValue="BTCUSDT") SupportedAsset symbol,
            @RequestParam(defaultValue="1m") String interval,
            @RequestParam(defaultValue="100") Integer requestedCandles) {

        return service.getCandles(symbol.name(), interval, requestedCandles);
    }

    @PostMapping("/sync")
    public CryptoPriceSyncResult sync(
            @RequestParam(defaultValue = "BTCUSDT") SupportedAsset symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "1000") Integer requestedCandles,
            @RequestHeader(value = "X-Lox-Cookie", required = false) String loxCookie) {

        return service.syncPrices(symbol.name(), interval, requestedCandles, loxCookie);
    }
}
