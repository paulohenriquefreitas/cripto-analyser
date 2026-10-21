import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from mt5_dom_temporal_audit import analyze_file, association


def snapshot(sequence,start,empty=False):
    return {'kind':'snapshot','symbol':'WINZ26','sequence':sequence,
        'captured_utc':f'2026-10-15T00:00:00.{start:03d}+00:00',
        'completed_utc':f'2026-10-15T00:00:00.{start+1:03d}+00:00',
        'monotonic_start':start/1000,'monotonic_end':(start+1)/1000,
        'levels':[] if empty else [{'type':1,'price':105,'volume':10,'volume_dbl':10.},
                                  {'type':2,'price':100,'volume':10,'volume_dbl':10.}], 'error':None}


def batch(sequence,start,flags=1080):
    market=1792022400000+start-10
    raw={'time':market//1000,'time_msc':market,'flags':flags}
    return {'kind':'tick_batch','symbol':'WINZ26','sequence':sequence,
        'captured_utc':f'2026-10-15T00:00:00.{start:03d}+00:00',
        'completed_utc':f'2026-10-15T00:00:00.{start+1:03d}+00:00',
        'monotonic_start':start/1000,'monotonic_end':(start+1)/1000,
        'ticks':[{'raw':raw},{'raw':dict(raw)}]}


class AuditTest(unittest.TestCase):
    def analyze(self,records):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'joint.ndjson'
            p.write_text('\n'.join(json.dumps(r) for r in [{'kind':'session','symbol':'WINZ26'}]+records),encoding='utf-8')
            before=p.read_bytes()
            result=analyze_file(p)
            self.assertEqual(p.read_bytes(),before)
            self.assertEqual(result['source_sha256'],hashlib.sha256(before).hexdigest())
            return result

    def test_timing_duplicates_flags_and_unknown_clock(self):
        r=self.analyze([snapshot(1,0),batch(1,50),snapshot(2,100),batch(2,150,1112),snapshot(3,200)])
        self.assertAlmostEqual(r['dom']['start_intervals_ms']['median'],100)
        self.assertEqual(r['trade_market_intervals_ms']['min'],0)
        self.assertEqual(r['ticks']['trade_sides'],{'BUY':2,'SELL':2})
        self.assertEqual(r['query_start_minus_market_ms']['median'],10)
        self.assertEqual(r['query_end_minus_market_ms']['median'],11)
        self.assertEqual(r['strict_association']['indeterminate'],4)
        self.assertEqual(r['conditional_association_scenarios'][0]['counts'],{'unique_conditional':4})

    def test_empty_periods_and_gaps(self):
        r=self.analyze([snapshot(1,0),snapshot(2,100,True),batch(1,150),snapshot(3,200),snapshot(4,900)])
        self.assertEqual(r['dom']['gaps_over_max_gap'],1)
        self.assertTrue(all(not x['usable'] for x in r['dom_intervals']))
        self.assertEqual(r['conditional_association_scenarios'][0]['counts'],{'unknown_dom_period':2})

    def test_association_uncertainty_boundaries_and_offset(self):
        intervals=[{'possible_start_ms':0,'possible_end_ms':101,'safe_start_ms':1,'safe_end_ms':100,'usable':True},
                   {'possible_start_ms':100,'possible_end_ms':201,'safe_start_ms':101,'safe_end_ms':200,'usable':True}]
        self.assertEqual(association(50,intervals,0,10)['status'],'unique_conditional')
        self.assertEqual(association(100,intervals,0,0)['status'],'indeterminate_boundary_or_uncertainty')
        self.assertEqual(association(50,intervals,0,100)['status'],'indeterminate_boundary_or_uncertainty')
        self.assertEqual(association(-1000,intervals,0,0)['status'],'outside_dom_interval_coverage')
        self.assertEqual(association(-1000,intervals,1050,0)['status'],'unique_conditional')

    def test_quotes_excluded_and_ambiguous_retained(self):
        r=self.analyze([snapshot(1,0),batch(1,50,1026),batch(2,70,1144),snapshot(2,100)])
        self.assertEqual(r['ticks']['quote_ticks'],2)
        self.assertEqual(r['ticks']['trade_sides'],{'AMBIGUOUS':2})
        self.assertEqual(r['ticks']['trade_ticks'],2)

    def test_invalid_order_symbol_sequence(self):
        with self.assertRaises(ValueError): self.analyze([snapshot(2,0)])
        bad=snapshot(1,0);bad['symbol']='WINV26'
        with self.assertRaises(ValueError): self.analyze([bad])
        with self.assertRaises(ValueError): self.analyze([snapshot(1,100),batch(1,50)])

    def test_empty_capture_no_fabrication(self):
        r=self.analyze([snapshot(1,0,True)])
        self.assertEqual(r['strict_association']['indeterminate'],0)
        self.assertIsNone(r['trade_market_intervals_ms']['median'])


if __name__=='__main__': unittest.main()
