export type SwingCandle = { time: number; high: number; low: number; close: number };
export type SwingPivot = { type: 'HIGH' | 'LOW'; time: number; price: number; confirmedAt: number };

/** Strict 2-left/2-right pivots. The last candle is always treated as forming. */
export function createSwingPivotTracker() {
  let window: SwingCandle[] = [];
  let highs: SwingPivot[] = [];
  let lows: SwingPivot[] = [];
  let lastClosedTime = -Infinity;
  let current: SwingCandle | undefined;

  function close(candle: SwingCandle, confirmedAt: number) {
    if (candle.time <= lastClosedTime) return;
    lastClosedTime = candle.time;
    // Retire levels only after a subsequent candle closes beyond the wick price.
    highs = highs.filter(level => candle.close <= level.price);
    lows = lows.filter(level => candle.close >= level.price);
    window.push({ ...candle });
    if (window.length < 5) return;
    const pivot = window[2];
    const neighbors = window.filter((_, index) => index !== 2);
    if (neighbors.every(bar => pivot.high > bar.high)) {
      highs = [...highs, { type: 'HIGH' as const, time: pivot.time, price: pivot.high, confirmedAt }];
    }
    if (neighbors.every(bar => pivot.low < bar.low)) {
      lows = [...lows, { type: 'LOW' as const, time: pivot.time, price: pivot.low, confirmedAt }];
    }
    window.shift();
  }

  return {
    setHistory(candles: readonly SwingCandle[]) {
      if (!candles.length) {
        window = []; highs = []; lows = []; lastClosedTime = -Infinity; current = undefined;
        return;
      }
      for (let index = 0; index < candles.length - 1; index++) {
        close(candles[index], candles[index + 1].time);
      }
      const last = candles[candles.length - 1];
      if (!current || last.time >= current.time) current = { ...last };
    },
    updateLast(candle: SwingCandle) {
      if (current && candle.time < current.time) return;
      if (current && candle.time > current.time) close(current, candle.time);
      current = { ...candle };
    },
    getLevels: (): readonly SwingPivot[] => [...highs, ...lows],
  };
}
