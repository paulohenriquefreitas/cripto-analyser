import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import replay_m5_absorption as replay


def candle(i, high, low=100, close=None):
    close = (high+low)/2 if close is None else close
    return dict(time=i*300, open=close, high=high, low=low, close=close, tickVolume=1, realVolume=1)


def peak():
    return [candle(0, 103, 102), candle(1, 104, 101), candle(2, 110, 95),
            candle(3, 105, 100), candle(4, 106, 101), candle(5, 109, 99)]


def window(stamp, seconds=5, alert='POSSIBLE_ABSORPTION', quality='GOOD', buy=True):
    b, s = (80, 20) if buy else (20, 80)
    return dict(seconds=seconds, fromTimeMsc=stamp-seconds*1000, toTimeMsc=stamp,
                events=10, buyVolume=b, sellVolume=s, unknownVolume=0, delta=b-s,
                buyPercent=b, sellPercent=s, unknownPercent=0, lastDisplacement=0,
                quality=quality, alert=alert)


def item(stamp, seq=1):
    return dict(sequence=seq, time_msc=stamp, elapsed=seq, received_utc='2026-10-23T13:00:00+00:00',
                clock_consistent=True,
                value=dict(last=108, lastTimeMsc=stamp, volumeSource='MT5_VOLUME_REAL',
                           windows=[window(stamp, s) for s in (5, 10, 30)]))


def tick(stamp, price=110, seq=2):
    return dict(sequence=seq, time_msc=stamp, clock_consistent=True, value=dict(last=price))


class PivotTests(unittest.TestCase):
    def test_two_closed_bars_each_side_and_exact_confirmation_time(self):
        tracker = replay.PivotReplay(peak())
        tracker.advance(1_499_999)
        self.assertEqual(tracker.levels, [])
        tracker.advance(1_500_000)
        self.assertEqual([(p.kind, p.pivot_time, p.price, p.confirmed_at) for p in tracker.levels],
                         [('HIGH', 600, 110, 1500), ('LOW', 600, 95, 1500)])

    def test_last_historical_candle_is_forming_even_with_late_replay_time(self):
        tracker = replay.PivotReplay(peak()[:5])
        tracker.advance(99_000_000)
        self.assertEqual(tracker.levels, [])

    def test_equal_extreme_on_any_neighbor_rejects_pivot(self):
        for index in (0, 1, 3, 4):
            with self.subTest(index=index):
                bars = peak()
                bars[index].update(high=110, low=95)
                tracker = replay.PivotReplay(bars)
                tracker.advance(1_500_000)
                self.assertEqual(tracker.levels, [])

    def test_future_candle_prices_cannot_change_prior_state(self):
        bars = peak()+[candle(6, 120, 90), candle(7, 125, 85)]
        modified = copy.deepcopy(bars)
        for c in modified[5:]:
            c.update(high=10000, low=1, close=9000)
        a, b = replay.PivotReplay(bars), replay.PivotReplay(modified)
        a.advance(1_500_000)
        b.advance(1_500_000)
        self.assertEqual(a.levels, b.levels)
        self.assertIsNone(a.nearest(110).broken_at)

    def test_chart_break_rules_wick_equality_and_closed_break(self):
        bars = peak()
        bars[5] = candle(5, 120, 90, 110)  # Wick breaks both; close equals high level.
        bars += [candle(6, 120, 90, 111), candle(7, 120, 90, 94), candle(8, 120, 90, 100)]
        tracker = replay.PivotReplay(bars)
        tracker.advance(1_800_000)
        original = [p for p in tracker.levels if p.pivot_time == 600]
        self.assertTrue(all(p.broken_at is None for p in original))
        tracker.advance(2_099_999)
        self.assertIsNone(original[0].broken_at)
        tracker.advance(2_100_000)
        self.assertEqual(original[0].broken_at, 2100)
        self.assertIsNone(original[1].broken_at)
        tracker.advance(2_400_000)
        self.assertEqual(original[1].broken_at, 2400)
        self.assertIsNotNone(tracker.nearest(110, broken=True))
        self.assertTrue(all(p.broken_at is None for p in tracker.levels if p == tracker.nearest(110)))

    def test_next_candle_time_across_gap_matches_chart_and_never_early(self):
        bars = peak()
        bars[-1]['time'] = 86400
        tracker = replay.PivotReplay(bars)
        tracker.advance(86_399_999)
        self.assertEqual(tracker.levels, [])
        tracker.advance(86_400_000)
        self.assertEqual(tracker.levels[0].confirmed_at, 86400)

    def test_batch_and_incremental_replay_match(self):
        bars = [candle(i, 150+(i%5)*3, 90-i%7) for i in range(50)]
        a, b = replay.PivotReplay(bars), replay.PivotReplay(bars)
        a.advance(bars[-1]['time']*1000)
        for c in bars:
            b.advance(c['time']*1000)
        self.assertEqual(a.levels, b.levels)

    def test_port_matches_actual_typescript_on_exported_candle_boundaries(self):
        node = Path(os.environ.get('LOCALAPPDATA', '')) / 'JetBrains/IntelliJIdea2026.2/acp-agents/.runtimes/node/24.13.0/node.exe'
        executable = str(node) if node.is_file() else shutil.which('node')
        source = replay.DEFAULT_EXPORT/'candles.json'
        if not executable or not source.is_file():
            self.skipTest('Optional Node 24/export fixture not available; pure Python parity cases still run')
        candles = json.loads(source.read_bytes())
        # Every historical boundary, both just before and exactly at availability.
        times = sorted({t for c in candles for t in (c['time']*1000-1, c['time']*1000)})
        code = """
            import fs from 'node:fs';
            import {pathToFileURL} from 'node:url';
            const {candles,times,source}=JSON.parse(fs.readFileSync(0,'utf8'));
            const {createSwingPivotTracker}=await import(pathToFileURL(source).href);
            const tracker=createSwingPivotTracker();
            const result=times.map(time=>{
                tracker.setHistory(candles.filter(c=>c.time*1000<=time));
                return {confirmed:tracker.getConfirmedLevels().map(l=>({...l})),
                        active:tracker.getLevels().map(l=>`${l.type}:${l.time}`).sort()};
            });
            process.stdout.write(JSON.stringify(result));
        """
        result = subprocess.run([executable, '--input-type=module', '-e', code],
                                input=json.dumps(dict(candles=candles, times=times, source=str(replay.PIVOT_SOURCE))),
                                capture_output=True, text=True, timeout=15, check=True)
        expected = json.loads(result.stdout)
        tracker = replay.PivotReplay(candles)
        for stamp, reference in zip(times, expected):
            tracker.advance(stamp)
            confirmed = [dict(type=p.kind, time=p.pivot_time, price=p.price, confirmedAt=p.confirmed_at)
                         for p in tracker.levels]
            self.assertEqual(confirmed, reference['confirmed'], stamp)
            self.assertEqual(sorted(p.id for p in tracker.levels if p.broken_at is None), reference['active'], stamp)


class EventTests(unittest.TestCase):
    def test_overlapping_snapshots_and_windows_merge_without_changing_onset(self):
        tracker = replay.PivotReplay(peak())
        tracker.advance(1_500_000)
        episodes = replay.Episodes()
        first = item(1_510_000)
        self.assertTrue(episodes.accept('BUY_AGGRESSION', window(first['time_msc']), first, tracker))
        saved = copy.deepcopy(episodes.events[0])
        later = item(1_510_250, 2)
        later['value']['last'] = 109
        self.assertFalse(episodes.accept('BUY_AGGRESSION', window(later['time_msc']), later, tracker))
        self.assertFalse(episodes.accept('BUY_AGGRESSION', window(later['time_msc'], 10), later, tracker))
        event = episodes.events[0]
        for key in ('time_msc', 'baseline_price', 'nearest_active_id', 'nearest_active_distance', 'onset_delta'):
            self.assertEqual(event[key], saved[key])
        self.assertEqual(event['alert_window_observations'], 3)
        self.assertEqual(event['source_windows'], [5, 10])
        self.assertEqual(event['onset_delta'], 60)  # No summing overlapping deltas.
        self.assertTrue(episodes.accept('SELL_AGGRESSION', window(later['time_msc'], buy=False), later, tracker))
        distant = item(1_550_000, 3)
        self.assertTrue(episodes.accept('BUY_AGGRESSION', window(distant['time_msc']), distant, tracker))
        self.assertEqual(len(episodes.events), 3)

    def test_only_good_alerts_and_flow_integrity_are_eligible(self):
        first = item(1_510_000)
        first['value']['windows'][0]['quality'] = 'STALE'
        first['value']['windows'][1]['delta'] += 1
        first['value']['windows'][2]['alert'] = None
        data = dict(flows=[first], exclusions=[])
        events, _, exclusions, _ = replay.build_events(peak(), data)
        self.assertEqual(events, [])
        self.assertEqual({e['reason'] for e in exclusions}, {'ALERT_WITHOUT_GOOD', 'DELTA_MISMATCH'})

    def test_unconfirmed_level_cannot_be_associated_to_alert(self):
        first = item(1_499_999)
        events, _, _, _ = replay.build_events(peak(), dict(flows=[first], exclusions=[]))
        self.assertEqual(len(events), 1)
        self.assertIsNone(events[0]['nearest_active_id'])
        first = item(1_500_000)
        events, _, _, _ = replay.build_events(peak(), dict(flows=[first], exclusions=[]))
        self.assertEqual(events[0]['nearest_active_id'], 'HIGH:600')

    def test_broken_nearest_level_is_audited_but_not_used_as_active(self):
        tracker = replay.PivotReplay(peak())
        tracker.advance(1_500_000)
        tracker.levels[0].broken_at = 1501
        first = item(1_510_000)
        episodes = replay.Episodes()
        episodes.accept('BUY_AGGRESSION', window(first['time_msc']), first, tracker)
        event = episodes.events[0]
        self.assertEqual(event['nearest_broken_id'], 'HIGH:600')
        self.assertEqual(event['nearest_active_id'], 'LOW:600')

    def test_profile_issues_have_no_dependency_in_event_construction(self):
        first = item(1_510_000)
        data = dict(flows=[first], exclusions=[], volume_profile_fault='SOURCE_DISCONNECTED')
        events, _, excluded, _ = replay.build_events(peak(), data)
        self.assertEqual(len(events), 1)
        self.assertEqual(excluded, [])


class OutcomeTests(unittest.TestCase):
    def setUp(self):
        self.event = dict(event_id=1, time_msc=100000, sequence=5, baseline_price=100, direction='BUY_AGGRESSION')

    def measure(self, ticks, gaps=(), transport=()):
        return replay.outcome(self.event, 10, ticks, [t['time_msc'] for t in ticks], gaps, transport)

    def test_uses_first_future_tick_at_or_after_horizon_and_correct_sign(self):
        ticks = [tick(109999, 999, 6), tick(110020, 95, 7), tick(110040, 90, 8)]
        result = self.measure(ticks)
        self.assertEqual(result['tick_sequence'], 7)
        self.assertEqual(result['displacement'], -5)
        self.assertEqual(result['against_aggression'], 5)
        self.assertEqual(result['endpoint_delay_ms'], 20)
        self.event['direction'] = 'SELL_AGGRESSION'
        self.assertEqual(self.measure(ticks)['against_aggression'], -5)

    def test_never_uses_tick_received_before_signal_or_excessively_late(self):
        result = self.measure([tick(110000, 90, 4), tick(110100, 95, 6)])
        self.assertEqual(result['tick_sequence'], 6)
        self.assertEqual(self.measure([tick(111001, 95, 6)])['status'], 'NO_TICK_WITHIN_ENDPOINT_TOLERANCE')

    def test_no_interpolation_or_candle_fallback_on_gap_or_end_of_file(self):
        self.assertEqual(self.measure([tick(109999)])['status'], 'RIGHT_CENSORED_NO_FUTURE_TICK')
        ticks = [tick(110000, 110, 6)]
        self.assertEqual(self.measure(ticks, [dict(from_raw_msc=101000, to_raw_msc=105000)])['status'], 'TICK_GAP_IN_HORIZON')
        self.assertEqual(self.measure(ticks, transport=[6])['status'], 'TRANSPORT_GAP_IN_HORIZON')
        self.assertEqual(self.measure(ticks, [dict(from_raw_msc=90000, to_raw_msc=99000)])['status'], 'OK')

    def test_thresholds_inclusive_no_level_is_not_far(self):
        events = [dict(event_id=1, direction='BUY_AGGRESSION', nearest_active_distance=25),
                  dict(event_id=2, direction='BUY_AGGRESSION', nearest_active_distance=26),
                  dict(event_id=3, direction='BUY_AGGRESSION', nearest_active_distance=None)]
        outcomes = [dict(event_id=e['event_id'], horizon_seconds=h, status='OK', displacement=1, against_aggression=-1)
                    for e in events for h in replay.HORIZONS]
        rows, comparisons = replay.aggregate(events, outcomes)
        counts = {r['group']: r['episodes'] for r in rows if r['distance_points']==25 and r['direction']=='BUY_AGGRESSION' and r['horizon_seconds']==10}
        self.assertEqual(counts, dict(NEAR=1, FAR=1, NO_ACTIVE_LEVEL=1))
        self.assertEqual(len(comparisons), 24)


class IntegrityAndClockTests(unittest.TestCase):
    def test_180_minutes_is_checked_as_hypothesis_without_shifting(self):
        ticks, flows = [10800020, 10800100], [10800000, 10799900]
        before = ticks.copy()
        result = replay.check_alignment(ticks, flows, {180})
        self.assertEqual(result['applied_shift_ms'], 0)
        self.assertFalse(result['independently_confirmed'])
        self.assertEqual(ticks, before)
        with self.assertRaisesRegex(ValueError, 'inconsistent'):
            replay.check_alignment([20, 100], flows, {180})
        with self.assertRaises(ValueError):
            replay.check_alignment(ticks, flows, {0, 180})
        with self.assertRaises(ValueError):
            replay.utc_msc('2026-10-23T10:53:25')

    def test_candle_order_bounds_and_grid_are_revalidated(self):
        self.assertEqual(replay.validate_candles(peak()), [])
        for mutate in (lambda cs: cs[1].update(time=cs[0]['time']),
                       lambda cs: cs[1].update(close=10000),
                       lambda cs: cs[1].update(time=301),
                       lambda cs: cs[1].update(realVolume=-1)):
            cs = peak()
            mutate(cs)
            with self.assertRaises(ValueError):
                replay.validate_candles(cs)

    def test_tampered_export_is_rejected_before_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            for name in ('candles.json', 'metadata.json', 'validation.json'):
                (path/name).write_text('[]')
            (path/'observations.ndjson').write_text('{}')
            (path/'SHA256SUMS.txt').write_text('\n'.join('0'*64+'  '+name for name in ('candles.json', 'metadata.json', 'validation.json')))
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                replay.load_inputs(path, path/'observations.ndjson')

    def test_report_has_all_groups_and_is_deterministic(self):
        # Minimal synthetic provenance allows exercising output without MT5/network.
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for name in ('candles.json', 'metadata.json', 'validation.json', 'observations.ndjson'):
                (base/name).write_text('{}')
            provenance = dict(input_sha256={n: replay.sha256(base/n) for n in
                              ('candles.json', 'metadata.json', 'validation.json', 'observations.ndjson')},
                              export_edge_caveats=[])
            first = item(1_510_000)
            data = dict(flows=[first], ticks=[tick(first['time_msc']+h*1000, 110, 2) for h in replay.HORIZONS],
                        exclusions=[], gaps=[], transport_sequences=[], counts=dict(FLOW=1), qualities=dict(GOOD=3),
                        alignment=replay.check_alignment([10800000], [10800000], {180}),
                        time_basis={}, price_consistency=dict(ticks=4))
            events, pivots, excluded, counts = replay.build_events(peak(), data)
            for name in ('one', 'two'):
                replay.write_outputs(base/name, base, base/'observations.ndjson', peak(), {'symbol':'WINZ26'},
                                     provenance, data, events, pivots, excluded, counts)
            for path in (base/'one').iterdir():
                self.assertEqual(path.read_bytes(), (base/'two'/path.name).read_bytes(), path.name)
            self.assertEqual(len((base/'one/summary.csv').read_text().splitlines()), 73)


if __name__ == '__main__':
    unittest.main()
