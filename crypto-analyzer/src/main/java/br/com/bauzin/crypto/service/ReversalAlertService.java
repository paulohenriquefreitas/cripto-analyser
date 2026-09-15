package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.analyzer.ReversalAlertAnalyzer;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.ReversalAlert;
import br.com.bauzin.crypto.model.SupportedAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReversalAlertService {

    private static final int DEFAULT_ALERT_WINDOW_SECONDS = 20;
    private static final int CLOSED_CANDLES_TO_CHECK = 20;

    private final CryptoPriceHistoryService cryptoPriceHistoryService;
    private final ReversalAlertAnalyzer reversalAlertAnalyzer;

    public List<ReversalAlert> findActiveAlerts(String interval,
                                                Integer alertWindowSeconds,
                                                String loxCookie) {
        String normalizedInterval = normalizeInterval(interval);
        int safeAlertWindowSeconds = normalizeAlertWindowSeconds(alertWindowSeconds);
        Instant now = Instant.now();
        List<ReversalAlert> alerts = new ArrayList<>();

        for (SupportedAsset asset : SupportedAsset.values()) {
            try {
                findActiveAlert(asset.name(), normalizedInterval, safeAlertWindowSeconds, now, loxCookie)
                        .ifPresent(alert -> {
                            log.info("Reversal alert detected for {} interval {} direction {} signalClose {}",
                                    alert.getSymbol(),
                                    alert.getInterval(),
                                    alert.getDirection(),
                                    alert.getSignalCandleCloseTime());
                            alerts.add(alert);
                        });
            } catch (RuntimeException ex) {
                log.warn("Could not evaluate reversal alert for symbol {} interval {}: {}",
                        asset.name(),
                        normalizedInterval,
                        ex.getMessage());
            }
        }

        return alerts;
    }

    private Optional<ReversalAlert> findActiveAlert(String symbol,
                                                   String interval,
                                                   int alertWindowSeconds,
                                                   Instant now,
                                                   String loxCookie) {
        List<Candle> closedCandles = cryptoPriceHistoryService.getCandles(
                symbol,
                interval,
                CLOSED_CANDLES_TO_CHECK,
                loxCookie);

        return reversalAlertAnalyzer.analyze(
                symbol,
                interval,
                closedCandles,
                now,
                alertWindowSeconds);
    }

    private String normalizeInterval(String interval) {
        return CandleIntervalHelper.normalize(interval).toLowerCase(Locale.ROOT);
    }

    private int normalizeAlertWindowSeconds(Integer alertWindowSeconds) {
        if (alertWindowSeconds == null || alertWindowSeconds < 1) {
            return DEFAULT_ALERT_WINDOW_SECONDS;
        }

        return alertWindowSeconds;
    }
}
