# Experimental structure reactions

The replay owns one single-writer StructureReactionScanner. It consumes confirmed,
closed M5 candles from warmup and the causal replay processor. Strict two-left /
two-right wick pivots match the chart; subsequent candle closes beyond the level
retire it. No future candles are read for confirmation. Existing trading rules do
not consume these observations.

Default experimental settings: touch <=15 points, breakout >20 points, absolute
500ms delta >=300 contracts. These are configurable constructor settings, not
calibrated or validated trading parameters. A touch needs opposing directional
flow at the next available strictly later timestamp; no synthetic 1ms event is
created. Breakout needs crossing the 20-point boundary and directional flow on
that event. Each level emits at most once over its active lifetime. Optional
returns at most one observation per input, in active storage order; simultaneous
reactions are not an exhaustive multi-level report.

The session only checks complete GOOD VolumeDeltaTracker results. The latest
observation is accessible through structureReaction(); onStructureReaction()
provides synchronous observations before visual throttling. Each observation is
also queued in a ConcurrentLinkedQueue before callbacks run. A pending reaction
forces visual publication; publication drains every reaction as its own
MARKET_STATE frame with structureReaction (including delta500ms) and the original
timeMsc. These event-only frames have null candle/indicators/flow, so later OHLC
is never attributed to an earlier event. The normal candle frame follows and
never repeats the reaction. Pause/completion/error flushes also drain pending
reactions when a latest market state exists. Subscribers must not block or throw.

This prevents loss from local visual coalescing, not from transport failures:
the existing WebSocket transport has no client acknowledgement or durable retry.

The required List<Double> overload cannot encode level type or confirmedAt:
callers must supply already confirmed levels, and its pivot type is inferred
from the first approach. Replay uses the typed closed-candle path instead.
VWAP and POC are reserved event labels; this version does not register them.

Primitive arrays store per-level state. Arrays grow when required, and event
records / Optional allocate on signals: this is not literally GC-free. Single
writer confinement, not shared mutable access, provides concurrency safety;
only the immutable latest session observation is published via volatile.
