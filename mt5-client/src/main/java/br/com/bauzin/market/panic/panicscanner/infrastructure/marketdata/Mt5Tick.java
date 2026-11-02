package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Raw MT5 timestamps: seconds and milliseconds, without timezone adjustments. */
public record Mt5Tick(long time, @JsonProperty("time_msc") @JsonAlias("timeMsc") long timeMsc,
                      double bid, double ask, double last, double volume) {
    public Mt5Tick(long time, long timeMsc, double bid, double ask, double last) {
        this(time, timeMsc, bid, ask, last, 0);
    }
}
