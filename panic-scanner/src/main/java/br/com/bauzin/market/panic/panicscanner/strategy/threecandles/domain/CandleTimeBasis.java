package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain;

import java.time.*;

/** Selects how a civil UI time maps to the preserved raw feed axis. Never rewrites candles. */
public enum CandleTimeBasis {
    MT5_WALL_CLOCK, UTC_INSTANT;

    public long replayStart(LocalDate date, LocalTime time) {
        ZoneId zone = this == MT5_WALL_CLOCK ? ZoneOffset.UTC : ZoneId.of("America/Sao_Paulo");
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli();
    }
}
