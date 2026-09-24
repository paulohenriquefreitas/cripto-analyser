package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.PanicScannerApplication;
import org.springframework.boot.SpringApplication;

/** Controlled session when no application/chart is running. Owns and closes only its own app.
 * Real production LIVE beans/streams remain authoritative; no replica OLD engine is created.
 */
public final class CanonicalShadowSession {
    public static void main(String[] args) throws Exception {
        var options=CanonicalLiveShadowDiagnostics.Options.parse(args);
        if(options.oldUrl().getPort()!=8080)throw new IllegalArgumentException("Managed session requires port 8080");
        // Fail if another application owns the port; never stop or replace an existing server.
        try(var application=SpringApplication.run(PanicScannerApplication.class,
                "--server.port=8080", "--canonical.shadow.capture-enabled=true")) {
            try(var chart=new CanonicalShadowChartDriver(options.oldUrl().resolve("/"))) {
                chart.start();
                new CanonicalLiveShadowDiagnostics().run(options);
            }
        }
    }
}
