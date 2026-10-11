# Setup91 EMA reversal research

`Setup91` is additive; Pullback09 remains available. It uses the existing TA4J
`EMAIndicator(ClosePriceIndicator, 9)` on closed M5 closes, never the intrabar SMA9.
Each `onCandleClosed(candle, availableAtTimeMsc)` call returns an immutable
observation with the candle, availability timestamp, previous/current EMA9,
previous/current slope, previous/current direction, and optional BUY/SELL candidate.
Slope is current EMA9 minus previous EMA9, in instrument price points per M5 bar.

Before arming, Setup91 requires directional close confirmation on the two latest
CLOSED candles from its TA4J series:

- BUY: `Close(i) > Close(i-1) + 15`.
- SELL: `Close(i) < Close(i-1) - 15`.

The 15-point WIN margin is strict: exactly 15 points, equal closes and smaller
moves reject that side. Arithmetic and comparisons use TA4J `Num` values from
one series factory, preserving decimal prices. Highs and lows do not filter
candidates. Confirmation and the three-point EMA reversal must qualify on the
same closed candle; intrabar prices cannot arm a signal. Rejected candles still
update EMA history and diagnostics, and cannot become delayed signals.
ARMED retains the qualifying candle bucket; its event timestamp records when
closure was actually observed. Breakout extremes, N+1 execution, ATR risk bounds
and the 17:30 UTC entry limit remain unchanged.

Direction reversal uses native TA4J rules on closed EMA9:

- SELL: `PreviousBarAppliedRule(IsRisingRule(ema9, 1, 1.0), 1).and(IsFallingRule(ema9, 1, 1.0))`.
- BUY: `PreviousBarAppliedRule(IsFallingRule(ema9, 1, 1.0), 1).and(IsRisingRule(ema9, 1, 1.0))`.

TA4J 0.22.6 uses numeric strength `1.0` for strict direction; it has no boolean
constructor and no previous-bar decorator. The `ta4j-engine` compatibility adapter
`PreviousBarAppliedRule` applies a TA4J Rule at `index - 1`, returning false for
insufficient history. No future candles enter either rule. Flat EMA values do not
qualify. Three finite consecutive EMA values are required before rule evaluation.
The existing slope threshold classifies diagnostic directions only; it no longer
filters native reversal candidates.

Execution supports one open position. Replay and trigger research update exits
first, then skip signal evaluation on any LAST that started or remains positioned.
Closed EMA/ATR history remains continuous. Pending setups are cleared silently
while positioned and cannot reappear on resume; no new entry or rejection event
is emitted. The next LAST after closing may evaluate fresh signals.

`previousFiveEma9` and `previousFiveDirection` remain diagnostic snapshots only;
mixed, equal or unavailable older values do not veto a valid three-point reversal.
Unavailable values are not skipped. Lists remain immutable.

The installed TA4J 0.22.6 returns NaN EMA for the first nine closed bars and seeds
EMA with the tenth close. Warm-up observations have NaN EMA, null unavailable
previous EMA/slopes, FLAT direction and no candidate. The tenth observation has
a finite EMA but no slope; the twelfth closed bar is the earliest possible
reversal candidate, once three finite consecutive EMA values are available. Unavailable slope is distinct from
a measured zero slope even though both use the non-signalling FLAT direction.
For warmed research, feed chronological historical closed M5 candles through the
same method and discard observations before the analysis period. Missing buckets
are not synthesized; successive observed closed bars are successive EMA samples.
The detector retains its closed series for its lifetime; create one per research
session, and retain returned observations externally as needed.

`onEvent(IntrabarMarketState)` is the adapter for `IntrabarM5Processor`: it returns
an observation only when `completedCandle` is present. Availability is the event
that reveals closure, including delayed rollover; the next candle's price never
enters EMA9. A trailing developing candle is never force-closed. Direct callers
must supply authoritative closed candles; availability before candle end, duplicate
or out-of-order candles, and mixed symbols are rejected before mutation.

No dataset/import/cache, Batch/Replay, or outcome/MFE-MAE files change. The existing
`Pullback09CausalProcessor` is the future wiring point; this scope exposes the new
detector API without adding candidates to old strategy events or trade outcomes.

## Signal candle and causal breakout

`Setup91Breakout.onEvent(state, sequence)` consumes each `IntrabarM5Processor`
output exactly once, with the original LAST sequence (the existing historical
`HistoricalCanonicalPriceEvent.sequence`) or an increasing arrival ordinal for a
live source. Timestamp must not decrease and sequence must strictly increase,
including between equal-timestamp LASTs. Do not sort or deduplicate by timestamp.

On a closed-candle reversal, an immutable `SignalCandle` stores side, symbol,
M5 bucket and OHLC through `IntrabarCandleSnapshot`, plus EMA9 and both slopes.
The returned `Update` exposes the EMA observation and an ordered list of research
events: `SETUP91_BUY_ARMED`, `SETUP91_BUY_TRIGGERED`, `SETUP91_BUY_CANCELLED`,
`SETUP91_SELL_ARMED`, `SETUP91_SELL_TRIGGERED`, or `SETUP91_SELL_CANCELLED`.
Each event retains the signal, actual LAST timestamp,
sequence and price. Signal identity is its symbol, bucket and side.

During the immediately following M5 bucket N+1, the first LAST strictly above
signal HIGH triggers BUY; strictly below signal LOW triggers SELL. Exact touches do not trigger. Only closed candles can
arm; signal-candle LASTs cannot trigger that signal. The rollover LAST is in a
later bucket and can both reveal closure and break out: ARMED precedes TRIGGERED
in the returned list, with the same LAST sequence. Developing candle extrema are
never used to infer an earlier breakout.

Each signal from Candle N may trigger only in the calendar M5 bucket beginning
at N's bucket start + 300,000 ms. If still pending when N+1 closes, it emits
CANCELLED and is removed regardless of EMA direction. On rollover, old signals
expire first, then the newly closed candle is processed for a reversal, then the
new LAST may trigger a signal whose N+1 execution window matches its bucket.
The first LAST of N+2 cannot trigger an expired signal, even if it already crosses
the old extreme; further LASTs with the same timestamp cannot resurrect it.
A triggered signal is removed immediately and is never cancelled afterward.

Missing buckets do not extend the window. If closure of N is first observed in
N+2 or later, ARMED and CANCELLED are emitted in that order on the revealing LAST,
without retaining the signal or allowing a trigger. Cancellation carries the
actual revealing timestamp/sequence, never a synthetic tick. An unobserved
closure at EOF is not fabricated. No candidate can survive into a later or
opposite reversal's execution window.
`armedSignals()` returns an immutable snapshot. The wrapper owns its EMA detector
and consumes the ordered stream from initialization, including TA4J warm-up.
This API is research only: no orders, execution, stops, targets, outcome tracking,
Batch wiring or frontend changes.

Fast deterministic validation (no historical command):

```powershell
mvn -pl panic-scanner -am test '-Dtest=Setup91Test,Setup91BreakoutTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The chart renders only native green BUY / red SELL arrows for TRIGGERED events,
anchored to the execution candle. ARMED/CANCELLED and rejected trade events produce
no plotted labels or markers. Entry trade events do not duplicate trigger arrows;
trade outcomes and hover diagnostics remain available.

## EMA9 direction validation view

The Replay chart currently displays pure EMA9 reversals, not execution triggers.
`Observation.reversal` is the native direction change independent of the 15-point
close confirmation, ATR, execution windows and open-position gating. It is
computed only after an authoritative candle closes, including while a position
is open, for diagnostics. `candidate` and execution remain subject to their gates.

`ClosedEma9` carries `reversal` (UP/DOWN/null), `previousValue` and
`availableAtTimeMsc`. The frontend persists these backend diagnostics, plots one
arrow at the CLOSED candle timestamp and never derives reversals from ticks or
ARMED/TRIGGERED events. No arrow is placed at the next execution candle.
On 2026-09-09 the downward turn belongs to 11:00 UTC; it becomes observable at
11:05:00.020 UTC, when the 11:00 candle closes. Equality is not a strict turn.
