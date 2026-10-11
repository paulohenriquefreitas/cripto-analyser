package br.com.bauzin.market.panic.panicscanner.application.win.ml;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Independent FIRST reconstruction from original ordered LASTs. September is forbidden. */
public final class MlFirstAuditCommand {
    private record Anchor(long start, double close, double limit, String recorded) {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("DATASET LOCAL_ROOT NEW_REPORT");
        Path report = Path.of(args[2]); if (Files.exists(report)) throw new IllegalArgumentException("Report exists");
        Map<LocalDate, Map<Long, Anchor>> days = new TreeMap<>();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) {
            var header = Arrays.asList(reader.readLine().split(",", -1)); String line;
            while ((line = reader.readLine()) != null) {
                // Peek only identity columns; never parse September labels/features.
                var identity = line.split(",", 4); LocalDate date = LocalDate.parse(identity[1]);
                if (!date.isBefore(LocalDate.of(2026, 9, 1))) break;
                if (date.isBefore(LocalDate.of(2026, 4, 1))) throw new IllegalArgumentException("Unexpected date");
                var v = line.split(",", -1);
                long start = Long.parseLong(v[header.indexOf("bucket_start_msc")]);
                var anchor = new Anchor(start, Double.parseDouble(v[header.indexOf("close")]),
                        Double.parseDouble(v[header.indexOf("atr14")]), v[header.indexOf("label_first_touch")]);
                if (days.computeIfAbsent(date, ignored -> new HashMap<>()).put(start, anchor) != null)
                    throw new IllegalArgumentException("Duplicate anchor");
            }
        }
        long verified = 0;
        for (var day : days.entrySet()) {
            var actual = new HashMap<Long, String>();
            new LocalMlHistoryReader().read(Path.of(args[1]), "WIN$N", day.getKey(), h -> {}, tick -> {
                long time = tick.priceEvent().timeMsc(), bucket = time - time % 300_000;
                for (int lag = 1; lag <= 3; lag++) {
                    var anchor = day.getValue().get(bucket - lag * 300_000L);
                    if (anchor == null || actual.containsKey(anchor.start())) continue;
                    double move = tick.priceEvent().price() - anchor.close();
                    if (move >= anchor.limit()) actual.put(anchor.start(), "UP_FIRST");
                    else if (move <= -anchor.limit()) actual.put(anchor.start(), "DOWN_FIRST");
                }
            });
            for (var anchor : day.getValue().values()) {
                if (!anchor.recorded().equals(actual.getOrDefault(anchor.start(), "NONE")))
                    throw new IllegalStateException("FIRST mismatch " + day.getKey() + " " + anchor.start());
                verified++;
            }
            System.out.println(day.getKey() + " FIRST verified " + day.getValue().size());
        }
        Files.writeString(report, "PASS: independent ordered-LAST FIRST reconstruction; examples=" + verified
                + "; days=" + days.size() + "; fixed threshold=1x anchor ATR; September excluded.\n");
        System.out.println("PASS FIRST " + verified);
    }
}
