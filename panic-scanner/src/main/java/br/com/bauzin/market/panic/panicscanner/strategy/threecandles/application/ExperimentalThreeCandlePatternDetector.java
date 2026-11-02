package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import java.time.LocalDate;
import java.util.*;
import static br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ThreeCandleVisualAudit.*;

/** Experimental observational hypotheses, NOT a faithful course replica or an order/strategy engine. */
public final class ExperimentalThreeCandlePatternDetector implements ThreeCandlePatternDetector {
    public static final String CRITERION="DOW_LEG_MINIMUM_WICK_TEST_RETURN_M1_V4";
    public record Parameters(double contactTolerancePoints,double orderOffsetPoints,double minRejectionTicks) {
        public static final double WIN_TICK_POINTS=5;
        public Parameters(double contactTolerancePoints,double orderOffsetPoints){this(contactTolerancePoints,orderOffsetPoints,0);}
        public double minimumWickPoints(){return minRejectionTicks*WIN_TICK_POINTS;}
        public Parameters { if(!Double.isFinite(contactTolerancePoints)||contactTolerancePoints<0
            || !Double.isFinite(orderOffsetPoints)||orderOffsetPoints<=0||!Double.isFinite(minRejectionTicks)||minRejectionTicks<0)throw new IllegalArgumentException("Invalid experimental parameters"); }
        public static Parameters win(){return new Parameters(0,20,6);}
    }
    public record Region(String id,String source,double low,double high,long availableAtMsc,String provenance) {
        public Region { if(id==null||source==null||provenance==null||!Double.isFinite(low)||!Double.isFinite(high)||low<=0||high<low)
            throw new IllegalArgumentException("Invalid reference region"); }
    }
    public record Minute(long startTimeMsc,double open,double high,double low,double close) {
        public Minute { if(startTimeMsc%60000!=0||!Double.isFinite(open)||!Double.isFinite(high)||!Double.isFinite(low)
            ||!Double.isFinite(close)||low<=0||low>Math.min(open,close)||high<Math.max(open,close))throw new IllegalArgumentException("Invalid native M1"); }
        public long availableAtMsc(){return startTimeMsc+60000;}
        public static Minute of(Mt5Candle c){return new Minute(c.time()*1000,c.open(),c.high(),c.low(),c.close());}
    }
    public record Event(long atMsc,String kind,String reason,Minute minute,Double comparedPrice) {}
    public record Trigger(String state,Double referencePrice,Double level,Long referenceKnownAtMsc,Long placedAtMsc,
                          Long reachedAtMsc,Minute evidence,String reason) {}
    public record WickEvidence(double points,double ticks,double minimumPoints,long availableAtMsc,CandleUpdate observation) {}
    public record Candidate(String id,String symbol,String sessionDate,Direction direction,Region region,
                            String state,List<String> negationVariants,Double negationComparedExtreme,
                            CandleUpdate candle1,CandleUpdate candle2,CandleUpdate candle3,String candle1Id,String candle2Id,String candle3Id,
                            Double testLow,Double testHigh,String testState,Trigger trigger,List<Event> events,
                            String validationStatus,ThreeCandleMarketContext.Snapshot context,Long recognizedAtMsc,WickEvidence rejectionWick) {}
    public record Rejection(long atMsc,long candleTimeMsc,String referenceId,String reason) {}
    public record Snapshot(String symbol,String sessionDate,long availableAtMsc,String criterionId,Parameters parameters,
                           String status,String m1Status,List<Candidate> candidates,List<Rejection> rejectedContacts,
                           ThreeCandleVisualAudit.Snapshot visual) {}
    private static final long M5=300000;
    private static final class Working {
        String id,state="CANDIDATO_ALVO",test="AGUARDANDO_TESTE",triggerState="NAO_POSICIONADO",triggerReason="Teste ainda nao conhecido";
        Direction direction;Region region;CandleUpdate one,two,three;List<String> variants=new ArrayList<>();Double compared;
        double testLow,testHigh;Minute tested,lastMinute,triggerEvidence;Double orderReference,orderLevel;
        Long referenceKnown,placed,reached,recognized;boolean gap,stage3;
        ThreeCandleMarketContext.Snapshot context;
        WickEvidence rejectionWick;
        final List<Event> events=new ArrayList<>();
        long start(){return one.candle().bucketStartTimeMsc();}
    }
    private final String symbol,day;
    private final Parameters parameters;
    private final ThreeCandleVisualAudit visual;
    private final LinkedHashMap<String,Working> candidates=new LinkedHashMap<>();
    private final List<Rejection> rejected=new ArrayList<>();
    private final Set<String> rejectedSeen=new HashSet<>();
    private final Map<Long,Minute> seenMinutes=new HashMap<>();
    private final Map<Long,CandleUpdate> seenPartials=new HashMap<>();
    private List<Region> references=List.of();
    private final Map<Long,ThreeCandleMarketContext.Snapshot> contexts=new HashMap<>();
    public void context(long candleTime,ThreeCandleMarketContext.Snapshot context) {
        if(context.availableAtMsc()>candleTime)throw new IllegalArgumentException("Context must be known before C1");
        var old=contexts.putIfAbsent(candleTime,context);
        if(old!=null&&!old.equals(context))throw new IllegalArgumentException("Cannot rewrite frozen market context");
    }
    private long lastClosed=-1,available=-1;

    public ExperimentalThreeCandlePatternDetector(String symbol,String day,Parameters parameters) {
        if(!symbol.startsWith("WIN"))throw new IllegalArgumentException("20-point hypothesis is WIN-only");
        this.symbol=symbol;this.day=LocalDate.parse(day).toString();this.parameters=parameters;
        visual=new ThreeCandleVisualAudit(symbol,day,CRITERION);
    }
    public void references(List<Region> known){references=List.copyOf(known);}
    private boolean touches(double low,double high,Region r){return high>=r.low()-parameters.contactTolerancePoints()
        &&low<=r.high()+parameters.contactTolerancePoints();}
    private void targets(CandleUpdate observation,long intervalStart,double open,double high,double low,Minute evidence) {
        long t=observation.candle().bucketStartTimeMsc();
        for(var r:references) {
            if(!touches(low,high,r))continue;
            if(r.availableAtMsc()>intervalStart) {
                reject(observation.availableAtMsc(),t,r.id(),"REFERENCIA_POSTERIOR_AO_INICIO_DA_OBSERVACAO; ordem intrabar nao demonstrada");continue;
            }
            Direction direction=open>r.high()?Direction.BUY:open<r.low()?Direction.SELL:null;
            if(direction==null){reject(observation.availableAtMsc(),t,r.id(),"APROXIMACAO_INDETERMINADA: abertura da observacao dentro da faixa");continue;}
            var context=contexts.getOrDefault(t,ThreeCandleMarketContext.unknown(t));
            if(!context.flow().equals(direction==Direction.BUY?"DOWN":"UP")) {
                reject(observation.availableAtMsc(),t,r.id(),"CONTEXTO_APROXIMACAO_NAO_CONFIRMADO: flow="+context.flow()+"; source="+context.source());continue;
            }
            String id=symbol+":3c:"+t+":"+direction+":"+r.id();
            if(candidates.containsKey(id))continue;
            var c=new Working();c.id=id;c.direction=direction;c.region=r;c.one=observation;c.context=context;
            event(c,observation.availableAtMsc(),"CONTATO_CANDIDATO","Contato em alvo previamente conhecido; C1 depende da negacao de C2; flow="+context.flow(),evidence,r.low());
            candidates.put(id,c);
        }
    }
    private void reject(long at,long candle,String reference,String reason){
        if(rejectedSeen.add(candle+":"+reference+":"+reason))rejected.add(new Rejection(at,candle,reference,reason));
    }
    private void event(Working c,long at,String kind,String reason,Minute minute,Double price){c.events.add(new Event(at,kind,reason,minute,price));}
    @Override public void onCandle(CandleUpdate bar) {
        if(!bar.closed()||bar.timeframeMinutes()!=5||!bar.candle().symbol().equals(symbol)
            ||bar.candle().bucketStartTimeMsc()/86400000L!=LocalDate.parse(day).toEpochDay())return;
        if(bar.candle().bucketStartTimeMsc()<=lastClosed)return;
        long t=bar.candle().bucketStartTimeMsc();
        if(seenMinutes.keySet().stream().filter(m->m/M5*M5==t).count()<5)
            targets(bar,t,bar.candle().open(),bar.candle().high(),bar.candle().low(),null);
        for(var c:candidates.values()) {
            if(t==c.start()){c.one=bar;continue;}
            if(t==c.start()+M5 && c.two==null) {
                c.two=bar;var a=c.one.candle();var b=bar.candle();boolean sell=c.direction==Direction.SELL;
                c.compared=a.close();
                if(sell?b.close()<=a.close():b.close()>=a.close())c.variants.add("FECHAMENTO_NAO_CONTINUA_C1");
                if(c.variants.isEmpty()) {
                    c.state="REJEITADO";event(c,bar.availableAtMsc(),"NEGA_REJEITADA","C2 continuou alem do fechamento de C1: C1="+a.close()+", C2="+b.close(),null,b.close());
                } else {
                    c.state="NEGA_CONFIRMADA";c.recognized=bar.availableAtMsc();
                    String reason="C2 nao continuou o fechamento de C1; igualdade aceita; cor ignorada; C1="+a.close()+", C2="+b.close();
                    event(c,bar.availableAtMsc(),"NEGA_CONFIRMADA",reason,null,b.close());
                    visual.record(c.id,c.direction,new Stage(1,Role.ALVO,c.one,(c.region.low()+c.region.high())/2,bar.availableAtMsc(),"C1 reconhecido somente com C2: "+reason));
                    visual.record(c.id,c.direction,new Stage(2,Role.NEGA,bar,b.close(),bar.availableAtMsc(),reason));
                }
            }
            if(t==c.start()+2*M5 && !c.variants.isEmpty()) {
                c.three=bar;c.testLow=bar.candle().open();c.testHigh=bar.candle().open();
                if(c.state.equals("TESTE_INVALIDADO"))continue;
                if(!c.stage3) {
                    boolean attempted=c.direction==Direction.BUY?bar.candle().low()<bar.candle().open():bar.candle().high()>bar.candle().open();
                    c.test=attempted?"INDETERMINADO":"SEM_TESTE";c.state=attempted?"INDETERMINADO":"EXPIRADO";
                    event(c,bar.availableAtMsc(),attempted?"INDETERMINADO":"TESTE_INVALIDADO",
                        attempted?"M5 nao comprova ordem tentativa/retorno; sem gatilho":"C3 encerrou sem tentar a continuacao; nenhuma entrada",null,null);
                }
                if(c.stage3&&c.rejectionWick==null&&!c.gap&&parameters.minRejectionTicks()>0) {
                    c.state="EXPIRADO";c.triggerReason="C3 encerrou sem pavio minimo observado em M1; nenhuma ordem";
                    event(c,bar.availableAtMsc(),"PAVIO_REJEICAO_INSUFICIENTE",c.triggerReason,null,rejectionWickPoints(c.direction,bar));
                }
                if(c.triggerState.equals("AGUARDANDO")){c.triggerState="EXPIRADO";c.triggerReason="Hipotese de validade limitada ao candle de teste";}
            }
            if(t>c.start()+M5 && c.two==null && c.state.equals("CANDIDATO_ALVO")) {
                c.state="INCONCLUSIVO";event(c,bar.availableAtMsc(),"INCONCLUSIVO","Candle M5 imediatamente seguinte ausente",null,null);
            }
        }
        lastClosed=t;available=Math.max(available,bar.availableAtMsc());
    }
    private void recordTest(Working c,CandleUpdate observation,Minute minute,String reason) {
        c.stage3=true;c.three=observation;c.state="TESTANDO";c.test="REGIAO_TESTADA";
        event(c,observation.availableAtMsc(),"REGIAO_TESTADA",reason,minute,null);
        visual.record(c.id,c.direction,new Stage(3,Role.TESTA,observation,c.direction==Direction.BUY?observation.candle().high():observation.candle().low(),observation.availableAtMsc(),reason));
    }
    public void onMinute(Minute minute,CandleUpdate partial) {
        if(partial.availableAtMsc()!=minute.availableAtMsc() || partial.timeframeMinutes()!=5
            || !partial.candle().symbol().equals(symbol) || minute.startTimeMsc()/M5*M5!=partial.candle().bucketStartTimeMsc())
            throw new IllegalArgumentException("M1 evidence must match the currently known M5 observation");
        if(seenMinutes.containsKey(minute.startTimeMsc())) {
            if(!seenMinutes.get(minute.startTimeMsc()).equals(minute)||!seenPartials.get(minute.startTimeMsc()).equals(partial))
                throw new IllegalArgumentException("Cannot rewrite observed M1 evidence");
            return;
        }
        if(minute.availableAtMsc()<available)throw new IllegalArgumentException("Regressing native M1");
        seenMinutes.put(minute.startTimeMsc(),minute);seenPartials.put(minute.startTimeMsc(),partial);
        long at=minute.availableAtMsc(),bucket=partial.candle().bucketStartTimeMsc();
        for(var c:candidates.values()) {
            if(bucket!=c.start()+2*M5 || c.variants.isEmpty() || c.state.equals("TESTE_INVALIDADO"))continue;
            c.three=partial;c.testLow=partial.candle().open();c.testHigh=partial.candle().open();
            boolean sell=c.direction==Direction.SELL;
            if(c.lastMinute==null ? minute.startTimeMsc()!=bucket : c.lastMinute.availableAtMsc()!=minute.startTimeMsc()) {
                c.gap=true;c.test="INDETERMINADO";c.state="INDETERMINADO";
                if(c.placed!=null && c.triggerState.equals("AGUARDANDO")){c.triggerState="INDETERMINADO";c.triggerReason="Lacuna M1 apos posicionamento; primeira passagem nao determinavel";}
                event(c,at,"INDETERMINADO","Lacuna M1 na sequencia de C3",minute,null);
            }
            c.lastMinute=minute;
            if(c.referenceKnown==null){c.orderReference=partial.candle().open();c.referenceKnown=at;}
            else if(c.orderReference!=partial.candle().open())throw new IllegalArgumentException("Cannot rewrite C3 opening reference");
            boolean reached=c.placed!=null && minute.startTimeMsc()>=c.placed && (sell?minute.low()<=c.orderLevel:minute.high()>=c.orderLevel);
            if(c.placed!=null && c.triggerState.equals("AGUARDANDO") && reached) {
                boolean gapOpen=sell?minute.open()<=c.orderLevel:minute.open()>=c.orderLevel;
                // A later interval opening before the fixed level and crossing it proves return AFTER the test.
                boolean returned=c.tested!=null && minute.startTimeMsc()>=c.tested.availableAtMsc();
                if(gapOpen||c.gap||!returned) {
                    c.triggerState="INDETERMINADO";c.triggerReason="Abertura alem do nivel, lacuna ou retorno nao demonstrado no M1: sequencia nao comprovada";
                    event(c,at,"GATILHO_INDETERMINADO",c.triggerReason,minute,c.orderLevel);
                } else {
                    c.test="REJEICAO_OBSERVADA";c.state="REJEICAO_OBSERVADA";
                    event(c,at,"REJEICAO_OBSERVADA","M1 posterior ao teste retornou alem de seu extremo e alcancou o gatilho",minute,minute.close());
                    c.triggerState="ALCANCADO_HIPOTETICO";c.reached=at;c.triggerEvidence=minute;
                    c.triggerReason="M1 iniciou antes do nivel e o alcancou apos disponibilidade da ordem; sem preco de fill, sem ordem real";
                    event(c,at,"GATILHO_HIPOTETICO_ALCANCADO",c.triggerReason,minute,c.orderLevel);
                }
            }
            if(c.tested!=null && minute.startTimeMsc()>=c.tested.availableAtMsc() && !c.gap
                && (sell?minute.close()<partial.candle().open():minute.close()>partial.candle().open())) {
                if(!c.test.equals("REJEICAO_OBSERVADA"))event(c,at,"REJEICAO_OBSERVADA","M1 posterior retornou alem da abertura de C3; nao usa cor do candle",minute,minute.close());
                c.test="REJEICAO_OBSERVADA";c.state="REJEICAO_OBSERVADA";
            }
            boolean attempt=sell?minute.high()>partial.candle().open():minute.low()<partial.candle().open();
            if(attempt && c.events.stream().noneMatch(e->e.kind().equals("TESTE_INICIADO")))
                event(c,at,"TESTE_INICIADO","Excursao de C3 em direcao a faixa conhecida de NEGA",minute,null);
            boolean touch=minute.high()>=c.testLow-parameters.contactTolerancePoints() && minute.low()<=c.testHigh+parameters.contactTolerancePoints();
            if(!c.stage3 && attempt) {
                c.tested=minute;
                recordTest(c,partial,minute,"C3 tentou continuar o fluxo anterior alem da propria abertura; retorno exige M1 posterior");
                if(c.gap){c.state="INDETERMINADO";c.test="INDETERMINADO";c.triggerState="INDETERMINADO";c.triggerReason="C3 com lacuna; nao posicionar usando extremo desconhecido";}

            } else if(!c.stage3 && touch && !attempt) {
                event(c,at,"INDETERMINADO","Abertura de C3 tocada sem tentativa de continuacao demonstrada",minute,null);
            }
            if(c.stage3&&!c.gap&&c.placed==null&&c.triggerState.equals("NAO_POSICIONADO")) {
                double wick=rejectionWickPoints(c.direction,partial);
                if(wick>=parameters.minimumWickPoints()) {
                    c.rejectionWick=new WickEvidence(wick,wick/Parameters.WIN_TICK_POINTS,parameters.minimumWickPoints(),at,partial);
                    event(c,at,"PAVIO_REJEICAO_QUALIFICADO","Pavio conhecido="+wick+" pontos; minimo="+parameters.minimumWickPoints()+" pontos ("+parameters.minRejectionTicks()+" ticks)",minute,wick);
                    c.orderLevel=hypotheticalLevel(c.direction,c.orderReference,parameters.orderOffsetPoints());
                    // A newly qualified wick cannot justify a stop entry already reached by the observed price.
                    boolean tooLate=parameters.minRejectionTicks()>0&&(sell?partial.candle().close()<=c.orderLevel:partial.candle().close()>=c.orderLevel);
                    if(tooLate){c.triggerState="INDETERMINADO";c.triggerReason="Pavio qualificou com preco ja no/alem do gatilho; nao criar entrada retroativa";
                        event(c,at,"GATILHO_INDETERMINADO",c.triggerReason,minute,c.orderLevel);
                    }else{
                        c.placed=at;c.triggerState="AGUARDANDO";
                        c.triggerReason="Teste e pavio minimo conhecidos; referencia inicial C3 congelada; alcance exige M1 posterior";
                        event(c,at,"ORDEM_HIPOTETICA_DISPONIVEL",c.triggerReason,minute,c.orderLevel);
                    }
                }else c.triggerReason="Aguardando pavio minimo: "+wick+" de "+parameters.minimumWickPoints()+" pontos";
            }
        }
        targets(partial,minute.startTimeMsc(),minute.open(),minute.high(),minute.low(),minute);
        available=Math.max(available,at);
    }
    public static double rejectionWickPoints(Direction direction,CandleUpdate observation) {
        var b=observation.candle();
        return direction==Direction.BUY?Math.min(b.open(),b.close())-b.low():b.high()-Math.max(b.open(),b.close());
    }
    public static double hypotheticalLevel(Direction direction,double reference,double offset){return direction==Direction.BUY?reference+offset:reference-offset;}
    public Snapshot snapshot(long cutoff,String m1Status) {
        if(cutoff<available)throw new IllegalArgumentException("Rebuild detector for a regressing cutoff");
        var result=new ArrayList<Candidate>();
        for(var c:candidates.values())result.add(new Candidate(c.id,symbol,day,c.direction,c.region,c.state,List.copyOf(c.variants),c.compared,
            c.one,c.two,c.three,symbol+":5:"+c.start(),c.two==null?null:symbol+":5:"+c.two.candle().bucketStartTimeMsc(),c.three==null?null:symbol+":5:"+c.three.candle().bucketStartTimeMsc(),c.three==null||c.variants.isEmpty()?null:c.testLow,c.three==null||c.variants.isEmpty()?null:c.testHigh,c.test,
            new Trigger(c.triggerState,c.orderReference,c.orderLevel,c.referenceKnown,c.placed,c.reached,c.triggerEvidence,c.triggerReason),List.copyOf(c.events),"NAO_VALIDADO",c.context,c.recognized,c.rejectionWick));
        return new Snapshot(symbol,day,cutoff,CRITERION,parameters,"HIPOTESE_OPERACIONAL_EM_VALIDACAO",m1Status,List.copyOf(result),List.copyOf(rejected),visual.snapshot(cutoff));
    }
}
