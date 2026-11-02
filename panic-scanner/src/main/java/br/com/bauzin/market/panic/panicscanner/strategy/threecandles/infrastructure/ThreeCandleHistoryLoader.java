package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Finite native M5 queries. This cache never replaces canonical tick datasets. */
@Component
public final class ThreeCandleHistoryLoader {
    private final ThreeCandleHistory local;
    private final ReplayHistoryProvider paths;
    private final Mt5ProcessClient mt5;
    private final ObjectMapper json;
    public record Dataset(int version, String symbol, String seriesKind, String timeframe,
                          LocalDate from, LocalDate through, String fetchedAt, List<Mt5Candle> candles) {}
    public record Manifest(String sha256) {}

    public ThreeCandleHistoryLoader(ThreeCandleHistory local, ReplayHistoryProvider paths,
                                   Mt5ProcessClient mt5, ObjectMapper json) {
        this.local=local; this.paths=paths; this.mt5=mt5; this.json=json.copy().findAndRegisterModules();
    }
    private void validateRequest(String symbol, LocalDate date) {
        if (symbol==null || !symbol.matches("WIN(?:[A-Z][0-9]{2}|\\$N)"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ativo WIN inválido; informe o símbolo exato do MT5.");
        if (!date.isBefore(LocalDate.now(ZoneOffset.UTC)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecione um pregão histórico encerrado, anterior ao dia atual.");
    }
    private Path file(String symbol,LocalDate date) {
        return paths.resolve(symbol,date).toAbsolutePath().getParent().resolve("three-candles-m5").resolve(date+".json");
    }
    private String kind(String symbol) { return symbol.equals("WIN$N") ? "MT5_CONTINUOUS_UNMAPPED" : "EXACT_CONTRACT"; }
    private String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private List<CandleUpdate> validate(Dataset data,String symbol,LocalDate date) throws IOException {
        if (data.version()!=1 || !symbol.equals(data.symbol()) || !kind(symbol).equals(data.seriesKind())
                || !"M5".equals(data.timeframe()) || !date.equals(data.through()) || !date.minusDays(28).equals(data.from())
                || Instant.parse(data.fetchedAt()).isBefore(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
            throw new IOException("Identidade/cobertura inválida no histórico M5 de "+symbol);
        long first=data.from().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long end=date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long previous=-1; var result=new ArrayList<CandleUpdate>();
        for (var c:data.candles()) {
            long t=Math.multiplyExact(c.time(),1000L);
            if (t<first || t+300000>end || t<=previous || c.tickVolume()<0 || c.realVolume()<0)
                throw new IOException("Timestamps/volumes inválidos no histórico M5");
            try { result.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol,t,c.open(),c.high(),c.low(),c.close()),5,true,t+300000)); }
            catch (IllegalArgumentException e) { throw new IOException("OHLC ou alinhamento M5 inválido",e); }
            previous=t;
        }
        if (result.stream().noneMatch(c->c.candle().bucketStartTimeMsc()/86400000L==date.toEpochDay()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"O MT5 não retornou candles M5 para "+symbol+" em "+date+". Data sem negociação ou histórico não fornecido pelo terminal.");
        var preparation=H1SessionPreparation.prepare(symbol,date,date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli(),result);
        if (!preparation.sufficient())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Histórico M5 insuficiente no MT5 para "+symbol+" em "+date
                    +". São necessários três pregões anteriores e aquecimento H1; pregões encontrados: "+preparation.sourceSessions()
                    +". A consulta cobriu os 28 dias anteriores. Nenhum candle artificial foi criado.");
        return List.copyOf(result);
    }
    private Optional<List<CandleUpdate>> cached(String symbol,LocalDate date) {
        Path file=file(symbol,date);
        try {
            byte[] bytes=Files.readAllBytes(file);
            var manifest=json.readValue(file.resolveSibling(file.getFileName()+".metadata.json").toFile(),Manifest.class);
            if (!sha(bytes).equals(manifest.sha256())) return Optional.empty();
            return Optional.of(validate(json.readValue(bytes,Dataset.class),symbol,date));
        } catch (IOException | RuntimeException e) { return Optional.empty(); }
    }
    private Optional<List<CandleUpdate>> existing(String symbol,LocalDate date) {
        var saved=cached(symbol,date);
        if (saved.isPresent()) return saved;
        try {
            var bars=local.loadWithStructuralWarmup(symbol,date,5);
            long opening=date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
            if (H1SessionPreparation.prepare(symbol,date,opening,bars).sufficient()) return Optional.of(bars);
        } catch (IOException | RuntimeException ignored) { /* Missing/incomplete cache is queried from MT5. */ }
        return Optional.empty();
    }
    public synchronized boolean ready(String symbol,LocalDate date) {
        validateRequest(symbol,date); return existing(symbol,date).isPresent();
    }
    public synchronized List<CandleUpdate> load(String symbol,LocalDate date,int timeframe) throws IOException {
        validateRequest(symbol,date);
        if (timeframe!=5 && timeframe!=15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Período inválido");
        var saved=existing(symbol,date);
        if (saved.isPresent()) return CandleAggregation.aggregate(saved.get(),timeframe);
        List<Mt5Candle> rates;
        try { rates=mt5.readCandles(symbol,"M5",date.minusDays(28),date); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Consulta histórica MT5 interrompida",e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Não foi possível recuperar M5 de "+symbol+" no MT5. Verifique a conexão do terminal, a disponibilidade do símbolo exato e do histórico. Detalhe: "+e.getMessage(),e);
        }
        var dataset=new Dataset(1,symbol,kind(symbol),"M5",date.minusDays(28),date,Instant.now().toString(),rates);
        var bars=validate(dataset,symbol,date);
        Path file=file(symbol,date); Files.createDirectories(file.getParent());
        byte[] bytes=json.writeValueAsBytes(dataset);
        atomic(file,bytes);
        atomic(file.resolveSibling(file.getFileName()+".metadata.json"),json.writeValueAsBytes(new Manifest(sha(bytes))));
        return CandleAggregation.aggregate(bars,timeframe);
    }
    /** Native prefixes and official M5 closes, with explicit source gaps. */
    public synchronized List<CandleUpdate> progressive(String symbol,LocalDate date,List<CandleUpdate> official) throws IOException {
        return progressiveHistory(symbol,date,official).updates();
    }
    public synchronized br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ProgressiveM1History.Result progressiveHistory(
            String symbol,LocalDate date,List<CandleUpdate> official) throws IOException {
        var minutes=nativeMinutes(symbol,date);
        long opening=date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
        try { return br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ProgressiveM1History.buildDetailed(symbol,official,minutes,opening); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Replay progressivo indisponivel: "+e.getMessage(),e); }
    }
    /** Cache genuine M1 even when isolated gaps prevent construction of the progressive replay. */
    private List<Mt5Candle> nativeMinutes(String symbol,LocalDate date) throws IOException {
        validateRequest(symbol,date);
        Path target=file(symbol,date).getParent().getParent().resolve("three-candles-m1").resolve(date+".json");
        Dataset data=null;
        try {
            byte[] bytes=Files.readAllBytes(target);
            var manifest=json.readValue(target.resolveSibling(target.getFileName()+".metadata.json").toFile(),Manifest.class);
            if(sha(bytes).equals(manifest.sha256()))data=json.readValue(bytes,Dataset.class);
        } catch(IOException ignored) { /* Fetch missing/corrupted cache only. */ }
        boolean fetched=data==null;
        if(fetched) {
            try { data=new Dataset(1,symbol,kind(symbol),"M1",date,date,Instant.now().toString(),mt5.readCandles(symbol,"M1",date,date)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("Consulta M1 interrompida",e); }
        }
        if(data.version()!=1||!symbol.equals(data.symbol())||!kind(symbol).equals(data.seriesKind())
                ||!"M1".equals(data.timeframe())||!date.equals(data.from())||!date.equals(data.through())||data.candles()==null)
            throw new IOException("Identidade/cobertura M1 invalida");
        long previous=-1;
        for(var m:data.candles()) {
            long t=m.time()*1000;
            if(t%60000!=0||t<=previous||!Instant.ofEpochSecond(m.time()).atOffset(ZoneOffset.UTC).toLocalDate().equals(date))
                throw new IOException("M1 fora de ordem/alinhamento/sessao");
            try { new IntrabarCandleSnapshot(symbol,t/300000*300000,m.open(),m.high(),m.low(),m.close()); }
            catch(IllegalArgumentException invalid) { throw new IOException("OHLC M1 invalido",invalid); }
            previous=t;
        }
        if(fetched) {
            Files.createDirectories(target.getParent());byte[] bytes=json.writeValueAsBytes(data);
            atomic(target,bytes);atomic(target.resolveSibling(target.getFileName()+".metadata.json"),json.writeValueAsBytes(new Manifest(sha(bytes))));
        }
        return List.copyOf(data.candles());
    }
    private void atomic(Path target,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),"history-",".part");
        try {
            Files.write(temporary,bytes);
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Pattern analysis handles gaps locally; never require an entirely reconstructible progressive session. */
    public synchronized List<Mt5Candle> patternMinutes(String symbol,LocalDate date,List<CandleUpdate> official) throws IOException {
        return nativeMinutes(symbol,date);
    }
}
