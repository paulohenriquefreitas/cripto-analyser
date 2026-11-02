package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ThreeCandleStructure;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.DowStructureEngine;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.MicroDowEngine;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.DowFiftyEngine;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.OpeningStructureContext;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.MicroDowFiftyEngine;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ThreeCandleVisualAudit;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ThreeCandlePatternStudy;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ExperimentalThreeCandlePatternDetector;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.H1SessionPreparation;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.H1DetectionMode;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleTimeBasis;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.time.*;
import java.io.IOException;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ExperimentalChopStudy;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.DmiStudy;

@RestController
@RequestMapping("/api/three-candles")
public final class ThreeCandlesController {
    private final M5VisualAuditStore visualAudit = new M5VisualAuditStore();
    private final ThreeCandleHistory history;
    private final ThreeCandleLive live;
    private final Mt5Symbol symbol;
    private final ThreeCandleHistoryLoader loader;
    private final Map<String, Entry> sessions = new HashMap<>();
    private static final class PatternCache { long cutoff=Long.MIN_VALUE; ExperimentalThreeCandlePatternDetector.Snapshot snapshot; }
    private record Entry(String symbol,int minutes,ThreeCandleReplay replay,long created,List<CandleUpdate> patternSource,List<Mt5Candle> patternMinutes,PatternCache patternCache) {}
    public record ReplayView(String id,String symbol,int timeframeMinutes,String status,int speed,List<CandleUpdate> candles,ThreeCandleStructure.Snapshot structure,long availableAtMsc,DowStructureEngine.Snapshot dow, MicroDowEngine.Snapshot microDow, DowFiftyEngine.Snapshot dowFifty,ThreeCandleReplay.Mode replayMode,List<Long> missingM1Times, OpeningStructureContext.Snapshot openingStructure, MicroDowFiftyEngine.Snapshot microDowFifty, ThreeCandleVisualAudit.Snapshot patternAudit, ExperimentalThreeCandlePatternDetector.Snapshot patternStudy, ExperimentalChopStudy.Snapshot chop, DmiStudy.Snapshot dmi) {}
    public record Start(String symbol,String date,String startTime,int timeframeMinutes,CandleTimeBasis timeBasis,Integer maxLevels,H1DetectionMode h1DetectionMode,ThreeCandleReplay.Mode replayMode) {}
    public record Command(String action,Integer speed) {}
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ProblemDetail historyError(ResponseStatusException error) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(error.getStatusCode(),Objects.toString(error.getReason(),"Falha no Replay"));
    }
    @ExceptionHandler(IOException.class)
    public org.springframework.http.ProblemDetail historyError(IOException error) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,"Falha ao validar ou armazenar histórico M5: "+error.getMessage());
    }
    public ThreeCandlesController(ThreeCandleHistory history,ThreeCandleLive live,Mt5Symbol symbol) {
        this(history,live,symbol,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ThreeCandlesController(ThreeCandleHistory history,ThreeCandleLive live,Mt5Symbol symbol,ThreeCandleHistoryLoader loader) {
        this.history=history; this.live=live; this.symbol=symbol; this.loader=loader;
    }
    @PostMapping("/m5-visual-audit") public void recordVisualAudit(@RequestBody M5VisualAuditStore.Submission submission) {
        try { visualAudit.record(submission); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage()); }
    }
    @GetMapping("/m5-visual-audit") public List<M5VisualAuditStore.Replacement> visualAudit(
            @RequestParam String scope,@RequestParam String symbol,@RequestParam double tolerance,@RequestParam long availableAtMsc) {
        return visualAudit.read(scope,symbol,tolerance,availableAtMsc);
    }
    @GetMapping("/history/status") public Map<String,Object> historyStatus(@RequestParam String symbol,@RequestParam String date) {
        return Map.of("cached",loader.ready(symbol,LocalDate.parse(date)));
    }
    @PostMapping("/history/prepare") public Map<String,Object> prepareHistory(@RequestBody Start request) throws IOException {
        var bars=loader.load(request.symbol(),LocalDate.parse(request.date()),request.timeframeMinutes());
        return Map.of("candles",bars.size(),"message","Histórico recuperado. Preparando Replay...");
    }
    @GetMapping("/config") public Map<String,Object> config() throws IOException {
        return Map.of("liveSymbol",symbol.value(),"timeframes",List.of(5,15),"zone","America/Sao_Paulo","sessions",history.sessions());
    }
    @GetMapping("/live") public ThreeCandleLive.View live(@RequestParam String symbol,@RequestParam(defaultValue="5") int timeframeMinutes, @RequestParam(defaultValue="6") int maxLevels)
            throws IOException,InterruptedException { return live.snapshot(symbol,timeframeMinutes,maxLevels); }
    @PostMapping("/replays") public ReplayView create(@RequestBody Start request) throws IOException {
        LocalDate date=LocalDate.parse(request.date());
        CandleTimeBasis basis=request.timeBasis()==null ? CandleTimeBasis.MT5_WALL_CLOCK : request.timeBasis();
        long start=basis.replayStart(date,LocalTime.parse(request.startTime()));
        long opening=basis.replayStart(date,LocalTime.of(9,0));
        if(start<opening) throw new IllegalArgumentException("Replay must start at or after the B3 session opening");
        var bars=loader==null ? history.loadWithStructuralWarmup(request.symbol(),date,request.timeframeMinutes())
                : loader.load(request.symbol(),date,request.timeframeMinutes());
        var preparation=request.timeframeMinutes()==5 ? H1SessionPreparation.prepare(request.symbol(),date,opening,bars,request.h1DetectionMode()) : null;
        var patternSource=bars;
        List<Mt5Candle> patternMinutes=List.of();
        if(request.timeframeMinutes()==5 && loader!=null) {
            try { patternMinutes=loader.patternMinutes(request.symbol(),date,bars); }
            catch(IOException | ResponseStatusException unavailable) { /* Preserve M5 study; missing M1 cannot certify reaction/execution. */ }
        }
        var mode=request.replayMode()==null?ThreeCandleReplay.Mode.CLOSED_CANDLE:request.replayMode();
        List<Long> sourceGaps=List.of();
        if(mode==ThreeCandleReplay.Mode.PROGRESSIVE_M1) {
            if(request.timeframeMinutes()!=5 || loader==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Replay progressivo requer M5 e fonte M1");
            var progressive=loader.progressiveHistory(request.symbol(),date,bars);
            bars=progressive.updates();sourceGaps=progressive.missingM1Times();
        }
        var replay=new ThreeCandleReplay(request.symbol(),request.timeframeMinutes(),bars,start,preparation,mode,sourceGaps);
        synchronized(sessions) {
            sessions.entrySet().removeIf(e -> System.currentTimeMillis()-e.getValue().created()>1800000);
            if (sessions.size()>=64) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many replays");
            String id=UUID.randomUUID().toString(); var entry=new Entry(request.symbol(),request.timeframeMinutes(),replay,System.currentTimeMillis(),patternSource,patternMinutes,new PatternCache());
            sessions.put(id,entry); return view(id,entry,request.maxLevels()==null ? 6 : request.maxLevels());
        }
    }
    @GetMapping("/replays/{id}") public ReplayView get(@PathVariable String id,@RequestParam(defaultValue="6") int maxLevels) {
        synchronized(sessions) { var entry=find(id); entry.replay().advance(System.nanoTime()); return view(id,entry,maxLevels); }
    }
    @PostMapping("/replays/{id}/commands") public ReplayView command(@PathVariable String id,@RequestBody Command command,@RequestParam(defaultValue="6") int maxLevels) {
        synchronized(sessions) {
            var entry=find(id); var replay=entry.replay(); long now=System.nanoTime();
            switch(command.action()) {
                case "PLAY" -> replay.play(now);
                case "PAUSE" -> replay.pause(now);
                case "NEXT" -> replay.next();
                case "RESET" -> replay.reset();
                case "SPEED" -> replay.speed(Objects.requireNonNull(command.speed()),now);
                default -> throw new IllegalArgumentException("Invalid replay command");
            }
            return view(id,entry,maxLevels);
        }
    }
    @DeleteMapping("/replays/{id}") public void close(@PathVariable String id) { synchronized(sessions) { sessions.remove(id); } }
    private Entry find(String id) {
        var e=sessions.get(id); if(e==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Replay expired or not found"); return e;
    }
    private ReplayView view(String id,Entry e,int maxLevels) {
        var candles=e.replay().candles();var structure=e.replay().pipeline().structure(maxLevels);long at=e.replay().availableAtMsc();
        var dow=e.minutes()==5?DowStructureEngine.analyze(e.symbol(),candles,structure,at):null;
        var micro=e.minutes()==5 ? MicroDowEngine.analyze(e.symbol(),candles,structure,at) : null;
        var cache=e.patternCache();
        if(e.minutes()==5 && cache.cutoff!=at) {
            String day=structure.preparation()!=null?structure.preparation().sessionDate():LocalDate.ofEpochDay(at/86400000L).toString();
            cache.snapshot=ThreeCandlePatternStudy.analyze(e.symbol(),day,e.patternSource(),e.patternMinutes(),structure.preparation(),at);cache.cutoff=at;
        }
        return new ReplayView(id,e.symbol(),e.minutes(),e.replay().status().name(),e.replay().speed(),candles,structure,at,
            dow,
            micro,
            dow==null?null:DowFiftyEngine.analyze(e.symbol(),candles,dow,at),e.replay().mode(),e.replay().missingM1Times(),
            e.minutes()==5 ? OpeningStructureContext.analyze(e.symbol(),candles,structure,at) : null,
            micro==null?null:MicroDowFiftyEngine.analyze(micro,at),
            e.minutes()==5 ? cache.snapshot.visual() : null,e.minutes()==5 ? cache.snapshot : null,
            e.minutes()==5 ? ExperimentalChopStudy.analyze(e.symbol(),candles,at) : null,
            e.minutes()==5 ? DmiStudy.analyze(e.symbol(),candles,at) : null);
    }
}
