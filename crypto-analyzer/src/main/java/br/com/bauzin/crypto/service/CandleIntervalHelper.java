package br.com.bauzin.crypto.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class CandleIntervalHelper {

    private CandleIntervalHelper() {
    }

    public static Instant resolveLatestClosedOpenTime(String interval) {
        long intervalMillis = toMillis(interval);
        long currentBucketStart = resolveCurrentOpenTime(interval).toEpochMilli();
        long latestClosedOpenTime = currentBucketStart - intervalMillis;

        return Instant.ofEpochMilli(Math.max(latestClosedOpenTime, 0));
    }

    public static Instant resolveCurrentOpenTime(String interval) {
        return resolveCurrentOpenTime(interval, Instant.now());
    }

    public static Instant resolveCurrentOpenTime(String interval, Instant referenceTime) {
        long intervalMillis = toMillis(interval);
        long nowMillis = referenceTime.toEpochMilli();
        long currentBucketStart = (nowMillis / intervalMillis) * intervalMillis;

        return Instant.ofEpochMilli(Math.max(currentBucketStart, 0));
    }

    public static Instant resolveInitialOpenTime(String interval, int initialLoadSize) {
        Instant latestClosedOpenTime = resolveLatestClosedOpenTime(interval);
        long intervalMillis = toMillis(interval);
        long candlesBack = Math.max(initialLoadSize - 1L, 0L);
        long startTime = latestClosedOpenTime.toEpochMilli() - (candlesBack * intervalMillis);

        return Instant.ofEpochMilli(Math.max(startTime, 0));
    }

    public static Instant resolveNextOpenTime(Instant openTime, String interval) {
        return openTime.plusMillis(toMillis(interval));
    }

    public static Instant resolvePreviousOpenTime(Instant openTime, String interval) {
        return openTime.minusMillis(toMillis(interval));
    }

    public static long toMillis(String interval) {
        String normalizedInterval = normalize(interval);
        int quantity = Integer.parseInt(normalizedInterval.substring(0, normalizedInterval.length() - 1));
        char unit = normalizedInterval.charAt(normalizedInterval.length() - 1);

        return switch (unit) {
            case 'm' -> ChronoUnit.MINUTES.getDuration().toMillis() * quantity;
            case 'h' -> ChronoUnit.HOURS.getDuration().toMillis() * quantity;
            case 'd' -> ChronoUnit.DAYS.getDuration().toMillis() * quantity;
            case 'w' -> ChronoUnit.WEEKS.getDuration().toMillis() * quantity;
            default -> throw new IllegalArgumentException("Unsupported interval: " + interval);
        };
    }

    public static String normalize(String interval) {
        if (interval == null || interval.isBlank()) {
            return "1m";
        }

        return interval.trim().toLowerCase();
    }
}
