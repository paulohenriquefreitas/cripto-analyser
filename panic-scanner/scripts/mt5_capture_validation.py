"""Capture-only continuity validation; never resets or advances an unverified cursor."""
from dataclasses import asdict
import time

from mt5_canonical_price_stream import query, BATCH_SIZE


def connection(api):
    try:
        info = api.terminal_info()
        return bool(info.connected) if info is not None else None
    except Exception:
        return None


def identity(row):
    if hasattr(row, 'dtype') and row.dtype.names:
        return tuple((name, row[name].item()) for name in row.dtype.names)
    return tuple(sorted(dict(row).items()))


class CursorValidationError(RuntimeError):
    def __init__(self, diagnostics):
        self.diagnostics = diagnostics
        super().__init__('SOURCE_GAP cursor continuity unverified: ' + diagnostics[-1]['reason'])


class CaptureQuery:
    def __init__(self, api, attempts=3, clock=time.monotonic):
        if not isinstance(attempts, int) or not 1 <= attempts <= 3:
            raise ValueError('attempts must be between 1 and 3')
        self.api, self.attempts, self.clock = api, attempts, clock
        self.expected = None
        self.prefix = ()

    def read(self, symbol, cursor, through, counters, batch_size=BATCH_SIZE):
        diagnostics = []
        owner = self

        class Reply:
            rows = None
            duration = None

            def __getattr__(self, name):
                return getattr(owner.api, name)

            def copy_ticks_from(self, *args):
                start = owner.clock()
                try:
                    rows = owner.api.copy_ticks_from(*args)
                finally:
                    self.duration = (owner.clock() - start) * 1000
                duration = self.duration
                self.rows = rows
                if rows is None:
                    return None  # Existing API-error policy remains unchanged.
                prefix = tuple(identity(r) for r in rows if int(r['time_msc']) == cursor.time_msc)
                reason = None
                if cursor.ordinal:
                    if not len(rows):
                        reason = 'empty_response'
                    elif len(prefix) < cursor.ordinal:
                        reason = ('record_limit_before_cursor' if len(rows) == batch_size
                                  and int(rows[-1]['time_msc']) < cursor.time_msc
                                  else 'anchor_absent' if not prefix else 'prefix_shortened')
                    elif owner.expected != cursor or len(owner.prefix) != cursor.ordinal:
                        reason = 'consumed_prefix_unavailable'
                    elif prefix[:cursor.ordinal] != owner.prefix:
                        reason = 'consumed_prefix_changed'
                    elif len(rows) == batch_size and int(rows[-1]['time_msc']) == cursor.time_msc and len(prefix) == cursor.ordinal:
                        reason = 'record_limit_without_progress'
                if reason:
                    counters.rowsFetched += len(rows)
                    counters.fullBatches += int(len(rows) == batch_size)
                    diagnostics.append(dict(reason=reason, attempt=len(diagnostics)+1,
                        returned_count=len(rows), first_time_msc=int(rows[0]['time_msc']) if len(rows) else None,
                        last_time_msc=int(rows[-1]['time_msc']) if len(rows) else None,
                        expected_cursor=asdict(cursor), observed_prefix_count=len(prefix),
                        expected_prefix=owner.prefix, observed_prefix=prefix,
                        record_limit=batch_size, limit_reached=len(rows) == batch_size,
                        copy_ticks_api_ms=duration, connected=connection(owner.api)))
                    raise CursorValidationError(diagnostics)
                return rows

        for attempt in range(self.attempts):
            reply = Reply()
            try:
                result = query(reply, symbol, cursor, through, counters, batch_size)
            except CursorValidationError:
                if attempt + 1 == self.attempts:
                    raise
                continue
            except Exception as exc:
                # API/order/schema errors remain terminal, including after a retry.
                rows = getattr(reply, 'rows', None)
                count = len(rows) if rows is not None else None
                diagnostics.append(dict(reason='query_error', error=str(exc), attempt=attempt+1,
                    returned_count=count,
                    first_time_msc=int(rows[0]['time_msc']) if count else None,
                    last_time_msc=int(rows[-1]['time_msc']) if count else None,
                    expected_cursor=asdict(cursor), expected_prefix=self.prefix,
                    observed_prefix_count=None, record_limit=batch_size,
                    limit_reached=count == batch_size,
                    copy_ticks_api_ms=getattr(reply, 'duration', None), connected=connection(self.api)))
                exc.diagnostics = diagnostics
                raise
            _, updated, _ = result
            self.prefix = tuple(identity(r) for r in reply.rows
                                if int(r['time_msc']) == updated.time_msc)[:updated.ordinal]
            self.expected = updated
            return (*result, diagnostics)
