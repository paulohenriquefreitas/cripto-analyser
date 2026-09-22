package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Mt5TradeFlowDiagnostics {
    private static final Duration[] WINDOWS = {
            TradeFlowEngine.ONE_SECOND,
            TradeFlowEngine.THREE_SECONDS,
            TradeFlowEngine.FIVE_SECONDS,
            TradeFlowEngine.TEN_SECONDS
    };

    private Mt5TradeFlowDiagnostics() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        TradeFlowEngine engine = new TradeFlowEngine();
        try (var client = new Mt5TradeStreamClient();
             var scheduler = Executors.newSingleThreadScheduledExecutor(
                     Thread.ofPlatform().name("mt5-trade-flow-diagnostic").factory())) {
            Thread hook = new Thread(() -> closeQuietly(client), "mt5-trade-flow-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                System.out.println("MT5/Clear Times & Trades -> TradeFlowEngine iniciado para WINV26");
                System.out.println("Snapshots seguem o tempo dos negócios; pressione Ctrl+C para encerrar.\n");
                scheduler.scheduleAtFixedRate(() -> print(engine, client.stats()), 1, 1, TimeUnit.SECONDS);
                client.start(engine::onTrade);
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already executing the hook.
                }
            }
        }
    }

    private static void print(TradeFlowEngine engine, Mt5TradeStreamStats stats) {
        String timestamp = stats.lastTradeTimeMsc() < 0
                ? "aguardando primeiro negócio"
                : Instant.ofEpochMilli(stats.lastTradeTimeMsc()).toString();
        System.out.println("[FLOW] WINV26 @ " + timestamp);
        System.out.printf(Locale.ROOT,
                "  counters trades=%d BUY=%d SELL=%d AMBIGUOUS=%d parseErrors=%d outOfOrderErrors=%d%n",
                stats.tradesReceived(), stats.buyTrades(), stats.sellTrades(), stats.ambiguousTrades(),
                stats.parseErrors(), stats.outOfOrderErrors());
        for (Duration window : WINDOWS) {
            FlowSnapshot value = engine.snapshot(window);
            System.out.printf(Locale.ROOT,
                    "  %2ds trades=%d volume=%.0f buy=%.0f sell=%.0f ambiguous=%.0f delta=%+.0f "
                            + "buyShare=%.2f%% priceChange=%s range=%s velocity=%s%n",
                    window.toSeconds(), value.tradeCount(), value.totalVolume(), value.buyVolume(),
                    value.sellVolume(), value.ambiguousVolume(), value.knownDelta(), value.buyShare() * 100,
                    value.priceChange().isPresent() ? String.format(Locale.ROOT, "%+.1f", value.priceChange().getAsDouble()) : "n/a",
                    value.priceRange().isPresent() ? String.format(Locale.ROOT, "%.1f", value.priceRange().getAsDouble()) : "n/a",
                    value.priceVelocity().isPresent() ? String.format(Locale.ROOT, "%+.2f", value.priceVelocity().getAsDouble()) : "n/a");
        }
        System.out.println();
    }

    private static void closeQuietly(Mt5TradeStreamClient client) {
        try {
            client.stop();
        } catch (IOException ex) {
            System.err.println("Falha ao encerrar trade stream MT5: " + ex.getMessage());
        }
    }
}
