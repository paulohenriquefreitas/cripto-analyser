from copy import deepcopy
import io
import json
import unittest
from unittest.mock import patch

from observe_absorption import Observation
from replay_observation import verify
from test_experimental_absorption import book, batch, utc
import test_mt5_dom_trades_probe as probe_tests
from mt5_dom_trades_probe import run


class ReproducibleObservationTest(unittest.TestCase):
    def recording(self):
        out = io.StringIO()
        observer = Observation(out, record_inputs=True, connected=lambda: True)
        observer.receive(dict(kind='session', symbol='WINZ26'))
        cursor = None
        for i in range(13):
            observer.receive(book(i+1, i*100))
            # Equal timestamps and exact duplicate trades must survive recording.
            price = 110 if i == 12 else 100
            record = batch(i+1, i*100+30, [(i*100+30,price,88,30)]*2, cursor)
            cursor = record['cursor_after']
            observer.receive(record)
        observer.receive(book(14,1300,empty=True))
        observer.receive(dict(kind='tick_query_error', completed_utc=utc(1320), error='persistent'))
        observer.receive(dict(kind='summary', ended_utc=utc(1340), errors=['persistent']))
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def test_exact_transitions_and_block_points(self):
        records = self.recording()
        result = verify(records)
        self.assertTrue(result['equivalent'], result)
        self.assertGreater(result['transitions'], 0)
        self.assertGreater(result['events'], 0)
        self.assertEqual(result['first_block_order'], 28)
        self.assertEqual(result['inputs'], 30)
        saved = next(r['record'] for r in records if r['kind']=='detector_input' and r['record']['kind']=='tick_batch')
        self.assertEqual(len(saved['ticks']), 2)
        self.assertEqual(saved['ticks'][0], saved['ticks'][1])

    def test_transition_tampering_detected(self):
        for field in ('state', 'order', 'evidence'):
            records = self.recording()
            transition = next(r for r in records if r['kind']=='transition')
            transition[field] = 'changed' if field != 'evidence' else dict(transition[field], reason='changed')
            self.assertFalse(verify(records)['equivalent'])

    def test_block_tampering_detected(self):
        records = self.recording()
        next(r for r in records if r['kind']=='observation' and r['blocked'])['blocked'] = False
        self.assertFalse(verify(records)['equivalent'])

    def test_missing_inputs_order_changes_and_truncation_rejected(self):
        records = self.recording()
        with self.assertRaisesRegex(ValueError, 'No detector_input'):
            verify(r for r in records if r['kind']!='detector_input')
        changed = deepcopy(records)
        next(r for r in changed if r['kind']=='detector_input')['order'] = 99
        with self.assertRaisesRegex(ValueError, 'discontinuity'):
            verify(changed)
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            verify(r for r in records if not (r['kind']=='detector_input' and r['record']['kind']=='summary'))

    def test_optional_off(self):
        out = io.StringIO()
        Observation(out).receive(dict(kind='session',symbol='WINZ26'))
        self.assertFalse(any(json.loads(line)['kind']=='detector_input' for line in out.getvalue().splitlines()))

    def test_live_connection_and_delivery_faults_use_recorded_context(self):
        out = io.StringIO()
        observer = Observation(out, live=True, connected=lambda: False, record_inputs=True)
        with patch('observe_absorption.time.monotonic', return_value=2):
            observer.receive(dict(book(1,0),monotonic_start=0,monotonic_end=.001))
        observer.receive(dict(kind='summary',ended_utc=utc(10),errors=[]))
        result = verify(json.loads(line) for line in out.getvalue().splitlines())
        self.assertTrue(result['equivalent'])
        self.assertIn('observation_delivery_gap',result['faults'])
        self.assertIn('observation_disconnected',result['faults'])

    def test_retry_rejected_responses_are_not_detector_inputs(self):
        fixture = probe_tests.JointProbeTest()
        api = fixture.api()
        original = fixture.rows
        replies = iter([original, [], original])
        api.copy_ticks_from.side_effect = lambda *args: next(replies, original)
        out = io.StringIO()
        observer = Observation(out, record_inputs=True)
        clock = probe_tests.Clock()
        summary = run(api,None,.05,clock,clock.sleep,on_record=observer.receive)
        records = [json.loads(line) for line in out.getvalue().splitlines()]
        inputs = [r['record'] for r in records if r['kind']=='detector_input']
        batches = [r for r in inputs if r['kind']=='tick_batch']
        self.assertEqual(len(batches),summary['tick_queries'])
        self.assertGreater(api.copy_ticks_from.call_count, len(batches))
        self.assertEqual(sum(len(b['ticks']) for b in batches),len(original))
        self.assertTrue(verify(records)['equivalent'])


if __name__ == '__main__':
    unittest.main()
