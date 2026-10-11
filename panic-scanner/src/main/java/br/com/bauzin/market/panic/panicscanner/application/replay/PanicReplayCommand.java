package br.com.bauzin.market.panic.panicscanner.application.replay;

import br.com.bauzin.market.panic.panicscanner.application.*;
import br.com.bauzin.market.panic.panicscanner.application.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.*;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Standalone command: loads existing properties without starting live scanners or web services. */
public final class PanicReplayCommand {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PanicScannerProperties.class, PanicScannerEntryProperties.class, BrapiProperties.class})
    static class ReplayConfiguration {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        var request = new PanicHistoricalReplayRunner.Request(LocalDate.parse(required(options, "from")),
                LocalDate.parse(required(options, "to")),
                options.containsKey("tickers") ? List.of(options.get("tickers").split(",")) : List.of(),
                RankingMode.valueOf(options.getOrDefault("ranking-mode", "MOMENTUM").toUpperCase(Locale.ROOT)),
                options.getOrDefault("historical-provider", "mt5").toLowerCase(Locale.ROOT));
        if (!request.to().isBefore(LocalDate.now(PanicHistoricalReplayRunner.MARKET_ZONE)))
            throw new IllegalArgumentException("Replay requires completed past sessions (to must be before today)");
        Path output = Path.of(options.getOrDefault("output", "panic-scanner/target/panic-replay"));
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        try (var context = new SpringApplicationBuilder(ReplayConfiguration.class).web(WebApplicationType.NONE).run()) {
            var props = context.getBean(PanicScannerProperties.class);
            var brapi = new BrapiMarketDataClient(RestClient.builder(), context.getBean(BrapiProperties.class), Clock.systemUTC());
            MarketDataClient historical = request.historicalProvider().equals("mt5")
                    ? new Mt5MarketDataClient(brapi, new Mt5ProcessClient(), Clock.systemUTC()) : brapi;
            var config = new MomentumScoringConfig(props.minAverageFinancialVolume20(), props.minimumRsi(), props.maximumRsi(),
                    props.minimumAdx(), props.minimumQualifiedScore(), props.maximumHealthyDistanceFromSma21Percent(),
                    props.preferredRelativeVolume());
            var runner = new PanicHistoricalReplayRunner(historical, new Ta4jTechnicalAnalysisAdapter(),
                    new MomentumScannerStrategy(config, new ScoreCalculator()),
                    new DefaultEntryAnalyzer(context.getBean(PanicScannerEntryProperties.class).toConfig(), new EntryScoreCalculator()), props);
            var rows = runner.run(request);
            if (rows.isEmpty()) throw new IllegalStateException("No closed sessions found in requested interval");
            PanicReplayReport.write(output, request, rows);
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writerWithDefaultPrettyPrinter().writeValue(output.resolve("settings.json").toFile(), Map.of(
                            "momentum", props, "entry", context.getBean(PanicScannerEntryProperties.class),
                            "clock", "end-of-session America/Sao_Paulo", "futureCandles", "excluded",
                            "universe", request.tickers().isEmpty() ? "current BRAPI universe" : "explicit tickers"));
            System.out.println("PANIC_REPLAY rows=" + rows.size() + " output=" + output.toAbsolutePath());
            rows.stream().filter(r -> !r.statusChanges().isEmpty()).forEach(r ->
                    System.out.println("CHANGE " + r.simulatedDate() + " " + r.result().ticker() + " " + r.statusChanges()));
        }
    }

    private static String required(Map<String, String> options, String key) {
        if (!options.containsKey(key)) throw new IllegalArgumentException("Missing --" + key);
        return options.get(key);
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> allowed = Set.of("from", "to", "tickers", "ranking-mode", "historical-provider", "output");
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option");
            String key = args[i].substring(2);
            if (!allowed.contains(key) || ++i >= args.length || options.putIfAbsent(key, args[i]) != null)
                throw new IllegalArgumentException("Invalid or duplicate option: " + key);
        }
        return options;
    }
}
