package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.List;
import java.util.function.Consumer;

/** Streaming adapter over mt5_price_history.py NDJSON. The caller owns the Reader. */
public final class Mt5HistoricalPriceSource {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public record Header(String symbol, long startMsc, long endMsc,
                         List<Mt5Candle> warmup, List<Mt5Candle> official, Integer schema) {}
    public record Statistics(long eventsRead, long lastEvents) {}

    public Statistics stream(Reader input, Consumer<Header> onHeader,
                             Consumer<CanonicalPriceEvent> onEvent) throws IOException {
        return streamInternal(input, onHeader, event -> onEvent.accept(event.priceEvent()), false);
    }

    public Statistics streamHistorical(Reader input, Consumer<Header> onHeader,
                                       Consumer<HistoricalCanonicalPriceEvent> onEvent) throws IOException {
        return streamInternal(input, onHeader, onEvent, true);
    }

    private Statistics streamInternal(Reader input, Consumer<Header> onHeader,
                                      Consumer<HistoricalCanonicalPriceEvent> onEvent,
                                      boolean requireVolume) throws IOException {
        BufferedReader reader = input instanceof BufferedReader b ? b : new BufferedReader(input);
        JsonNode first = parse(reader.readLine());
        if (!"header".equals(first.path("type").asText())) throw new IOException("Missing header");
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).remove("type");
        if (!first.has("schema")) ((com.fasterxml.jackson.databind.node.ObjectNode) first).putNull("schema");
        Header header = JSON.treeToValue(first, Header.class);
        if (header.symbol() == null || header.warmup() == null || header.official() == null
                || header.startMsc() < 0 || header.endMsc() <= header.startMsc()
                || M5Bucket.start(header.startMsc()) != header.startMsc()
                || M5Bucket.start(header.endMsc()) != header.endMsc()) throw new IOException("Invalid header");
        onHeader.accept(header);
        long rows = 0, selected = 0, previous = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("History interrupted");
            JsonNode node = parse(line);
            String type = node.path("type").asText();
            if ("end".equals(type)) {
                if (integer(node, "rows") != rows || reader.readLine() != null)
                    throw new IOException("Invalid trailer or data after end");
                return new Statistics(rows, selected);
            }
            if (!"tick".equals(type)) throw new IOException("Unknown history message: " + type);
            long timestamp = integer(node, "timeMsc");
            long flags = integer(node, "flags");
            if (flags < 0 || flags > Integer.MAX_VALUE || !node.path("last").isNumber())
                throw new IOException("Invalid flags/last");
            if (timestamp < previous || timestamp < header.startMsc() || timestamp >= header.endMsc())
                throw new IOException("Unordered or out-of-range raw history");
            previous = timestamp;
            rows++;
            var event = Mt5CanonicalPriceMapper.historical(header.symbol(), timestamp,
                    node.get("last").doubleValue(), (int) flags);
            if (event.isPresent()) {
                JsonNode volume = node.get("volumeReal");
                if (requireVolume && (volume == null || !volume.isNumber() || !Double.isFinite(volume.doubleValue())
                        || volume.doubleValue() < 0)) {
                    throw new IOException("Missing or invalid volumeReal");
                }
                onEvent.accept(new HistoricalCanonicalPriceEvent(
                        event.get(), volume == null ? 0 : volume.doubleValue(), (int) flags, selected));
                selected++;
            }
        }
        throw new EOFException("Truncated history: missing end trailer");
    }

    private static JsonNode parse(String line) throws IOException {
        if (line == null || line.isBlank()) throw new EOFException("Empty history message");
        JsonNode node = JSON.readTree(line);
        if (node == null || !node.isObject()) throw new IOException("History message must be an object");
        return node;
    }

    private static long integer(JsonNode node, String field) throws IOException {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToLong())
            throw new IOException("Missing or invalid " + field);
        return node.get(field).longValue();
    }
}
