import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from experimental_absorption import Detector, CONFIRMED
from observe_absorption import Observation, PreparedObservation, TimedMT5
from mt5_dom_trades_probe import run
from test_mt5_dom_trades_probe import JointProbeTest, Clock
from test_experimental_absorption import AbsorptionTest, batch, book


class ObservationTest(unittest.TestCase):
    def prepare(self, fail=False):
        clock = Clock()
        output = io.StringIO()
        prepared = PreparedObservation(output, lambda: True, clock=clock)
        prepared.receive({'kind': 'session', 'symbol': 'WINZ26'})
        cursor = None
        with patch('observe_absorption.time.monotonic', side_effect=clock):
            for i in range(7):
                clock.now = i * .1 + .002
                snapshot = dict(book(i+1, i*100, empty=fail),
                                monotonic_start=i*.1, monotonic_end=i*.1+.001,
                                latency_ms=1.)
                prepared.receive(snapshot)
                if prepared.observer:
                    break
                tick = dict(batch(i+1, i*100+2, [(i*100+2, 100, 88, 5)], cursor),
                            monotonic_start=i*.1+.001, monotonic_end=i*.1+.002,
                            latency_ms=1.)
                cursor = tick['cursor_after']
                prepared.receive(tick)
                if prepared.observer:
                    break
        return prepared, output, clock

    def test_warmup_clean_activation_and_persistent_failure(self):
        prepared, output, clock = self.prepare()
        observer = prepared.observer
        self.assertIsNotNone(observer)
        self.assertFalse(observer.detector.events)
        self.assertFalse(observer.detector.trades)
        self.assertFalse(observer.detector.books)
        self.assertTrue(all(v >= 3 for v in prepared.valid_counts.values()))
        self.assertGreaterEqual(prepared.activated-prepared.started, .5)
        self.assertNotIn('"kind": "transition"', output.getvalue())
        with patch('observe_absorption.time.monotonic', side_effect=clock):
            clock.now += .1
            seq = prepared.offsets['snapshot']+1
            r = dict(book(seq, 800, empty=True), monotonic_start=clock.now-.001,
                     monotonic_end=clock.now)
            prepared.receive(r)
            self.assertIn('observation_dom_unusable', observer.detector.faults)
            clock.now += .1
            prepared.receive(dict(book(seq+1, 900), monotonic_start=clock.now-.001,
                                  monotonic_end=clock.now))
        self.assertIs(prepared.observer, observer)
        self.assertTrue(observer.detector.faults)

    def test_warmup_timeout_never_creates_detector(self):
        prepared, output, clock = self.prepare(fail=True)
        clock.now = 31
        self.assertTrue(prepared.done())
        prepared.report()
        self.assertIsNone(prepared.observer)
        self.assertIn('warmup_not_ready:dom_unusable', output.getvalue())

    def test_boundary_cursor_is_checked_without_importing_warmup_trades(self):
        prepared, output, clock = self.prepare()
        observer = prepared.observer
        self.assertIsNone(observer.detector.cursor)
        clock.now += .02
        record = dict(batch(prepared.offsets['tick_batch']+1, 700, [(700, 100, 88, 1)]),
                      monotonic_start=clock.now-.001, monotonic_end=clock.now)
        with patch('observe_absorption.time.monotonic', side_effect=clock):
            prepared.receive(record)
        self.assertIn('observation_cursor_discontinuity', observer.detector.faults)

    def test_capture_releases_subscription_when_preparation_times_out(self):
        fixture = JointProbeTest()
        api = fixture.api()
        api.market_book_get.return_value = ()
        clock = Clock()
        prepared = PreparedObservation(io.StringIO(), lambda: True,
                                       warmup_seconds=.2, clock=clock)
        with patch('observe_absorption.time.monotonic', side_effect=clock):
            run(api, None, 10, clock, clock.sleep, prepared.receive, prepared.done)
        self.assertIsNone(prepared.observer)
        api.market_book_release.assert_called_once()
        self.assertLess(clock.now, .3)

    def test_api_and_python_timing_are_separate(self):
        clock = Clock()
        class API:
            def copy_ticks_from(self):
                clock.now += .4
                return []
        api = TimedMT5(API(), clock)
        self.assertEqual(api.copy_ticks_from(), [])
        self.assertAlmostEqual(api.copy_ms, 400)
        output = io.StringIO()
        prepared = PreparedObservation(output, lambda: True, clock=clock, timed_api=api)
        clock.now = .5
        prepared.receive(dict(batch(1, 10, [(10, 100, 88, 1)]),
                              monotonic_start=0., monotonic_end=.5, latency_ms=500.))
        row = json.loads(output.getvalue())
        self.assertAlmostEqual(row['source']['copy_ticks_api_ms'], 400.)
        self.assertAlmostEqual(row['source']['query_python_ms'], 100.)

    def test_memory_only_capture_and_delivery_gap(self):
        fixture = JointProbeTest()
        clock = Clock()
        seen = []
        run(fixture.api(), None, .05, clock, clock.sleep, on_record=seen.append)
        self.assertTrue(any(r['kind'] == 'tick_batch' for r in seen))
        observer = Observation(io.StringIO(), live=True)
        record = dict(AbsorptionTest().records()[1], monotonic_start=0., monotonic_end=.001)
        with patch('observe_absorption.time.monotonic', return_value=1.):
            observer.receive(record)
        self.assertIn('observation_delivery_gap', observer.detector.faults)

    def test_callback_precedes_any_write_and_flush(self):
        fixture = JointProbeTest()
        api = fixture.api()
        seen = []
        class Output(io.StringIO):
            def write(self, value):
                self_test.assertEqual(json.loads(value)['kind'], seen[-1]['kind'])
                return super().write(value)
        self_test = self
        clock = Clock()
        run(api, Output(), .05, clock, clock.sleep, on_record=seen.append)
        self.assertTrue(any(r['kind'] == 'tick_batch' for r in seen))

    def test_real_captures_incremental_prefixes_and_immutable_evidence(self):
        root = Path(__file__).resolve().parents[1] / 'dom-captures'
        for path in root.glob('*.ndjson'):
            records = [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()]
            observer = Observation(io.StringIO())
            baseline = Detector()
            previous = []
            offset = 0
            current = []
            for record in records:
                baseline.feed(record)
                observer.receive(record)
                output = observer.out.getvalue()
                for line in output[offset:].splitlines():
                    row = json.loads(line)
                    if row['kind'] == 'transition':
                        current.append(row['evidence'])
                offset = len(output)
                self.assertEqual(current[:len(previous)], previous)
                previous = list(current)
                # External guard may conservatively block, but cannot invent a positive result.
                for event in current:
                    if event['state'] in ('POSSÍVEL_ABSORÇÃO', CONFIRMED):
                        self.assertIn(event, baseline.events)
            self.assertGreater(observer.order, 0)

    def test_stale_dom_blocks_a_later_reaction_and_never_resumes(self):
        fixture = AbsorptionTest()
        observer = Observation(io.StringIO())
        observer.detector = Detector(fixture.config())
        records = fixture.records()
        for record in records:
            observer.receive(record)
        self.assertIsNotNone(observer.detector.active)
        observer.receive(batch(3, 650, [(650, 110, 56, 1)], records[-1]['cursor_after']))
        self.assertIn('observation_dom_stale', observer.detector.faults)
        self.assertFalse(any(e['state'] == CONFIRMED for e in observer.detector.events))
        for record in fixture.records():
            observer.receive(record)
        self.assertTrue(observer.detector.faults)

    def test_disconnect_latches(self):
        observer = Observation(io.StringIO(), connected=lambda: False)
        observer.receive({'kind': 'session', 'symbol': 'WINZ26'})
        observer.connected = lambda: True
        for record in AbsorptionTest().records()[1:]:
            observer.receive(record)
        self.assertIn('observation_disconnected', observer.detector.faults)
        self.assertFalse(observer.detector.events)


if __name__ == '__main__':
    unittest.main()
