package br.com.bauzin.market.panic.panicscanner.domain.win.intrabar;

/** Raw event time, independent of presentation timezone. */
public final class M5Bucket {
    public static final long DURATION_MSC = 300_000;
    private M5Bucket() {}

    public static long start(long timeMsc) {
        if (timeMsc < 0 || timeMsc > Long.MAX_VALUE - DURATION_MSC)
            throw new IllegalArgumentException("Unsupported event timestamp");
        return Math.floorDiv(timeMsc, DURATION_MSC) * DURATION_MSC;
    }
}
