package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.time.LocalDate;
import java.util.*;

/** Presentation/audit ledger for recognizer evidence; contains no recognition or trading rules. */
public final class ThreeCandleVisualAudit {
    public enum Direction { BUY, SELL }
    public enum Role { ALVO, NEGA, TESTA }
    public record Stage(int number, Role role, CandleUpdate observation, double referencePrice,
                        long availableAtMsc, String reason) {}
    public record Pattern(String id, Direction direction, String state, List<Stage> stages) {}
    public record Snapshot(String symbol, int timeframeMinutes, String sessionDate, long availableAtMsc,
                           String rulesStatus, String criterionId, List<Pattern> patterns) {}
    private final String symbol, sessionDate, criterionId;
    private final Map<String,Direction> directions=new LinkedHashMap<>();
    private final Map<String,List<Stage>> stages=new LinkedHashMap<>();

    public ThreeCandleVisualAudit(String symbol,String sessionDate,String criterionId) {
        this.symbol=Objects.requireNonNull(symbol);
        this.sessionDate=LocalDate.parse(sessionDate).toString();
        if(criterionId==null || criterionId.isBlank()) throw new IllegalArgumentException("Recognition criterion required before recording stages");
        this.criterionId=criterionId;
    }
    /** A recognizer must supply the real observation and reason; this ledger never infers a stage. */
    public void record(String patternId,Direction direction,Stage stage) {
        Objects.requireNonNull(direction);Objects.requireNonNull(stage);
        if(patternId==null || patternId.isBlank() || stage.number()<1 || stage.number()>3
                || stage.role()!=Role.values()[stage.number()-1]
                || stage.observation()==null || stage.observation().timeframeMinutes()!=5
                || !symbol.equals(stage.observation().candle().symbol())
                || stage.observation().candle().bucketStartTimeMsc()/86400000L!=LocalDate.parse(sessionDate).toEpochDay()
                || stage.availableAtMsc()<stage.observation().availableAtMsc()
                || !Double.isFinite(stage.referencePrice()) || stage.referencePrice()<=0
                || stage.reason()==null || stage.reason().isBlank())
            throw new IllegalArgumentException("Invalid visual stage evidence");
        if(directions.containsKey(patternId) && directions.get(patternId)!=direction)
            throw new IllegalArgumentException("Pattern direction cannot change");
        var existing=stages.getOrDefault(patternId,List.of());
        if(stage.number()<=existing.size()) {
            if(!existing.get(stage.number()-1).equals(stage)) throw new IllegalArgumentException("Recognized stages cannot repaint");
            return;
        }
        if(stage.number()!=existing.size()+1) throw new IllegalArgumentException("Stages must be recognized in order");
        if(!existing.isEmpty()) {
            var prior=existing.getLast();
            if(stage.observation().candle().bucketStartTimeMsc()<=prior.observation().candle().bucketStartTimeMsc()
                    || stage.availableAtMsc()<prior.availableAtMsc())
                throw new IllegalArgumentException("Pattern candles and recognition must be chronological");
        }
        directions.putIfAbsent(patternId,direction);
        stages.computeIfAbsent(patternId,k->new ArrayList<>()).add(stage);
    }
    public Snapshot snapshot(long cutoff) {
        var known=new ArrayList<Pattern>();
        for(var entry:stages.entrySet()) {
            var prefix=entry.getValue().stream().filter(s->s.availableAtMsc()<=cutoff).toList();
            if(prefix.isEmpty()) continue;
            String state=prefix.size()==1?"CANDLE_1":prefix.size()==2?"CANDLES_1_2":"COMPLETE";
            known.add(new Pattern(entry.getKey(),directions.get(entry.getKey()),state,prefix));
        }
        return new Snapshot(symbol,5,sessionDate,cutoff,"DEFINED",criterionId,List.copyOf(known));
    }
    public static Snapshot pending(String symbol,String sessionDate,long cutoff) {
        return new Snapshot(symbol,5,LocalDate.parse(sessionDate).toString(),cutoff,"PENDING_DEFINITION",null,List.of());
    }
}
