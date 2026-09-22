package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Presentation only: never adjusts raw MT5 timestamps. */
public final class Mt5TimestampDiagnostics {
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss.SSS XXX", Locale.ROOT);

    private Mt5TimestampDiagnostics() {}

    static Instant toInstant(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds);
    }

    static ZonedDateTime inSaoPaulo(Instant instant) {
        return instant.atZone(SAO_PAULO);
    }

    static String describeTime(long epochSeconds) {
        Instant instant = toInstant(epochSeconds);
        return "Raw timestamp: " + epochSeconds
                + "\nInstant: " + instant
                + "\nUTC: " + FORMAT.format(instant.atZone(ZoneOffset.UTC))
                + "\nAmerica/Sao_Paulo: " + FORMAT.format(inSaoPaulo(instant));
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("MT5 timestamp diagnostics");
        var client = new Mt5ProcessClient();
        var candles = client.readCandles();
        Mt5Candle candle = candles.getLast();
        System.out.println("Candles recebidos: " + candles.size());
        System.out.println("\nUltimo candle (abertura):");
        System.out.println(describeTime(candle.time()));
        System.out.println("OHLC:\nO: " + candle.open() + "\nH: " + candle.high()
                + "\nL: " + candle.low() + "\nC: " + candle.close());

        // Separate, sequential query: this is not an atomic candle/tick snapshot.
        Mt5Tick tick = client.readTick();
        System.out.println("\nUltimo tick (consulta separada):");
        System.out.println(describeTime(tick.time()));
        System.out.println("Raw time_msc: " + tick.timeMsc());
        System.out.println("Instant time_msc: " + Instant.ofEpochMilli(tick.timeMsc()));
        System.out.println("last: " + tick.last() + "\nbid: " + tick.bid() + "\nask: " + tick.ask());
    }
}
