package br.com.bauzin.market.panic.panicscanner.application.experimental;

import br.com.bauzin.market.panic.panicscanner.application.*;
import br.com.bauzin.market.panic.panicscanner.application.entry.*;
import br.com.bauzin.market.panic.panicscanner.application.usecase.AnalyzeStockUseCase;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ExperimentalEntryAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.*;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Offline by default. Universe comes exclusively from an existing study, never B3 discovery. */
public final class ExperimentalComparisonCommand {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public record Case(String ticker, LocalDate date, String partition, String evidence,
                       String currentSignal, String experimentalSignal, boolean experimentalAlert,
                       Integer leadSessions, JsonNode currentResult, ExperimentalEntryAnalysis experimental,
                       BigDecimal return3, BigDecimal return5, BigDecimal return10,
                       LocalDate date3, LocalDate date5, LocalDate date10) {}

    public static void main(String[] args) throws Exception {
        Map<String,String> opts = options(args);
        if (opts.containsKey("report-from")) {
            regenerateReport(opts);
            return;
        }
        Path study = Path.of(required(opts,"study")), cache = Path.of(required(opts,"cache"));
        Path output = Path.of(required(opts,"output"));
        LocalDate validation = LocalDate.parse(required(opts,"validation-from"));
        boolean fetch = Boolean.parseBoolean(opts.getOrDefault("fetch-missing","false"));
        String only = opts.get("tickers");
        Set<String> subset = only == null ? Set.of() : new HashSet<>(Arrays.asList(only.split(",")));
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists; choose a new directory");
        JsonNode settings = JSON.readTree(study.resolve("settings.json").toFile());
        LocalDate from = LocalDate.parse(settings.path("from").asText()), to = LocalDate.parse(settings.path("to").asText());
        if (!validation.isAfter(from) || validation.isAfter(to) || !to.isBefore(LocalDate.now(ZONE)))
            throw new IllegalArgumentException("Split must lie inside the study, whose sessions must be closed");
        var props = JSON.treeToValue(settings.get("momentum"), PanicScannerProperties.class);
        var ep = JSON.treeToValue(settings.get("entry"), PanicScannerEntryProperties.class);
        var config = new MomentumScoringConfig(props.minAverageFinancialVolume20(), props.minimumRsi(), props.maximumRsi(),
                props.minimumAdx(), props.minimumQualifiedScore(), props.maximumHealthyDistanceFromSma21Percent(), props.preferredRelativeVolume());
        var engine = new Ta4jTechnicalAnalysisAdapter();
        var strategy = new MomentumScannerStrategy(config, new ScoreCalculator());
        var entry = new DefaultEntryAnalyzer(ep.toConfig(), new EntryScoreCalculator());
        Files.createDirectories(output);
        List<Case> cases = new ArrayList<>();
        List<Map<String,String>> unavailable = new ArrayList<>();
        List<Map<String,String>> provenance = new ArrayList<>();
        int tickers = 0, complete = 0, drift = 0;
        for (JsonNode name : settings.path("tickers")) {
            String ticker = name.asText();
            if (!subset.isEmpty() && !subset.contains(ticker)) continue;
            tickers++;
            Path checkpoint = study.resolve("checkpoints").resolve(ticker + ".json");
            JsonNode saved = Files.isRegularFile(checkpoint) ? JSON.readTree(checkpoint.toFile()) : null;
            Path history = cache.resolve(ticker + ".mt5.json");
            List<Candle> candles = List.of();
            String error = "Missing raw D1 OHLCV (legacy checkpoint is not a candle archive)";
            try {
                if (!Files.isRegularFile(history) && fetch) {
                    Files.createDirectories(cache);
                    var rates = new Mt5ProcessClient().readCandles(ticker, "D1", from.minusDays(props.lookbackDays()),
                            to.plusDays(40).isBefore(LocalDate.now(ZONE)) ? to.plusDays(40) : LocalDate.now(ZONE).minusDays(1));
                    Path temp = cache.resolve(ticker + ".mt5.json.part");
                    JSON.writeValue(temp.toFile(), rates);
                    Files.move(temp, history, StandardCopyOption.ATOMIC_MOVE);
                }
                if (Files.isRegularFile(history)) candles = readHistory(history);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
            catch (Exception ex) { error = ex.getClass().getSimpleName() + ": " + ex.getMessage(); }
            if (!candles.isEmpty()) {
                complete++;
                provenance.add(Map.of("ticker",ticker,"file",history.toAbsolutePath().toString(),
                        "sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(history))),
                        "firstSession",candles.getFirst().date().toString(),"lastSession",candles.getLast().date().toString()));
                Map<String,List<Candle>> datasets = Map.of(ticker,candles);
                List<Case> tickerCases = new ArrayList<>();
                // Only true ticker sessions produce cases. Frozen signals are generated before labels.
                Map<LocalDate,ScannerResult> signals = new TreeMap<>();
                for (Candle candle : candles) {
                    LocalDate d = candle.date();
                    if (d.isBefore(from) || d.isAfter(to)) continue;
                    Clock clock = Clock.fixed(d.atTime(LocalTime.MAX).atZone(ZONE).toInstant(), ZONE);
                    MarketDataClient bounded = new MarketDataClient() {
                        public List<StockSummary> listStocks() { throw new IllegalStateException("No universe discovery"); }
                        public List<Candle> getDailyCandles(String t, LocalDate f, LocalDate end) {
                            return datasets.getOrDefault(t,List.of()).stream().filter(c -> !c.date().isBefore(f)
                                    && !c.date().isAfter(d) && !c.date().isAfter(end)).toList();
                        }
                    };
                    signals.put(d, new AnalyzeStockUseCase(bounded,engine,strategy,entry,props,clock)
                            .withExperimentalEntryEnabled(true).execute(ticker));
                }
                for (var signal : signals.entrySet()) {
                    LocalDate d = signal.getKey(); ScannerResult r = signal.getValue();
                    ExperimentalEntryAnalysis exp = r.experimentalEntry() == null
                            ? new ExperimentalEntryAnalysis("INCONCLUSIVE",List.of(),Map.of(),List.of("Insufficient closed candle history")) : r.experimentalEntry();
                    int i = index(candles,d);
                    tickerCases.add(new Case(ticker,d,partition(d,validation),"RAW_D1",r.status().name(),exp.signal(),
                            exp.signal().equals("ANALYZE_ENTRY"),null,JSON.valueToTree(r),exp,
                            forward(candles,i,3),forward(candles,i,5),forward(candles,i,10),
                            futureDate(candles,i,3),futureDate(candles,i,5),futureDate(candles,i,10)));
                    if (saved != null) for (JsonNode old : saved.path("observations")) {
                        if (old.path("signal").path("simulatedDate").asText().equals(d.toString())
                                && !old.path("signal").path("result").path("status").asText().equals(r.status().name())) drift++;
                    }
                }
                cases.addAll(withLeads(tickerCases));
            } else {
                unavailable.add(Map.of("ticker",ticker,"reason",error));
                if (saved != null) for (JsonNode observation : saved.path("observations")) {
                    JsonNode signal = observation.path("signal"), r = signal.path("result");
                    LocalDate d = LocalDate.parse(signal.path("simulatedDate").asText());
                    var exp = new ExperimentalEntryAnalysis("INCONCLUSIVE",List.of(),Map.of(),List.of(error));
                    cases.add(new Case(ticker,d,partition(d,validation),"LEGACY_SNAPSHOT",r.path("status").asText(),
                            "INCONCLUSIVE",false,null,r,exp,decimal(observation,"return3Percent"),decimal(observation,"return5Percent"),
                            decimal(observation,"return10Percent"),date(observation,"date3"),date(observation,"date5"),date(observation,"date10")));
                }
            }
            if (tickers % 25 == 0) System.out.printf("Processed %d tickers, raw datasets=%d%n", tickers,complete);
        }
        cases.sort(Comparator.comparing(Case::date).thenComparing(Case::ticker));
        ObjectNode metadata = JSON.valueToTree(Map.of(
                "version",ExperimentalEntryAnalyzer.VERSION,"sourceStudy",study.toAbsolutePath().toString(),"sourceSettings",settings,
                "validationFrom",validation,"fetchMissing",fetch,"tickerCount",tickers,"rawDatasetCount",complete,
                "legacyStatusDrift",drift,"developmentExcludesAuditTicker","MOVI3","thresholds",Map.of("D3",5,"D5",10,"D10",15)));
        metadata.put("reportSchema", ExperimentalComparisonReport.SCHEMA);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("settings.json").toFile(), metadata);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("unavailable.json").toFile(),unavailable);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("raw-provenance.json").toFile(),provenance);
        try (var writer = Files.newBufferedWriter(output.resolve("cases.ndjson"))) {
            for (Case c : cases) { writer.write(JSON.writeValueAsString(c)); writer.newLine(); }
        }
        writeCases(output.resolve("comparison.csv"),cases);
        writeCases(output.resolve("MOVI3-audit.csv"),cases.stream().filter(c -> c.ticker().equals("MOVI3")).toList());
        writeSummary(output,cases,validation,tickers,complete,drift);
        System.out.printf("COMPARISON COMPLETE cases=%d tickers=%d raw=%d unavailable=%d output=%s%n",cases.size(),tickers,complete,unavailable.size(),output.toAbsolutePath());
    }

    static List<Case> withLeads(List<Case> rows) {
        var audits = ExperimentalAnticipation.audit(rows);
        List<Case> result = new ArrayList<>();
        for (Case c : rows) {
            Integer lead = audits.get(ExperimentalAnticipation.key(c.ticker(), c.date())).leadSessions();
            result.add(new Case(c.ticker(),c.date(),c.partition(),c.evidence(),c.currentSignal(),c.experimentalSignal(),c.experimentalAlert(),lead,
                    c.currentResult(),c.experimental(),c.return3(),c.return5(),c.return10(),c.date3(),c.date5(),c.date10()));
        }
        return result;
    }

    /** Rebuild evaluation from frozen cases. This branch has no provider or price-data access. */
    static void regenerateReport(Map<String, String> opts) throws Exception {
        if (!Set.of("report-from", "output").containsAll(opts.keySet()))
            throw new IllegalArgumentException("Report-only accepts only --report-from and --output; no fetch, split or universe changes");
        Path source = Path.of(required(opts, "report-from")).toAbsolutePath();
        Path output = Path.of(required(opts, "output")).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists; source results are preserved");
        ObjectNode metadata = (ObjectNode) JSON.readTree(source.resolve("settings.json").toFile());
        LocalDate split = LocalDate.parse(metadata.path("validationFrom").asText());
        List<Case> original = new ArrayList<>();
        Path caseFile = source.resolve("cases.ndjson");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(caseFile)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        try (var reader = Files.newBufferedReader(caseFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Case c = JSON.readValue(line, Case.class);
                if (!partition(c.date(), split).equals(c.partition()))
                    throw new IllegalArgumentException("Case partition disagrees with saved validation split");
                original.add(c);
            }
        }
        List<Case> cases = withLeads(original);
        Files.createDirectories(output);
        metadata.put("reportSchema", ExperimentalComparisonReport.SCHEMA);
        metadata.put("operation", "REPORT_ONLY");
        metadata.put("reportSource", source.toString());
        metadata.put("sourceCasesSha256", HexFormat.of().formatHex(digest.digest()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("settings.json").toFile(), metadata);
        for (String name : List.of("unavailable.json", "raw-provenance.json")) {
            if (Files.isRegularFile(source.resolve(name))) Files.copy(source.resolve(name), output.resolve(name));
        }
        try (var writer = Files.newBufferedWriter(output.resolve("cases.ndjson"))) {
            for (Case c : cases) { writer.write(JSON.writeValueAsString(c)); writer.newLine(); }
        }
        writeCases(output.resolve("comparison.csv"), cases);
        writeCases(output.resolve("MOVI3-audit.csv"), cases.stream().filter(c -> c.ticker().equals("MOVI3")).toList());
        writeSummary(output, cases, split, metadata.path("tickerCount").asInt(), metadata.path("rawDatasetCount").asInt(),
                metadata.path("legacyStatusDrift").asInt());
        System.out.printf("REPORT COMPLETE cases=%d schema=%d providerCalls=0 output=%s%n", cases.size(),
                ExperimentalComparisonReport.SCHEMA, output);
    }

    static List<Candle> readHistory(Path file) throws IOException {
        JsonNode tree = JSON.readTree(file.toFile());
        if (!tree.isArray() || tree.isEmpty()) throw new IOException("Empty or invalid MT5 D1 array");
        List<Candle> candles = new ArrayList<>();
        for (JsonNode row : tree) {
            for (String field : List.of("time","open","high","low","close","realVolume"))
                if (!row.path(field).isNumber()) throw new IOException("Missing numeric field: " + field);
            LocalDate d = Instant.ofEpochSecond(row.get("time").asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            if (!d.isBefore(LocalDate.now(ZONE))) continue;
            BigDecimal o=decimal(row,"open"),h=decimal(row,"high"),l=decimal(row,"low"),c=decimal(row,"close"),v=decimal(row,"realVolume");
            if (l.signum()<=0 || h.compareTo(l)<0 || o.compareTo(l)<0 || o.compareTo(h)>0
                    || c.compareTo(l)<0 || c.compareTo(h)>0 || v.signum()<0) throw new IOException("Invalid OHLCV at " + d);
            candles.add(new Candle(d,o,h,l,c,v));
        }
        candles.sort(Comparator.comparing(Candle::date));
        for (int i=1;i<candles.size();i++) if (candles.get(i-1).date().equals(candles.get(i).date())) throw new IOException("Duplicate D1 session");
        return List.copyOf(candles);
    }
    static BigDecimal forward(List<Candle> c,int i,int h) {
        return i+h<c.size() ? c.get(i+h).close().subtract(c.get(i).close()).multiply(BigDecimal.valueOf(100))
                .divide(c.get(i).close(),8,RoundingMode.HALF_UP) : null;
    }
    private static int index(List<Candle> c,LocalDate d) { for(int i=0;i<c.size();i++) if(c.get(i).date().equals(d)) return i; throw new IllegalArgumentException(); }
    private static LocalDate futureDate(List<Candle> c,int i,int h) { return i+h<c.size()?c.get(i+h).date():null; }
    static String partition(LocalDate d,LocalDate split) { return d.isBefore(split)?"DEVELOPMENT":"VALIDATION"; }
    static BigDecimal decimal(JsonNode n,String f) { return n.path(f).isNumber()?n.path(f).decimalValue():null; }
    static LocalDate date(JsonNode n,String f) { return n.path(f).isTextual()?LocalDate.parse(n.path(f).asText()):null; }
    static String csv(Object v) { return v==null?"":"\""+v.toString().replace("\"","\"\"")+"\""; }
    static void writeCases(Path path,List<Case> cases) throws IOException {
        try (var w=Files.newBufferedWriter(path)) {
            w.write("ticker,date,partition,evidence,currentSignal,newSignal,patterns,momentumScore,entryScore,entryStatus,leadSessions,return3,return5,return10,date3,date5,date10,indicators,currentChecks,currentReasons,experimentalChecks,reasons\n");
            for(Case c:cases) {
                Object[] values={c.ticker(),c.date(),c.partition(),c.evidence(),c.currentSignal(),c.experimentalSignal(),String.join(";",c.experimental().patterns()),
                        c.currentResult().path("score"),c.currentResult().path("entryAnalysis").path("score"),c.currentResult().path("entryAnalysis").path("status"),c.leadSessions(),
                        c.return3(),c.return5(),c.return10(),c.date3(),c.date5(),c.date10(),c.currentResult().path("technicalAnalysis"),
                        c.currentResult().path("checks"),c.currentResult().path("reasons"),JSON.writeValueAsString(c.experimental().checks()),String.join(";",c.experimental().reasons())};
                w.write(Arrays.stream(values).map(ExperimentalComparisonCommand::csv).collect(java.util.stream.Collectors.joining(",")));w.newLine();
            }
        }
    }

    static boolean evaluable(Case c,int h,LocalDate split) {
        return ExperimentalComparisonReport.labelStatus(c, h, split) == ExperimentalComparisonReport.LabelStatus.AVAILABLE;
    }
    static boolean positive(Case c,int h) {
        BigDecimal value=h==3?c.return3():h==5?c.return5():c.return10();
        return value!=null && value.compareTo(BigDecimal.valueOf(h==3?5:h==5?10:15))>=0;
    }
    static boolean alert(Case c,String variant) { return c.currentSignal().equals("QUALIFIED") || variant.equals("COMBINED") && c.experimentalAlert(); }
    static void writeSummary(Path output, List<Case> cases, LocalDate split, int tickers, int complete, int drift) throws IOException {
        ExperimentalComparisonReport.write(output, cases, split, tickers, complete, drift);
    }
    static long[] movementWindows(List<Case> rows,int h,LocalDate split) {
        long total=0,current=0,combined=0;
        for(String ticker:rows.stream().map(Case::ticker).distinct().toList()) {
            var tickerRows=rows.stream().filter(c->c.ticker().equals(ticker)).sorted(Comparator.comparing(Case::date)).toList();
            LocalDate lastEnd=null;
            for(Case start:tickerRows) {
                if(!evaluable(start,h,split)||!positive(start,h)||lastEnd!=null&&!start.date().isAfter(lastEnd)) continue;
                LocalDate end=h==3?start.date3():h==5?start.date5():start.date10();
                total++;
                if(tickerRows.stream().anyMatch(c->!c.date().isBefore(start.date())&&c.date().isBefore(end)&&alert(c,"CURRENT"))) current++;
                if(tickerRows.stream().anyMatch(c->!c.date().isBefore(start.date())&&c.date().isBefore(end)&&alert(c,"COMBINED"))) combined++;
                lastEnd=end;
            }
        }
        return new long[]{total,current,combined};
    }
    private static String required(Map<String,String> o,String k) { if(!o.containsKey(k)) throw new IllegalArgumentException("Missing --"+k); return o.get(k); }
    private static Map<String,String> options(String[] args) {
        Set<String> allowed=Set.of("study","cache","output","validation-from","fetch-missing","tickers","report-from");
        Map<String,String> result=new HashMap<>();
        for(int i=0;i<args.length;i++) {
            if(!args[i].startsWith("--")) throw new IllegalArgumentException("Expected option");
            String key=args[i].substring(2);
            if(!allowed.contains(key)||++i>=args.length||result.putIfAbsent(key,args[i])!=null) throw new IllegalArgumentException("Invalid option "+key);
        }
        return result;
    }
}
