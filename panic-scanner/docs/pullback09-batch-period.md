# Local period research

Run `br.com.bauzin.market.panic.panicscanner.application.win.replay.Pullback09BatchPeriodCommand`
with the `panic-scanner` module classpath, JDK 21 and repository root as working directory.

Arguments:

```text
SYMBOL START_DATE END_DATE [HISTORY_TEMPLATE]
WINV26 2026-09-14 2026-09-18
WINV26 2026-09-14 2026-09-18 historical-data/{symbol}/{date}.ndjson
```

The separate single-day command remains unchanged. This command does not prepare or import data.
Every calendar date in the inclusive UTC interval must have a valid local COMPLETE dataset.
An absent, INCOMPLETE or corrupt dataset stops execution, names the failed date and exits with
code 1 without printing an aggregate report. No trading calendar is inferred.

The period runner invokes the existing `Pullback09BatchRunner` sequentially once per day.
It retains session summaries and consolidated MFE/MAE samples for exact medians, never LASTs
or the entire period's causal events. Each day has its own existing daily causal pipeline.

The console displays LAST counts, elapsed time (including validation and aggregation), throughput,
all six event counts, per-day summaries and UP/DOWN/TOTAL distributions for 5/15/30 minutes.
Overall and per-day COMPLETE means all three outcome windows are complete. Each statistical
window independently includes only its COMPLETE outcomes. Thresholds are inclusive (`>=`);
percentages use that window's complete count, including for TOTAL (pooled observations,
not an average of direction percentages). Means and medians use the same eligible observations.
An even-sized median averages the two middle values. No eligible observations prints `N/A`.
All excursion values and thresholds are points: WIN 150 points = 30 ticks, not 150 ticks.

Illustrative excerpt from the controlled six-outcome test (not real market data):

```text
PULLB09 TOTAL - 5 MIN
Complete outcomes: 6; Incomplete: 0
>= 50 pts  5 / 6  83.33%
>= 100 pts  4 / 6  66.67%
>= 150 pts  3 / 6  50.00%
>= 200 pts  2 / 6  33.33%
>= 300 pts  1 / 6  16.67%
MFE mean: 133.33; median: 125.00 pts
MAE mean: 35.00; median: 35.00 pts
```

Small deterministic tests only:

```powershell
mvn -pl panic-scanner -am test '-Dtest=Pullback09BatchPeriodRunnerTest,Pullback09BatchRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

No real periods or MT5/Python processes are executed by these tests.
