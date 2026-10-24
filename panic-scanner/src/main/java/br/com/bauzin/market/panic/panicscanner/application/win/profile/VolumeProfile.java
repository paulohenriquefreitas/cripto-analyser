package br.com.bauzin.market.panic.panicscanner.application.win.profile;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Executed volume only. A delivery identity is a source cursor, never trade content. */
public final class VolumeProfile {
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public record Calendar(LocalTime open, LocalTime close, Set<LocalDate> holidays,
                           boolean confirmed, Duration feedTimeShift, boolean timeBasisConfirmed) {
        public Calendar {
            Objects.requireNonNull(open); Objects.requireNonNull(close);
            holidays = Set.copyOf(holidays); Objects.requireNonNull(feedTimeShift);
            if (!close.isAfter(open)) throw new IllegalArgumentException("Session must close after open");
        }
        public boolean tradingDay(LocalDate day) {
            return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(day);
        }
        public ZonedDateTime local(long feedTimeMsc) {
            return Instant.ofEpochMilli(feedTimeMsc).plus(feedTimeShift).atZone(ZONE);
        }
    }
    public record Identity(long timeMsc, long ordinal) implements Comparable<Identity> {
        public Identity { if (timeMsc < 0 || ordinal < 1) throw new IllegalArgumentException("Invalid source identity"); }
        @Override public int compareTo(Identity other) {
            int byTime = Long.compare(timeMsc, other.timeMsc);
            return byTime != 0 ? byTime : Long.compare(ordinal, other.ordinal);
        }
    }
    public record Level(BigDecimal price, BigDecimal totalVolume, BigDecimal buyVolume,
                        BigDecimal sellVolume, BigDecimal ambiguousVolume, BigDecimal delta,
                        double buyPercent, double sellPercent, double ambiguousPercent, long trades) {}
    public record Region(BigDecimal low, BigDecimal high) {}
    public record Snapshot(String symbol, String session, String timezone, String volumeSource,
                           String volumeUnit, BigDecimal tickSize, String status, boolean partial,
                           boolean integrityCompromised, List<String> coverageReasons, List<String> faults,
                           Long firstTimeMsc, Long lastTimeMsc, long trades, BigDecimal totalVolume,
                           BigDecimal delta, BigDecimal poc, BigDecimal vah, BigDecimal val,
                           BigDecimal valueAreaVolume, double valueAreaPercent, List<Level> levels,
                           List<Region> hvn, List<Region> lvn) {}
    public record Readout(String session, String status, boolean partial, List<String> coverageReasons,
                          List<String> faults, Level level, BigDecimal distanceToPoc, BigDecimal distanceToVah,
                          BigDecimal distanceToVal, BigDecimal distanceToHvn, BigDecimal distanceToLvn) {}
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal TARGET = new BigDecimal("0.70");
    private final String symbol;
    private final LocalDate session;
    private final BigDecimal tickSize;
    private final Calendar calendar;
    private final NavigableMap<Long, Bucket> buckets = new TreeMap<>();
    private final Set<String> faults = new TreeSet<>();
    private Identity cursor;
    private MarketTrade lastDelivery;
    private Long firstTime, lastTime;
    private long trades;
    private BigDecimal total = ZERO, delta = ZERO;
    private Long poc;
    private boolean coverageConfirmed;
    private Snapshot cached;

    public VolumeProfile(String symbol, LocalDate session, BigDecimal tickSize, Calendar calendar) {
        this.symbol = Objects.requireNonNull(symbol); this.session = Objects.requireNonNull(session);
        this.tickSize = Objects.requireNonNull(tickSize); this.calendar = Objects.requireNonNull(calendar);
        if (!symbol.startsWith("WIN") || tickSize.signum() <= 0) throw new IllegalArgumentException("Invalid WIN profile");
    }
    public LocalDate session() { return session; }
    public synchronized List<String> faults() { return List.copyOf(faults); }
    public synchronized void fault(String reason) {
        faults.add(Objects.requireNonNull(reason)); cached = null;
    }
    /** Replayed old identities are ignored; equal content with a new ordinal is a new trade. */
    public synchronized boolean accept(MarketTrade trade, Identity identity) {
        if (identity == null || identity.timeMsc() != trade.timeMsc()) { fault("SOURCE_IDENTITY_MISMATCH"); return false; }
        if (!symbol.equals(trade.symbol())) { fault("SYMBOL_MISMATCH"); return false; }
        if (cursor != null && identity.compareTo(cursor) <= 0) {
            if (identity.equals(cursor) && !trade.equals(lastDelivery)) fault("SOURCE_IDENTITY_CHANGED");
            return false;
        }
        var local = calendar.local(trade.timeMsc());
        if (!local.toLocalDate().equals(session)) { fault("SESSION_MISMATCH"); return false; }
        if (!calendar.tradingDay(session) || local.toLocalTime().isBefore(calendar.open())
                || !local.toLocalTime().isBefore(calendar.close())) {
            fault("OUTSIDE_CONFIGURED_SESSION"); return false;
        }
        long key;
        try { key = BigDecimal.valueOf(trade.price()).divide(tickSize, 0, RoundingMode.UNNECESSARY).longValueExact(); }
        catch (ArithmeticException ex) { fault("PRICE_OFF_TICK_GRID"); return false; }
        if (cursor != null && identity.timeMsc() == cursor.timeMsc() && identity.ordinal() != cursor.ordinal()+1)
            fault("SOURCE_ORDINAL_GAP");
        if (cursor != null && identity.timeMsc() > cursor.timeMsc() && identity.ordinal() != 1)
            fault("SOURCE_ORDINAL_GAP");
        if (cursor == null && identity.ordinal() != 1) fault("SOURCE_PREFIX_MISSING");
        BigDecimal volume = BigDecimal.valueOf(trade.volume());
        Bucket b = buckets.computeIfAbsent(key, ignored -> new Bucket());
        switch (trade.side()) {
            case BUY -> { b.buy = b.buy.add(volume); delta = delta.add(volume); }
            case SELL -> { b.sell = b.sell.add(volume); delta = delta.subtract(volume); }
            case AMBIGUOUS -> b.ambiguous = b.ambiguous.add(volume);
        }
        b.total = b.total.add(volume); b.trades++;
        total = total.add(volume); trades++;
        if (poc == null || b.total.compareTo(buckets.get(poc).total) > 0
                || (b.total.compareTo(buckets.get(poc).total) == 0 && key < poc)) poc = key;
        if (firstTime == null) firstTime = trade.timeMsc();
        lastTime = trade.timeMsc(); cursor = identity; lastDelivery = trade; cached = null;
        coverageConfirmed = false; // New evidence invalidates any previous full-history assertion.
        return true;
    }
    /** Only an external audited history importer may assert full session coverage. Live never calls this. */
    public synchronized void confirmCoverage(Instant from, Instant through) {
        Instant open = session.atTime(calendar.open()).atZone(ZONE).toInstant();
        Instant close = session.atTime(calendar.close()).atZone(ZONE).toInstant();
        if (!calendar.confirmed() || !calendar.timeBasisConfirmed() || from.isAfter(open)
                || through.isBefore(close) || trades == 0 || !faults.isEmpty())
            throw new IllegalStateException("Complete session coverage is not proven");
        coverageConfirmed = true; cached = null;
    }
    public synchronized Snapshot snapshot() {
        if (cached != null) return cached;
        List<String> coverage = new ArrayList<>();
        if (!coverageConfirmed) coverage.add("FULL_SESSION_HISTORY_UNVERIFIED");
        if (!calendar.confirmed()) coverage.add("CALENDAR_UNVERIFIED");
        if (!calendar.timeBasisConfirmed()) coverage.add("FEED_TIME_BASIS_UNVERIFIED");
        if (firstTime == null) coverage.add("NO_ACCEPTED_TRADES");
        List<Level> levels = buckets.entrySet().stream().map(e -> e.getValue().level(price(e.getKey()))).toList();
        Long low = poc, high = poc;
        BigDecimal area = poc == null ? ZERO : buckets.get(poc).total;
        BigDecimal target = total.multiply(TARGET);
        while (low != null && area.compareTo(target) < 0) {
            Long below = buckets.lowerKey(low), above = buckets.higherKey(high);
            if (below == null && above == null) break;
            // Adjacent TRADED prices; gaps have no observed volume and are never
            // labelled LVN. Equal candidate volumes expand the lower side first.
            if (above == null || (below != null && buckets.get(below).total.compareTo(buckets.get(above).total) >= 0)) {
                low = below; area = area.add(buckets.get(low).total);
            } else { high = above; area = area.add(buckets.get(high).total); }
        }
        BigDecimal peak = poc == null ? ZERO : buckets.get(poc).total;
        List<Region> hvn = regions(peak.multiply(new BigDecimal("0.70")), true);
        List<Region> lvn = regions(peak.multiply(new BigDecimal("0.30")), false);
        cached = new Snapshot(symbol, session.toString(), ZONE.getId(), "MT5_VOLUME_REAL", "MT5_VOLUME_REAL_UNITS",
                tickSize, !faults.isEmpty() ? "INCOMPLETE" : coverage.isEmpty() ? "COMPLETE" : "PARTIAL",
                !coverage.isEmpty(), !faults.isEmpty(), List.copyOf(coverage), List.copyOf(faults),
                firstTime, lastTime, trades, total, delta, price(poc), price(high), price(low), area,
                percent(area, total), levels, hvn, lvn);
        return cached;
    }
    public synchronized Readout read(BigDecimal currentPrice) {
        var s = snapshot();
        Long key = null;
        try { key = currentPrice.divide(tickSize, 0, RoundingMode.UNNECESSARY).longValueExact(); }
        catch (ArithmeticException ignored) { /* off-grid read is explicitly absent */ }
        Bucket b = key == null ? null : buckets.get(key);
        return new Readout(s.session(),s.status(),s.partial(),s.coverageReasons(),s.faults(),
                b == null ? null : b.level(price(key)), distance(currentPrice,s.poc()),
                distance(currentPrice,s.vah()), distance(currentPrice,s.val()),
                regionDistance(currentPrice,s.hvn()), regionDistance(currentPrice,s.lvn()));
    }
    private BigDecimal distance(BigDecimal from, BigDecimal to) { return to == null ? null : from.subtract(to).abs(); }
    private BigDecimal regionDistance(BigDecimal p, List<Region> regions) {
        return regions.stream().map(r -> p.compareTo(r.low()) < 0 ? r.low().subtract(p)
                : p.compareTo(r.high()) > 0 ? p.subtract(r.high()) : ZERO).min(BigDecimal::compareTo).orElse(null);
    }
    private List<Region> regions(BigDecimal threshold, boolean high) {
        List<Region> result = new ArrayList<>(); Long from = null, end = null;
        for (var e : buckets.entrySet()) {
            boolean selected = high ? e.getValue().total.compareTo(threshold) >= 0 : e.getValue().total.compareTo(threshold) <= 0;
            if (from != null && (!selected || e.getKey() != end+1)) {
                result.add(new Region(price(from),price(end))); from = end = null;
            }
            if (selected) { if (from == null) from = e.getKey(); end = e.getKey(); }
        }
        if (from != null) result.add(new Region(price(from),price(end)));
        return List.copyOf(result);
    }
    private BigDecimal price(Long key) { return key == null ? null : tickSize.multiply(BigDecimal.valueOf(key)); }
    private static double percent(BigDecimal part, BigDecimal total) {
        return total.signum() == 0 ? 0 : part.multiply(BigDecimal.valueOf(100)).divide(total, 10, RoundingMode.HALF_UP).doubleValue();
    }
    private static final class Bucket {
        BigDecimal total = ZERO, buy = ZERO, sell = ZERO, ambiguous = ZERO; long trades;
        Level level(BigDecimal price) {
            return new Level(price,total,buy,sell,ambiguous,buy.subtract(sell),percent(buy,total),
                    percent(sell,total),percent(ambiguous,total),trades);
        }
    }
}
