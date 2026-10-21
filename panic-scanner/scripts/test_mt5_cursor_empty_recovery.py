"""Executable proposal only: no MT5 imports, EA execution or production changes.

Fake monotonic microseconds and raw CopyTicks replies exercise a reference model.
Passing these tests does not validate MQL5 integration or actual feed completeness.
"""
from dataclasses import dataclass
import math
import unittest

T = 1792174240036


@dataclass(frozen=True)
class Tick:
    time_msc: int = T
    time: int = T // 1000
    bid: float = 210095.
    ask: float = 210100.
    last: float = 210095.
    volume: int = 5
    volume_real: float = 5.
    flags: int = 1112


def tick(ms=T, **changes):
    return Tick(time_msc=ms, time=ms // 1000, **changes)


class RecoveryProposal:
    """One call per timer; retry count excludes the initial empty response.

    The complete ordered consumed prefix is a proposed additional invariant.
    No publication or cursor commit until all validations have passed.
    """
    def __init__(self, prefix=(Tick(),), max_attempts=3, budget_us=500_000,
                 batch_size=2048):
        self.cursor = (T, len(prefix))
        self.prefix = tuple(prefix)
        self.max_attempts = max_attempts
        self.budget_us = budget_us
        self.batch_size = batch_size
        self.started = None
        self.attempts = 0
        self.fault = ''
        self.events = []
        self.published = []
        self.calls = []

    def finish(self, now, outcome):
        if self.started is not None:
            self.events.append(dict(outcome=outcome, attempts=self.attempts,
                                    uncertainty_start_us=self.started,
                                    uncertainty_end_us=now,
                                    uncertainty_duration_us=now-self.started,
                                    anchor=self.cursor))
            self.started = None

    def stop(self, now, reason):
        self.fault = reason
        self.finish(now, reason)
        return []

    def deadline(self, now):
        """Also called by timer before normal session completion/deinit."""
        if self.started is not None and now-self.started >= self.budget_us:
            self.stop(now, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')

    def reply(self, rows, start, end, error=0, count=None):
        if self.fault:
            return []
        self.deadline(start)
        if self.fault:
            return []
        recovering = self.started is not None
        if recovering:
            self.attempts += 1
        self.calls.append(self.cursor)
        count = len(rows) if count is None else count
        if error or count < 0:
            return self.stop(end, 'COPY_TICKS_FAILED_OR_PARTIAL')
        # A synchronous call can exceed the wall-time budget; never commit late.
        if recovering and end-self.started >= self.budget_us:
            return self.stop(end, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
        if count == 0 and self.cursor[1]:
            if not recovering:
                self.started = start
                self.attempts = 0
            if (self.attempts >= self.max_attempts or
                    end-self.started >= self.budget_us):
                return self.stop(end, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
            return []
        previous = -1
        for row in rows:
            if row.time_msc < previous or row.time != row.time_msc // 1000:
                return self.stop(end, 'TICK_ORDER_OR_TIME_ERROR')
            previous = row.time_msc
        anchor = tuple(r for r in rows if r.time_msc == self.cursor[0])
        if len(anchor) < self.cursor[1]:
            return self.stop(end, 'CURSOR_PREFIX_DISAPPEARED')
        comparable = all(math.isfinite(getattr(r, field))
                         for r in (*anchor[:self.cursor[1]], *self.prefix)
                         for field in ('bid', 'ask', 'last', 'volume_real'))
        if not comparable or anchor[:self.cursor[1]] != self.prefix:
            return self.stop(end, 'CURSOR_PREFIX_CONTENT_MISMATCH')
        unseen = []
        ordinal = 0
        previous = -1
        updated = self.cursor
        for row in rows:
            ordinal = ordinal+1 if row.time_msc == previous else 1
            previous = row.time_msc
            if row.time_msc < self.cursor[0] or (
                    row.time_msc == self.cursor[0] and ordinal <= self.cursor[1]):
                continue
            unseen.append(row)
            updated = (row.time_msc, ordinal)
        if count == self.batch_size and updated == self.cursor:
            return self.stop(end, 'FULL_BATCH_WITHOUT_PROGRESS')
        self.finish(end, 'reconfirmed')  # records frozen anchor before commit
        self.cursor = updated
        self.prefix = tuple(r for r in rows if r.time_msc == updated[0])
        self.published.extend(unseen)
        return unseen


class EmptyRecoveryTest(unittest.TestCase):
    def empty(self, model):
        before = model.cursor
        self.assertEqual(model.reply([], 100, 200), [])
        self.assertEqual(model.cursor, before)
        self.assertEqual(model.published, [])
        self.assertEqual(model.fault, '')

    def test_isolated_empty_then_prefix_and_new_rows(self):
        m = RecoveryProposal()
        self.empty(m)
        self.assertEqual(m.reply([tick(), tick(), tick(T+1)], 300, 400),
                         [tick(), tick(T+1)])
        self.assertEqual(m.calls, [(T, 1), (T, 1)])
        self.assertEqual(m.cursor, (T+1, 1))
        self.assertEqual(m.events[0]['attempts'], 1)
        self.assertEqual(m.events[0]['uncertainty_duration_us'], 300)

    def test_prefix_only_reconfirms_without_advancing(self):
        m = RecoveryProposal()
        self.empty(m)
        self.assertEqual(m.reply([tick()], 300, 400), [])
        self.assertEqual(m.cursor, (T, 1))
        self.assertEqual(m.events[0]['outcome'], 'reconfirmed')

    def test_attempt_limit_includes_success_on_last_retry(self):
        m = RecoveryProposal(max_attempts=2)
        self.empty(m)
        m.reply([], 300, 400)
        self.assertEqual(m.cursor, (T, 1))
        m.reply([tick(), tick()], 500, 600)
        self.assertEqual(m.fault, '')
        self.assertEqual(m.events[0]['attempts'], 2)
        self.assertEqual(len(m.published), 1)

    def test_exhaustion_stops_and_cannot_resume(self):
        m = RecoveryProposal(max_attempts=2)
        self.empty(m)
        m.reply([], 300, 400)
        m.reply([], 500, 600)
        self.assertEqual(m.fault, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
        self.assertEqual(m.events[0]['attempts'], 2)
        self.assertEqual(m.events[0]['uncertainty_duration_us'], 500)
        m.reply([tick(), tick(T+1)], 700, 800)
        self.assertEqual(m.calls, [(T, 1)]*3)
        self.assertEqual(m.cursor, (T, 1))
        self.assertEqual(m.published, [])

    def test_deadline_before_next_query(self):
        m = RecoveryProposal(budget_us=400)
        self.empty(m)
        m.reply([tick()], 500, 600)
        self.assertEqual(m.fault, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
        self.assertEqual(len(m.calls), 1)
        self.assertEqual(m.events[0]['attempts'], 0)

    def test_late_reconfirmation_does_not_commit(self):
        m = RecoveryProposal(budget_us=400)
        self.empty(m)
        m.reply([tick(), tick(T+1)], 300, 500)
        self.assertEqual(m.fault, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
        self.assertEqual(m.cursor, (T, 1))
        self.assertEqual(m.published, [])

    def test_initial_empty_query_itself_exceeds_budget(self):
        m = RecoveryProposal(budget_us=400)
        m.reply([], 100, 500)
        self.assertEqual(m.fault, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
        self.assertEqual(m.events[0]['attempts'], 0)

    def test_nonempty_missing_or_short_prefix_stops_without_publication(self):
        for prefix, response in [((tick(),), [tick(T+1)]),
                                 ((tick(), tick()), [tick(), tick(T+1)])]:
            with self.subTest(prefix=prefix):
                m = RecoveryProposal(prefix)
                self.empty(m)
                m.reply(response, 300, 400)
                self.assertEqual(m.fault, 'CURSOR_PREFIX_DISAPPEARED')
                self.assertEqual(m.cursor, (T, len(prefix)))
                self.assertEqual(m.published, [])

    def test_same_size_replacement_and_reordering_fail(self):
        a, b = tick(), tick(volume=7, volume_real=7.)
        for prefix, response in [((a,), [b, tick(T+1)]),
                                 ((a, b), [b, a, tick(T+1)])]:
            with self.subTest(prefix=prefix):
                m = RecoveryProposal(prefix)
                self.empty(m)
                m.reply(response, 300, 400)
                self.assertEqual(m.fault, 'CURSOR_PREFIX_CONTENT_MISMATCH')
                self.assertEqual(m.published, [])

    def test_identical_legitimate_duplicates_preserved_exactly_once(self):
        m = RecoveryProposal((tick(), tick()))
        self.empty(m)
        self.assertEqual(m.reply([tick()]*4, 300, 400), [tick()]*2)
        self.assertEqual(m.cursor, (T, 4))
        self.assertEqual(m.reply([tick()]*4, 500, 600), [])
        self.assertEqual(m.published, [tick()]*2)

    def test_errors_never_become_empty_recovery(self):
        for response, error, count in [([], 4403, 0), ([], 0, -1),
                                       ([tick(), tick(T+1)], 4403, 2)]:
            for recovering in (False, True):
                with self.subTest(error=error, count=count, recovering=recovering):
                    m = RecoveryProposal()
                    if recovering:
                        self.empty(m)
                    m.reply(response, 300, 400, error, count)
                    self.assertEqual(m.fault, 'COPY_TICKS_FAILED_OR_PARTIAL')
                    self.assertEqual(m.cursor, (T, 1))
                    self.assertEqual(m.published, [])

    def test_order_and_timestamp_checks_precede_commit(self):
        for rows in ([tick(), tick(T+2), tick(T+1)],
                     [tick(), Tick(time_msc=T+1000)]):
            m = RecoveryProposal()
            self.empty(m)
            m.reply(rows, 300, 400)
            self.assertEqual(m.fault, 'TICK_ORDER_OR_TIME_ERROR')
            self.assertEqual(m.cursor, (T, 1))
            self.assertEqual(m.published, [])

    def test_full_batch_without_progress_still_stops(self):
        m = RecoveryProposal((tick(), tick()), batch_size=2)
        self.empty(m)
        m.reply([tick(), tick()], 300, 400)
        self.assertEqual(m.fault, 'FULL_BATCH_WITHOUT_PROGRESS')
        self.assertEqual(m.cursor, (T, 2))

    def test_separate_episodes_reset_attempts(self):
        m = RecoveryProposal()
        self.empty(m)
        m.reply([tick()], 300, 400)
        m.reply([], 500, 600)
        m.reply([tick(), tick(T+1)], 700, 800)
        self.assertEqual([e['attempts'] for e in m.events], [1, 1])
        self.assertEqual([e['uncertainty_duration_us'] for e in m.events], [300, 300])

    def test_seed_without_consumed_prefix_has_no_recovery(self):
        m = RecoveryProposal(prefix=())
        self.empty(m)
        self.assertIsNone(m.started)
        self.assertEqual(m.events, [])

    def test_session_end_while_uncertain_cannot_be_completed(self):
        m = RecoveryProposal()
        self.empty(m)
        m.stop(350, 'CURSOR_CONTINUITY_UNVERIFIED_EMPTY')
        self.assertEqual(m.events[0]['uncertainty_duration_us'], 250)
        self.assertEqual(m.events[0]['attempts'], 0)
        self.assertEqual(m.cursor, (T, 1))

    def test_every_tick_field_is_part_of_prefix_evidence(self):
        for field, value in dict(bid=1., ask=2., last=3., volume=8,
                                 volume_real=8., flags=0).items():
            with self.subTest(field=field):
                m = RecoveryProposal()
                self.empty(m)
                m.reply([tick(**{field: value}), tick(T+1)], 300, 400)
                self.assertEqual(m.fault, 'CURSOR_PREFIX_CONTENT_MISMATCH')
                self.assertEqual(m.cursor, (T, 1))
                self.assertEqual(m.published, [])

    def test_nonfinite_prefix_cannot_reconfirm_even_same_object(self):
        for value in (float('nan'), float('inf'), -float('inf')):
            for field in ('bid', 'ask', 'last', 'volume_real'):
                with self.subTest(field=field, value=value):
                    row = tick(**{field: value})
                    m = RecoveryProposal((row,))
                    self.empty(m)
                    m.reply([row, tick(T+1)], 300, 400)
                    self.assertEqual(m.fault, 'CURSOR_PREFIX_CONTENT_MISMATCH')
                    self.assertEqual(m.published, [])

    def test_quote_and_trade_positions_both_count_towards_prefix(self):
        quote, trade = tick(flags=2), tick()
        m = RecoveryProposal((quote, trade))
        self.empty(m)
        self.assertEqual(m.reply([quote, trade, trade, quote], 300, 400),
                         [trade, quote])
        self.assertEqual(m.cursor, (T, 4))
        self.assertEqual(m.prefix, (quote, trade, trade, quote))

    def test_new_timestamp_prefix_is_retained_for_next_episode(self):
        m = RecoveryProposal()
        self.empty(m)
        m.reply([tick(), tick(T+1), tick(T+1)], 300, 400)
        self.assertEqual(m.prefix, (tick(T+1), tick(T+1)))
        m.reply([], 500, 600)
        m.reply([tick(T+1)]*3, 700, 800)
        self.assertEqual(m.cursor, (T+1, 3))
        self.assertEqual(m.published, [tick(T+1)]*3)


if __name__ == '__main__':
    unittest.main()
