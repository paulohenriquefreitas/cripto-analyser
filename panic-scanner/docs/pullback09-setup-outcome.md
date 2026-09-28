# PULLB09 SETUP OUTCOME

This research measures market behavior after a confirmed setup. It does not
model an entry, execution, position, stop, target or trade return.

`M5ReplaySession.researchRecords()` returns one immutable snapshot plus the
current setup outcome per PULLB09. `researchCsv()` exports these records; no new
HTTP endpoint or frontend feature is introduced.

## Reference and causality

- `pullb09CandleTimeMsc`: Candle 3 bucket, in Unix milliseconds.
- `setupAvailableTimeMsc`: first LAST of Candle 4, when Candle 3 is known closed.
- `referencePrice`: that LAST's price, **not** Candle 3's close.
- `referenceBucket`: Candle 4 bucket, also in Unix milliseconds.
- `date` in CSV is the UTC date of availability.

If the input has missing buckets, the existing setup machine's availability
event is preserved: the reference is the first actual LAST making Candle 3's
close known, with its actual bucket. Research does not invent candles or change
the setup machine's gap behavior.

RJ09 OHLC and minimum distance to SMA9 come from the existing setup context.
Candle 3 OHLC comes from `completedCandle` at the rollover.
`confirmationStrength = candle3.close - rj09.high` is only a feature.

SMA9, SMA21 and VWAP are the causal values including the reference LAST.
`distanceToVwap = referencePrice - vwap`.
Slopes are **closed candles only**, in points:
`SMA(lastClosed) - SMA(lastClosed - 3)`. No division by time or by three is used.
They require 12 closed bars for SMA9 and 24 for SMA21.
ATR14 uses the existing ta4j ATRIndicator over closed M5 candles (including
Candle 3, excluding developing Candle 4), with at least 14 closed bars.
Unavailable features are null/empty in CSV. Snapshots do not change when future
candles arrive. No feature is used to accept or reject a setup.

## Windows

Each window is `(setupAvailableTimeMsc, setupAvailableTimeMsc + duration]`,
for durations 300000, 900000 and 1800000 milliseconds. LASTs at the reference
timestamp, including additional LASTs with that same timestamp, are excluded.
The LAST exactly at the upper boundary is included. Later prices cannot change
that window. No future OHLC is used to infer price order.

`MFE = max(posterior LASTs) - referencePrice`.
`MAE = referencePrice - min(posterior LASTs)`.
These are the requested signed differences, without zero-clamping or inserting
the reference price into the extrema. For example, if every posterior price is
above the reference, MAE can be negative.

A window becomes COMPLETE when the chronological stream reaches its boundary
or passes it. Before that it is INCOMPLETE with null excursions, including when
the replay ends early. If no eligible LAST exists inside a completed window,
its extrema and excursions remain null. No extrapolation fills missing ticks.
Registration is idempotent by event ID; finishing freezes outcomes.

## Deterministic example

Reference price 100 at availability time 9900000, Candle 3 bucket 9600000,
Candle 4 bucket 9900000. RJ09 high 110, Candle 3 close 112, strength 2.
Posterior LASTs, as offsets in milliseconds and prices:

| Offset | Price |
|---:|---:|
| 1 | 108 |
| 2 | 96 |
| 300000 | 110 |
| 300001 | 92 |
| 900000 | 115 |
| 900001 | 90 |
| 1800000 | 120 |

SETUP OUTCOME: 5m MFE/MAE = 10/4; 15m = 15/8; 30m = 20/10.
All three windows are COMPLETE. This is not TRADE OUTCOME.
