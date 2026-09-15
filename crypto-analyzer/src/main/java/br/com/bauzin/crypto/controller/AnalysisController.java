package br.com.bauzin.crypto.controller;

import br.com.bauzin.crypto.model.SupportedAsset;
import br.com.bauzin.crypto.model.SequenceResult;
import br.com.bauzin.crypto.model.TrendAnalysisResult;
import br.com.bauzin.crypto.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/sumarry")
    public SequenceResult analyzeSequences(
            @RequestParam(defaultValue = "BTCUSDT") SupportedAsset symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "1000") Integer requestedCandles,
            @RequestHeader(value = "X-Lox-Cookie", required = false) String loxCookie) {

        return analysisService.analyzeSequences(symbol.name(), interval, requestedCandles, loxCookie);
    }

    @GetMapping("/trend")
    public TrendAnalysisResult analyzeTrend(
            @RequestParam(defaultValue = "BTCUSDT") SupportedAsset symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "1000") Integer requestedCandles,
            @RequestHeader(value = "X-Lox-Cookie", required = false) String loxCookie) {

        return analysisService.analyzeTrend(symbol.name(), interval, requestedCandles, loxCookie);
    }

}
