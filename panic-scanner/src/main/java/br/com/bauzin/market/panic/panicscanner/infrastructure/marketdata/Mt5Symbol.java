package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable for one application lifetime. Changing the live contract requires restart. */
@Component
public record Mt5Symbol(@Value("${mt5.symbol}") String value) {
    public Mt5Symbol {
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("WIN[A-Z][0-9]{2}")) throw new IllegalArgumentException("Invalid MT5 live WIN contract");
    }
    public void configure(ProcessBuilder builder) { builder.environment().put("MT5_SYMBOL",value); }
    public static Mt5Symbol defaults() {
        String property = System.getProperty("mt5.symbol");
        if (property != null) return new Mt5Symbol(property);
        String env = System.getenv("MT5_SYMBOL");
        if (env != null) return new Mt5Symbol(env);
        try (var stream = Mt5Symbol.class.getResourceAsStream("/application.properties")) {
            var properties = new Properties();
            if (stream == null) throw new IllegalStateException("Shared mt5.symbol settings missing");
            properties.load(stream);
            String raw = properties.getProperty("mt5.symbol");
            var match = java.util.regex.Pattern.compile("\\$\\{MT5_SYMBOL:([A-Z0-9]+)}").matcher(raw);
            return new Mt5Symbol(match.matches() ? match.group(1) : raw);
        } catch (IOException ex) { throw new IllegalStateException("Cannot read MT5 live contract",ex); }
    }
}
