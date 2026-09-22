package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Manual LIVE observation; logs only structure transitions, never every quote. */
public final class Mt5MarketStructureDiagnostics {
    private Mt5MarketStructureDiagnostics() {}

    public static void main(String[] args) throws Exception {
        long durationSeconds = duration(args);
        Ta4jMt5Sma9Adapter averages = new Ta4jMt5Sma9Adapter();
        averages.synchronize(new Mt5ProcessClient().readCandles());
        MarketStructureLiveObserver observer = new MarketStructureLiveObserver();
        try (var client = new Mt5TickStreamClient();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stream = executor.submit(() -> {
                client.start(tick -> observer.onTick(tick, averages.onTick(tick)));
                return null;
            });
            Thread.sleep(Duration.ofSeconds(durationSeconds));
            client.stop();
            stream.get(10, TimeUnit.SECONDS);
        }
        System.out.println("Market structure observation completed after " + durationSeconds + "s");
        for (PriceReference reference : PriceReference.values()) {
            System.out.println(reference + " snapshot=" + observer.engine().snapshot(reference).orElse(null));
            System.out.println(reference + " lastCompleted=" + observer.engine().lastCompleted(reference).orElse(null));
        }
    }

    private static long duration(String[] args) {
        long value = 30;
        for (int i = 0; i < args.length; i++) {
            if ("--duration-seconds".equals(args[i]) && i + 1 < args.length) value = Long.parseLong(args[++i]);
            else throw new IllegalArgumentException("Usage: --duration-seconds N");
        }
        if (value <= 0) throw new IllegalArgumentException("duration must be positive");
        return value;
    }
}
