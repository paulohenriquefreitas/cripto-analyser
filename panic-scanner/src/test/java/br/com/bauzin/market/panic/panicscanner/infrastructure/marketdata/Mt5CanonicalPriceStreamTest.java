package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Mt5CanonicalPriceStreamTest {
    static final String PRICE="{\"type\":\"price\",\"symbol\":\"WINV26\",\"timeMsc\":6000000,\"price\":188700}";
    static Process fixture(String mode) throws IOException {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        return new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),Child.class.getName(),mode).start();
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            switch(args[0]) {
                case "stream" -> { System.out.println(PRICE);System.out.println(PRICE);System.out.flush();System.in.read(); }
                case "stubborn" -> { System.out.println(PRICE);System.out.flush();Thread.sleep(60_000); }
                case "invalid" -> { System.out.println("invalid");System.out.flush();System.in.read(); }
                case "order" -> { System.out.println(PRICE);System.out.println(PRICE.replace("6000000","5999999")); }
                case "error" -> { System.err.println("MT5 initialize failed fixture");System.exit(7); }
                case "inactive" -> { System.out.println("{\"type\":\"inactive\",\"stats\":{}}");System.out.println("{\"type\":\"stopped\",\"stats\":{}}"); }
                case "exit" -> { }
            }
        }
    }
    @Test void transportImmediatelyBecomesDomainEvent() throws Exception {
        var m=Mt5CanonicalPriceStream.parse(PRICE); assertEquals("WINV26",m.event().symbol());assertEquals(6_000_000,m.event().timeMsc());assertEquals(188700,m.event().price());
    }
    @ParameterizedTest @ValueSource(strings={"","null","{}","[]","{\"type\":\"quote\"}","{\"type\":\"price\"}","{\"type\":\"price\",\"symbol\":\"WINV26\",\"timeMsc\":1,\"price\":0}"})
    void invalidMessages(String line) { assertThrows(Exception.class,()->Mt5CanonicalPriceStream.parse(line)); }
    @Test void rejectsTrailingJson() { assertThrows(IOException.class,()->Mt5CanonicalPriceStream.parse(PRICE+" {}")); }
    @Test void preservesIdenticalEventsAndClosesChild() throws Exception {
        var child=fixture("stream");var source=new Mt5CanonicalPriceStream(()->child);var received=new CopyOnWriteArrayList<Mt5CanonicalPriceStream.Message>();var latch=new CountDownLatch(2);
        try(source;var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var task=executor.submit(()->{source.start(m->{received.add(m);latch.countDown();});return null;});
            try {assertTrue(latch.await(10,TimeUnit.SECONDS));} finally {source.close();}
            task.get(10,TimeUnit.SECONDS);assertFalse(child.isAlive());assertEquals(0,child.exitValue());assertEquals(2,received.size());assertEquals(received.getFirst().event(),received.getLast().event());
            assertThrows(IllegalStateException.class,()->source.start(m->{}));source.close();
        }
    }
    @Test void killsUncooperativeChildWithoutOrphan() throws Exception {
        var child=fixture("stubborn");var source=new Mt5CanonicalPriceStream(()->child);var latch=new CountDownLatch(1);
        try(source;var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var task=executor.submit(()->{source.start(m->latch.countDown());return null;});
            try {assertTrue(latch.await(10,TimeUnit.SECONDS));}finally{source.close();}
            task.get(10,TimeUnit.SECONDS);assertFalse(child.isAlive());
        }
    }
    @Test void failureContainsStderrAndExitCode() throws Exception {
        try(var source=new Mt5CanonicalPriceStream(()->fixture("error"))) {
            var ex=assertThrows(IOException.class,()->source.start(m->{}));assertTrue(ex.getMessage().contains("exit code 7"));assertTrue(ex.getMessage().contains("initialize failed"));
        }
    }
    @Test void successfulUnexpectedExitIsStillFailure() throws Exception {
        try(var source=new Mt5CanonicalPriceStream(()->fixture("exit"))) { assertThrows(IOException.class,()->source.start(m->{})); }
    }
    @Test void parseErrorStopsChild() throws Exception {
        var child=fixture("invalid");try(var source=new Mt5CanonicalPriceStream(()->child)) {
            assertThrows(IOException.class,()->source.start(m->{}));assertEquals(1,source.parseErrors());assertFalse(child.isAlive());
        }
    }
    @Test void decreasingTimeStopsChild() throws Exception {
        var child=fixture("order");try(var source=new Mt5CanonicalPriceStream(()->child)) {
            assertThrows(IOException.class,()->source.start(m->{}));assertEquals(1,source.orderErrors());assertFalse(child.isAlive());
        }
    }
    @Test void inactiveSessionFinishesNormallyAndNextSessionIsIndependent() throws Exception {
        for(int i=0;i<2;i++)try(var source=new Mt5CanonicalPriceStream(()->fixture("inactive"))) {
            var messages=new ArrayList<String>();source.start(m->messages.add(m.type()));assertEquals(List.of("inactive","stopped"),messages);assertEquals(0,source.parseErrors());
        }
    }
    @Test void consumerFailureAlsoStopsChild() throws Exception {
        var child=fixture("stream");try(var source=new Mt5CanonicalPriceStream(()->child)) {
            assertThrows(IllegalStateException.class,()->source.start(m->{throw new IllegalStateException("overflow");}));assertFalse(child.isAlive());
        }
    }
    @Test void optionsAreFiniteAndLoopbackOnly() throws Exception {
        var o=CanonicalLiveShadowDiagnostics.Options.parse(new String[]{"--duration","30m"});assertEquals(30,o.minutes());
        for(var args:List.of(new String[]{"--duration","1m"},new String[]{"--symbol","OTHER"},new String[]{"--old-url","https://example.com"},new String[]{"--probe-seconds","0"},new String[]{"--duration"}))
            assertThrows(Exception.class,()->CanonicalLiveShadowDiagnostics.Options.parse(args));
    }
}
