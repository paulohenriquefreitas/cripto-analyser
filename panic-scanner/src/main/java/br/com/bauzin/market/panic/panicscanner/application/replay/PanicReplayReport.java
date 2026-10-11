package br.com.bauzin.market.panic.panicscanner.application.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** CSV contains every scalar field; NDJSON preserves the complete original strategy output. */
public final class PanicReplayReport {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private PanicReplayReport() {}

    public static void write(Path directory, PanicHistoricalReplayRunner.Request request,
                             List<PanicHistoricalReplayRunner.Row> rows) throws IOException {
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("run.json").toFile(), request);
        List<Map<String, Object>> flat = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            flatten("", JSON.convertValue(row, Map.class), values);
            flat.add(values);
        }
        writeCsv(directory.resolve("radar.csv"), flat);
        writeCsv(directory.resolve("changes.csv"), flat.stream()
                .filter(r -> !r.get("statusChanges").toString().isEmpty()).toList());
        try (var writer = Files.newBufferedWriter(directory.resolve("radar.ndjson"), StandardCharsets.UTF_8)) {
            for (var row : rows) { writer.write(JSON.writeValueAsString(row)); writer.newLine(); }
        }
    }

    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        source.forEach((k, v) -> {
            String name = prefix.isEmpty() ? k.toString() : prefix + "." + k;
            if (v instanceof Map<?, ?> map) flatten(name, map, target);
            else if (v instanceof List<?> list) target.put(name, String.join(";", list.stream().map(Object::toString).toList()));
            else target.put(name, v == null ? "" : v);
        });
    }

    private static void writeCsv(Path path, List<Map<String, Object>> rows) throws IOException {
        Set<String> columns = new LinkedHashSet<>();
        rows.forEach(r -> columns.addAll(r.keySet()));
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", columns.stream().map(PanicReplayReport::escape).toList())); writer.newLine();
            for (var row : rows) {
                writer.write(String.join(",", columns.stream().map(c -> escape(row.getOrDefault(c, ""))).toList()));
                writer.newLine();
            }
        }
    }

    private static String escape(Object value) { return "\"" + value.toString().replace("\"", "\"\"") + "\""; }
}
