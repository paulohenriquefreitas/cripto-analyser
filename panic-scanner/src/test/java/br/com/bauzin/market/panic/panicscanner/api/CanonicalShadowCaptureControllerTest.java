package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalShadowCaptureControllerTest {
    static MockHttpServletRequest request(String address) {
        var r=new MockHttpServletRequest(); r.setRemoteAddr(address); return r;
    }
    @Test void loopbackSessionOpensPollsAndCloses() {
        var c=new CanonicalShadowCaptureController(new Ta4jMt5Sma9Adapter()); var r=request("127.0.0.1");
        String token=c.open(r).get("token");assertTrue(c.poll(r,token).observations().isEmpty());
        c.close(r,token);assertEquals(410,assertThrows(ResponseStatusException.class,()->c.poll(r,token)).getStatusCode().value());
    }
    @Test void remoteCannotOpenOrDrainOrCloseCapture() {
        var c=new CanonicalShadowCaptureController(new Ta4jMt5Sma9Adapter());var remote=request("192.0.2.1");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->c.open(remote)).getStatusCode().value());
        String token=c.open(request("::1")).get("token");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->c.poll(remote,token)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->c.close(remote,token)).getStatusCode().value());
    }
    @Test void secondSessionDoesNotReplaceFirstAndWrongTokenCannotDrainIt() {
        var c=new CanonicalShadowCaptureController(new Ta4jMt5Sma9Adapter());var r=request("127.0.0.1");
        var token=c.open(r).get("token");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->c.open(r)).getStatusCode().value());
        assertEquals(410,assertThrows(ResponseStatusException.class,()->c.poll(r,"wrong")).getStatusCode().value());
        assertTrue(c.poll(r,token).observations().isEmpty());
    }
}
