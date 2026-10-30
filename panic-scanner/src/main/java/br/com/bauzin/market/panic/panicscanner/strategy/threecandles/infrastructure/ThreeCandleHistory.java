package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5HistoricalPriceSource;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.CandleAggregation;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.H1CandleAggregator;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.security.*;

/** Read-only adapter: existing validated official M5 bars, never launches an exporter. */
@Component
public final class ThreeCandleHistory {
    private final ReplayHistoryProvider history;
    private final ObjectMapper json;
    public ThreeCandleHistory(ReplayHistoryProvider history, ObjectMapper json) { this.history=history; this.json=json; }
    public record Session(String symbol, String date) {}
    public List<Session> sessions() throws IOException {
        Path root = history.resolve("WINZ26", LocalDate.of(2026,1,1)).toAbsolutePath().getParent().getParent();
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.walk(root, 2)) {
            var result = new ArrayList<Session>();
            for (Path path : files.filter(p -> p.toString().endsWith(".ndjson.metadata.json")).toList()) {
                try {
                    var m = json.readValue(path.toFile(), ReplayHistoryProvider.Metadata.class);
                    if ("COMPLETE".equals(m.status()) && m.contract().matches("WIN(?:[A-Z][0-9]{2}|\\$N)"))
                        result.add(new Session(m.contract(), m.date()));
                } catch (Exception ignored) { /* Malformed manifests are not offered as sessions. */ }
            }
            return result.stream().sorted(Comparator.comparing(Session::date).reversed().thenComparing(Session::symbol)).toList();
        }
    }
    /** Reuses validated local histories; no MT5 export or future-session warmup. */
    public List<CandleUpdate> loadWithStructuralWarmup(String symbol, LocalDate date, int timeframe) throws IOException {
        var current = load(symbol,date,timeframe);
        if (timeframe != 5 || hasWarmup(current,date)) return current;
        var merged = new TreeMap<Long,CandleUpdate>();
        // Four older sessions provide three reference sessions plus left-side pivot warmup.
        var older = sessions().stream().filter(s -> s.symbol().equals(symbol)
                && LocalDate.parse(s.date()).isBefore(date)).limit(4).toList();
        for (var bar : current) merged.put(bar.candle().bucketStartTimeMsc(),bar);
        for (var session : older) {
            for (var bar : load(symbol,LocalDate.parse(session.date()),5))
                merged.putIfAbsent(bar.candle().bucketStartTimeMsc(),bar);
            if (hasWarmup(List.copyOf(merged.values()),date)) break;
        }
        return List.copyOf(merged.values());
    }
    private boolean hasWarmup(List<CandleUpdate> bars, LocalDate date) {
        long day = date.toEpochDay();
        var counts = new TreeMap<Long,Integer>(); var hourly = new H1CandleAggregator();
        for (var bar : bars) {
            if (bar.candle().bucketStartTimeMsc()/86400000L >= day) break;
            hourly.accept(bar).ifPresent(h -> counts.merge(h.candle().bucketStartTimeMsc()/86400000L,1,Integer::sum));
        }
        if (counts.size()<4) return false;
        long earliestReference = counts.navigableKeySet().stream().skip(counts.size()-3L).findFirst().orElseThrow();
        return counts.headMap(earliestReference,false).values().stream().mapToInt(Integer::intValue).sum()>=2;
    }
    public List<CandleUpdate> load(String symbol, LocalDate date, int timeframe) throws IOException {
        if (!symbol.matches("WIN(?:[A-Z][0-9]{2}|\\$N)")) throw new IllegalArgumentException("Invalid WIN symbol");
        Path path = history.resolve(symbol,date);
        if (!Files.isRegularFile(path) || !Files.isRegularFile(path.resolveSibling(path.getFileName()+".metadata.json")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Histórico local indisponível para " + symbol + " em " + date + "; nenhuma exportação foi iniciada.");
        var metadata = history.metadata(symbol,date);
        if (!"COMPLETE".equals(metadata.status()) || !symbol.equals(metadata.contract())
                || !date.toString().equals(metadata.date()) || metadata.formatVersion()!=2
                || metadata.sha256()==null
                || Instant.parse(metadata.importedAt()).isBefore(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Histórico incompleto ou incompatível.");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path),digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            if (!HexFormat.of().formatHex(digest.digest()).equals(metadata.sha256()))
                throw new IOException("Historical SHA-256 mismatch");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Mt5HistoricalPriceSource.Header header;
        try (var reader = Files.newBufferedReader(path)) {
            var node = json.readTree(reader.readLine());
            if (!"header".equals(node.path("type").asText())) throw new IOException("Missing history header");
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("type");
            header = json.treeToValue(node,Mt5HistoricalPriceSource.Header.class);
        }
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        if (!symbol.equals(header.symbol()) || !Integer.valueOf(2).equals(header.schema())
                || header.startMsc()!=start || header.endMsc()!=start+86400000L
                || header.official()==null || header.warmup()==null) throw new IOException("Invalid history coverage");
        if (header.warmup().stream().anyMatch(c -> c.time()*1000L >= start)
                || header.official().stream().anyMatch(c -> c.time()*1000L < start))
            throw new IOException("Warmup/official bars cross the session boundary");
        var result = new ArrayList<CandleUpdate>(); long previous = -1;
        var all = new ArrayList<>(header.warmup()); all.addAll(header.official());
        for (var c : all) {
            long time = Math.multiplyExact(c.time(),1000L);
            if (time <= previous || time+300000 > header.endMsc()) throw new IOException("Unordered/out-of-range historical bars");
            previous = time;
            result.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol,time,c.open(),c.high(),c.low(),c.close()),5,true,time+300000));
        }
        if (header.official().isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sessão sem candles M5 oficiais.");
        return CandleAggregation.aggregate(result,timeframe);
    }
}
