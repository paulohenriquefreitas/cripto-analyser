package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.profile.VolumeProfile;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Passive subscriber of the existing shared stream; never starts MT5 or reads DOM. */
@Component
public final class Mt5VolumeProfileService {
    private final VolumeProfile.Calendar calendar;
    private final String symbol;
    private final Set<String> sourceFaults = new TreeSet<>();
    private VolumeProfile current;
    private VolumeProfile.Snapshot previous;
    private boolean connected;
    private Long receivedUtc;

    public Mt5VolumeProfileService(String open, String close, String holidays, boolean confirmed,
                                  long timeShift, boolean timeConfirmed) {
        this(open,close,holidays,confirmed,timeShift,timeConfirmed,Mt5Symbol.defaults());
    }
    @Autowired public Mt5VolumeProfileService(
            @Value("${mt5.volume-profile.session-open:09:00}") String open,
            @Value("${mt5.volume-profile.session-close:18:00}") String close,
            @Value("${mt5.volume-profile.holidays:}") String holidays,
            @Value("${mt5.volume-profile.calendar-confirmed:false}") boolean confirmed,
            @Value("${mt5.volume-profile.feed-time-shift-minutes:0}") long timeShift,
            @Value("${mt5.volume-profile.time-basis-confirmed:false}") boolean timeConfirmed,
            Mt5Symbol symbol) {
        this.symbol = symbol.value();
        var days = Arrays.stream(holidays.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(LocalDate::parse).collect(Collectors.toSet());
        calendar = new VolumeProfile.Calendar(LocalTime.parse(open),LocalTime.parse(close),days,
                confirmed,Duration.ofMinutes(timeShift),timeConfirmed);
    }
    public synchronized void onTrade(MarketTrade trade, Mt5TradeStreamClient.Cursor cursor) {
        if (!symbol.equals(trade.symbol())) { fault("SOURCE_SYMBOL_MISMATCH"); return; }
        var date = calendar.local(trade.timeMsc()).toLocalDate();
        if (current == null || date.isAfter(current.session())) {
            if (current != null) previous = current.snapshot();
            current = new VolumeProfile(trade.symbol(),date,BigDecimal.valueOf(5),calendar);
            sourceFaults.forEach(current::fault);
        }
        if (cursor == null) { fault("SOURCE_CURSOR_UNAVAILABLE"); return; }
        current.accept(trade,new VolumeProfile.Identity(cursor.timeMsc(),cursor.ordinal()));
        sourceFaults.addAll(current.faults());
        receivedUtc = System.currentTimeMillis();
    }
    public synchronized void connectionStatus(boolean available, long ignored) {
        connected = available;
        if (!available) fault("SOURCE_UNAVAILABLE");
    }
    public synchronized void disconnected() { connected = false; fault("SOURCE_DISCONNECTED"); }
    public synchronized void invalidVolume(long ignored) { fault("INVALID_VOLUME"); }
    private void fault(String reason) {
        sourceFaults.add(reason);
        if (current != null) current.fault(reason);
    }
    public record View(boolean connected, Long lastReceivedUtcMsc, VolumeProfile.Snapshot profile,
                       VolumeProfile.Snapshot previousSession, List<String> sourceFaults) {}
    public synchronized View snapshot() {
        return new View(connected,receivedUtc,current == null ? null : current.snapshot(),previous,List.copyOf(sourceFaults));
    }
    public synchronized VolumeProfile.Readout read(BigDecimal price) {
        return current == null ? null : current.read(price);
    }
}
