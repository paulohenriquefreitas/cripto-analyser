package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class Mt5TickStreamDiagnostics {
    public static void main(String[] args) throws IOException, InterruptedException {
        var formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
        try (var client = new Mt5TickStreamClient()) {
            Thread hook = new Thread(() -> {
                try {
                    client.stop();
                } catch (IOException ex) {
                    System.err.println("Falha ao encerrar stream MT5: " + ex.getMessage());
                    ex.printStackTrace(System.err);
                }
            }, "mt5-stream-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                System.out.println("MT5 tick stream iniciado");
                System.out.println("Pressione Ctrl+C para encerrar. Horarios exibidos em UTC.\n");
                client.start(tick -> System.out.println(formatter.format(Instant.ofEpochMilli(tick.timeMsc()))
                        + " | last=" + tick.last() + " | bid=" + tick.bid() + " | ask=" + tick.ask()));
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ex) {
                    // JVM shutdown is already executing the registered hook.
                }
            }
        }
    }
}
