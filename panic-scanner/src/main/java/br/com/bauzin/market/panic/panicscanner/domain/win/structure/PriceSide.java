package br.com.bauzin.market.panic.panicscanner.domain.win.structure;

public enum PriceSide {
    ABOVE,
    BELOW,
    AT;

    public static PriceSide fromDistance(double distance) {
        return distance > 0 ? ABOVE : distance < 0 ? BELOW : AT;
    }
}
