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
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.util.*;
import static br.com.bauzin.market.panic.panicscanner.application.replay.PanicOpportunityStudy.*;

public final class PanicOpportunityStudyCommand {
    public static void main(String[] args) throws Exception {
        Map<String,String> opts = options(args);
        LocalDate from = LocalDate.parse(required(opts,"from")), to = LocalDate.parse(required(opts,"to"));
        if (!to.isBefore(LocalDate.now(PanicHistoricalReplayRunner.MARKET_ZONE))) throw new IllegalArgumentException("to must be before today");
        String provider = opts.getOrDefault("historical-provider","mt5");
        if (!List.of("mt5","brapi").contains(provider)) throw new IllegalArgumentException("Unsupported historical provider");
        boolean resume = Boolean.parseBoolean(opts.getOrDefault("resume","false"));
        Path output = Path.of(required(opts,"output")).toAbsolutePath();
        if (Files.exists(output) && !resume) throw new IllegalArgumentException("Output exists; use --resume true or a new directory");
        if (resume && !Files.isRegularFile(output.resolve("settings.json"))) throw new IllegalArgumentException("Cannot resume: missing settings.json");
        Files.createDirectories(output.resolve("checkpoints"));
        try (var lockChannel=FileChannel.open(output.resolve("study.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             var lock=lockChannel.tryLock()) {
            if (lock==null) throw new IllegalStateException("Study directory is already in use");
            try (var context=new SpringApplicationBuilder(PanicReplayCommand.ReplayConfiguration.class).web(WebApplicationType.NONE)
                    .properties("logging.level.br.com.bauzin.market.panic.panicscanner.application.usecase.ScanB3MarketUseCase=WARN").run()) {
                var props=context.getBean(PanicScannerProperties.class); var ep=context.getBean(PanicScannerEntryProperties.class);
                var brapi=new BrapiMarketDataClient(RestClient.builder(),context.getBean(BrapiProperties.class),Clock.systemUTC());
                var stored=resume ? OpportunityStudyReport.JSON.readTree(output.resolve("settings.json").toFile()) : null;
                List<String> tickers;
                if (opts.containsKey("tickers")) tickers=Arrays.stream(opts.get("tickers").split(",")).map(t->t.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList();
                else if (resume) tickers=OpportunityStudyReport.JSON.convertValue(stored.get("tickers"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
                else tickers=brapi.listStocks().stream().filter(s->s.ticker()!=null && !Boolean.FALSE.equals(s.active()))
                        .map(s->s.ticker().trim().toUpperCase(Locale.ROOT)).filter(t->t.matches("[A-Z]{4}[0-9]{1,2}")).distinct().sorted().toList();
                if (tickers.isEmpty() || tickers.stream().anyMatch(t->!t.matches("[A-Z]{4}[0-9]{1,2}"))) throw new IllegalArgumentException("No valid B3 universe");
                var settings=new Settings(from,to,provider,RankingMode.valueOf(opts.getOrDefault("ranking-mode","MOMENTUM")),
                        new BigDecimal(opts.getOrDefault("return5-threshold","10")),new BigDecimal(opts.getOrDefault("return10-threshold","15")),
                        Integer.parseInt(opts.getOrDefault("future-calendar-days","40")),tickers,props,ep,1);
                if (resume) verifyResumeSettings(stored,settings);
                if (!resume) OpportunityStudyReport.checkpoint(output.resolve("settings.json"),settings);
                MarketDataClient historical=provider.equals("mt5") ? new Mt5MarketDataClient(brapi,new Mt5ProcessClient(),Clock.systemUTC()) : brapi;
                var scoring=new MomentumScoringConfig(props.minAverageFinancialVolume20(),props.minimumRsi(),props.maximumRsi(),props.minimumAdx(),
                        props.minimumQualifiedScore(),props.maximumHealthyDistanceFromSma21Percent(),props.preferredRelativeVolume());
                var study=new PanicOpportunityStudy(historical,new Ta4jTechnicalAnalysisAdapter(),new MomentumScannerStrategy(scoring,new ScoreCalculator()),
                        new DefaultEntryAnalyzer(ep.toConfig(),new EntryScoreCalculator()),props);
                execute(study,settings,output,resume);
            }
        }
    }

    static void execute(PanicOpportunityStudy study, Settings settings, Path output, boolean resume) throws Exception {
        Files.createDirectories(output.resolve("checkpoints"));
        List<TickerResult> completed=new ArrayList<>();
        OpportunityStudyReport.initialize(output);
        for (String ticker:settings.tickers()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Study interrupted");
            Path checkpoint=output.resolve("checkpoints").resolve(ticker+".json");
            TickerResult result;
            if (resume && Files.isRegularFile(checkpoint)) result=OpportunityStudyReport.JSON.readValue(checkpoint.toFile(),TickerResult.class);
            else {
                try { result=study.studyTicker(ticker,settings); }
                catch (RuntimeException exception) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Study interrupted");
                    result=new TickerResult(ticker,List.of(),exception.getClass().getSimpleName()+": "+exception.getMessage());
                }
                OpportunityStudyReport.checkpoint(checkpoint,result);
            }
            completed.add(result);
            OpportunityStudyReport.append(output,result,Map.of(),false);
            OpportunityStudyReport.summary(output,settings,completed,false);
            System.out.printf(Locale.ROOT,"[%d/%d] %s - %d sessions processed - %.1f%% %s%n",completed.size(),settings.tickers().size(),
                    ticker,result.observations().size(),100.0*completed.size()/settings.tickers().size(),result.error()==null ? "" : "UNAVAILABLE: "+result.error());
        }
        Map<String,Integer> ranks=study.ranks(completed,settings);
        Path staged=output.resolve("final-export");
        OpportunityStudyReport.initialize(staged);
        for (var result:completed) OpportunityStudyReport.append(staged,result,ranks,true);
        try (var files=Files.list(staged)) {
            for (var file:files.toList()) Files.move(file,output.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);
        }
        OpportunityStudyReport.summary(output,settings,completed,true);
        System.out.println("STUDY COMPLETE: "+output);
    }

    private static String required(Map<String,String> opts,String name) {
        if (!opts.containsKey(name)) throw new IllegalArgumentException("Missing --"+name); return opts.get(name);
    }
    static void verifyResumeSettings(com.fasterxml.jackson.databind.JsonNode stored, Settings settings) throws java.io.IOException {
        // Compare both sides as persisted JSON: in-memory BigDecimal nodes differ from parsed integer nodes.
        var persisted=OpportunityStudyReport.JSON.readTree(OpportunityStudyReport.JSON.writeValueAsString(settings));
        if (!stored.equals(persisted)) throw new IllegalArgumentException("Resume settings differ from saved execution");
    }
    static Map<String,String> options(String[] args) {
        Set<String> allowed=Set.of("from","to","tickers","ranking-mode","historical-provider","output","resume",
                "return5-threshold","return10-threshold","future-calendar-days");
        Map<String,String> options=new HashMap<>();
        for(int i=0;i<args.length;i++) {
            if(!args[i].startsWith("--")) throw new IllegalArgumentException("Expected option");
            String key=args[i].substring(2);
            if(!allowed.contains(key) || ++i>=args.length || options.putIfAbsent(key,args[i])!=null) throw new IllegalArgumentException("Invalid option: "+key);
        }
        return options;
    }
}
