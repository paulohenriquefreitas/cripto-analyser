# FLOW clock

The live monitoring adapter evaluates windows in raw MT5 feed time. With
`mt5.flow.feed-time-shift-minutes=180`, the reference is backend UTC minus
180 minutes. The same reference drives expiry, freshness and price displacement.
Trade timestamps, capture cursor, strategy and Volume Profile are unchanged.

The reference advances from wall time and a monotonic clock, never from receipt
of a trade or connection notification. Trades at least 30 seconds old are ignored
by FLOW; delayed trades inside retention retain their actual age and expire from
shorter windows normally. Events more than two seconds ahead are rejected. This
tolerance accommodates the small lead observed in the existing capture; it does
not permit arbitrary future events to poison the clock. Source ordering and
symbol checks remain in force. Rejection counts are exposed in `timeBasis`.

Every FLOW snapshot includes `timeBasis.feedTimeShiftMinutes` and `confirmed`.
The default confirmation is false: this is an explicit operational warning,
independent of the window's existing quality classification. Activity or a GOOD
window does not validate the feed convention. Do not enable confirmation merely
because windows contain trades.

Properties are loaded at application startup: restart after changing the shift or
confirmation. A restart creates empty FLOW buffers. `configureTimeBasis` also
atomically recreates only the FLOW engine, counters and clock anchor when either
setting changes; identical settings retain buffers. No live property reload or
configuration endpoint is introduced. Reconnection alone preserves the clock.

After UTC rollback, monotonic elapsed time continues to expire windows. If the
feed itself regresses, older source events are rejected until timestamps recover
or the service is restarted. A large erroneous forward backend-clock jump cannot
be reversed inside a running instance; correct the clock and restart. Never
automatically re-anchor to received backlog. Backlog indistinguishable from live
events within the two-second freshness threshold cannot be identified from the
raw timestamp alone; older retained events remain STALE.

Unit tests include 60 seconds of virtual market activity without sleeping,
followed by inactivity and recovery. No terminal or external server is started.

For an optional read-only local check against an already restarted backend:

```powershell
powershell -NoProfile -File .\panic-scanner\scripts\validate_flow_clock.ps1 -Seconds 60
```

Run from the repository root. Use `-Uri ws://localhost:PORT/ws/mt5/ticks` if needed.
The command prints sampled FLOW snapshots and checks monotonicity and window
bounds. It neither starts the backend nor changes settings. It does not certify
the broker's clock convention or require trades during an idle market.
