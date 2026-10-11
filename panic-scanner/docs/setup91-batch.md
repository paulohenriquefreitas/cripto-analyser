# Setup91 local batch research

Main class:
`br.com.bauzin.market.panic.panicscanner.application.win.replay.Setup91BatchPeriodCommand`

IntelliJ: use the `panic-scanner` module classpath and Java 21. Set working directory
to `C:\Users\paulo\project\cripto-analyser` when `historical-data` is located there.
Program arguments:

```text
WINV26 2026-09-01 2026-09-25
```

Optional fourth argument overrides the dataset template, for example:

```text
WINV26 2026-09-01 2026-09-25 "C:\data\historical-data\{symbol}\{date}.ndjson"
```

The default is `historical-data/{symbol}/{date}.ndjson`, relative to the working
directory. Dates are inclusive UTC days, matching persisted dataset boundaries.
Every calendar date requires an existing COMPLETE dataset and metadata manifest,
including valid empty datasets for days with no LASTs. A missing, incomplete or
corrupt day aborts the report with that date. No import, acquisition or cache writes
occur, and weekends are not silently skipped.

`Setup91BatchRunner` streams one day with `Mt5HistoricalPriceSource`, checks metadata,
SHA-256 and counts, and passes LASTs in original sequence order through
`IntrabarM5Processor` and `Setup91Breakout`. Header warm-up seeds EMA9 but emits no
armed setups. Official candles are not used for signals. Each day starts with fresh
setup state; the final developing candle is not force-closed. Pending setups at EOF
remain part of ARMED counts, without synthetic cancellation or overnight carry-over.

`Setup91BatchPeriodRunner` aggregates independent daily counts. The command prints
BUY, SELL and TOTAL rows for every day and the period, containing ARMED, TRIGGERED,
CANCELLED and trigger rate. Rate = TRIGGERED / ARMED, displayed as a percentage;
zero ARMED yields N/A. Period rates use summed counts, not averages of daily rates.
ARMED can exceed TRIGGERED + CANCELLED because a setup may remain pending at EOF.

The detector uses native strict TA4J rising/falling reversal rules, combined with
strict directional close confirmation greater than 15 WIN points.
Replay and Batch reuse the same `Setup91` detector
and the same N+1-only `Setup91Breakout` lifetime. Pullback09 classes are unchanged. There are no outcomes, trades, stops, targets or profitability
calculations. Daily results expose complete research events for parity checks;
period results retain daily summaries rather than all days' event lists.

Fast synthetic tests only:

```powershell
mvn -pl panic-scanner -am test '-Dtest=Setup91Test,Setup91BreakoutTest,Setup91BatchTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```
