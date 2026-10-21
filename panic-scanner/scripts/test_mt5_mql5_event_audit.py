import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from mt5_mql5_event_audit import audit, dist, markdown, main


def frame(kind,sequence,start,end):
    return {'kind':kind,'sequence':sequence,'handler_start_us':start,'handler_end_us':end,
        'query_start_us':start,'query_end_us':end,'queue_delay_us':100,'ok':True,
        'time_gmt_before':1000,'time_gmt_after':1000}


def book(sequence,start):
    row=frame('book_snapshot',sequence,start,start+100)
    levels=[{'source_index':i,'type':1,'price':105+(11-i)*5,'volume':10,'volume_dbl':10.} for i in range(12)]
    levels+=[{'source_index':i+12,'type':2,'price':100-i*5,'volume':20,'volume_dbl':20.} for i in range(12)]
    row.update(raw_levels=levels,best_ask_levels=sorted(levels[:12],key=lambda r:r['price'])[:10],best_bid_levels=levels[12:22],dom_market_time_msc=None)
    return row


def rows():
    b=frame('tick_batch',2,20000,20100)
    tick={'time':1,'time_msc':1000,'bid':95.,'ask':105.,'last':100.,'volume':2,'volume_real':2.,'flags':1080,'cursor_ordinal':1}
    b['ticks']=[tick,{**tick,'cursor_ordinal':2}]
    return [{'kind':'session','symbol':'WINZ26'},book(1,1000),b,book(3,101000),
            {'kind':'summary','raw_ticks':2,'fault':'','release_ok':True,'dropped_observations':0}]


class EventAuditTest(unittest.TestCase):
    def run_rows(self,r,**kwargs):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'native.ndjson'
            p.write_text('\n'.join(json.dumps(x) for x in r),encoding='utf-8')
            before=p.read_bytes()
            result=audit(p,**kwargs)
            self.assertEqual(p.read_bytes(),before)
            self.assertEqual(result['source_sha256'],hashlib.sha256(before).hexdigest())
            return result

    def test_full_book_top10_duplicates_and_timing(self):
        r=self.run_rows(rows())
        self.assertEqual(r['integrity_issues'],{})
        self.assertEqual(r['raw_ticks'],2)
        self.assertEqual(r['repeated_market_timestamps'],1)
        self.assertEqual(r['repeated_raw_payloads'],1)
        self.assertEqual(r['dom_cadence']['interval_ms']['median'],100)
        self.assertEqual(r['trade_ticks'],2)

    def test_wrong_top10_and_fabricated_dom_timestamp_detected(self):
        r=rows();r[1]['best_ask_levels']=r[1]['raw_levels'][2:12]
        r[1]['dom_market_time_msc']=1000
        result=self.run_rows(r)
        self.assertIn('incorrect_top10_selection',result['integrity_issues'])
        self.assertIn('unexpected_dom_market_timestamp',result['integrity_issues'])

    def test_cursor_duplicates_not_ignored(self):
        r=rows();r[2]['ticks'][1]['cursor_ordinal']=1
        result=self.run_rows(r)
        self.assertIn('raw_tick_cursor_not_increasing',result['integrity_issues'])
        self.assertEqual(result['raw_ticks'],2)

    def test_empty_failed_gap_and_missing_trailer(self):
        r=rows();r[1].update(raw_levels=[],best_ask_levels=[],best_bid_levels=[])
        r[3].update(ok=False,raw_levels=[],best_ask_levels=[],best_bid_levels=[],query_start_us=1001000)
        result=self.run_rows(r[:-1])
        self.assertEqual(result['dom_empty'],1)
        self.assertEqual(result['dom_failed'],1)
        self.assertEqual(result['dom_cadence']['gaps_over_500ms'],1)
        self.assertIn('missing_summary',result['integrity_issues'])

    def test_overflow_sequence_gap_and_invalid_batch_evidence(self):
        r=rows();r[-1]['fault']='OBSERVATION_QUEUE_OVERFLOW';r[-1]['dropped_observations']=1
        r[3]['sequence']=4;r[2]['ok']=False
        result=self.run_rows(r)
        self.assertEqual(result['raw_ticks'],0)
        self.assertEqual(result['query_errors'],1)
        self.assertIn('summary_reports_incomplete_capture',result['integrity_issues'])
        self.assertIn('observation_sequence_gap_or_order',result['integrity_issues'])

    def test_other_session_never_compared_event_by_event(self):
        with tempfile.TemporaryDirectory() as d:
            n,p=Path(d)/'native.ndjson',Path(d)/'python.ndjson'
            n.write_text('\n'.join(json.dumps(r) for r in rows()),encoding='utf-8')
            p.write_text(json.dumps({'kind':'tick_batch','ticks':[{'raw':rows()[2]['ticks'][0]}]}),encoding='utf-8')
            baseline={'dom':{'first_utc':'2026-10-15T20:00:00+00:00','last_utc':'2026-10-15T20:00:30+00:00','start_intervals_ms':{}},
                      'ticks':{'start_intervals_ms':{},'trade_ticks':1}}
            with patch('mt5_mql5_event_audit.python_audit',return_value=baseline):
                r=audit(n,p)
            c=r['python_comparison']
            self.assertFalse(c['potential_local_overlap_seconds_precision'])
            self.assertFalse(c['same_session_established'])
            self.assertIsNone(c['exact_payload_multiset_overlap'])

    def test_parent_timer_span_does_not_invent_exchange_order(self):
        r=rows();r.insert(-1,frame('timer_handler',4,19000,102000))
        result=self.run_rows(r)
        self.assertEqual(result['integrity_issues'],{})
        self.assertEqual(result['handler_metrics']['timer_handler']['handler_ms']['max'],83)


def complete_rows():
    r=rows()
    r[0].update(cursor_seed_ms=1000,copy_ticks_batch_limit=2048,queue_capacity=1024,requested_seconds=1)
    r[1]['trigger']='OnBookEvent'
    r[3]['trigger']='OnBookEvent'
    r[2].update(rows_are_new=True,returned_rows=2,
        cursor_before={'time_msc':1000,'ordinal':0},cursor_after={'time_msc':1000,'ordinal':2})
    r[-1].update(peak_queue=8,completed_window=True,no_final_tick_drain=True,
                start_us=1000,end_us=1001000)
    return r


class QualityAuditTest(unittest.TestCase):
    run_rows=EventAuditTest.run_rows
    def test_p99_and_empty_distribution(self):
        d=dist(range(1,101))
        self.assertEqual((d['median'],d['p90'],d['p99'],d['max']),(50.5,90,99,100))
        self.assertIsNone(dist([])['p99'])

    def test_existing_cursor_fields_verified_and_repeats_kept(self):
        r=self.run_rows(complete_rows())
        self.assertEqual(r['cursor_continuity']['verifiable_batches'],1)
        self.assertEqual(r['cursor_continuity']['batches_missing_fields'],0)
        self.assertTrue(r['cursor_continuity']['checks'][0]['verified'])
        self.assertEqual(r['repeated_raw_payloads'],1)
        self.assertEqual(r['dom_event_records'],2)
        self.assertEqual(r['queue']['peak_observed_in_summary'],8)
        self.assertIsNone(r['queue']['saturation_event_count'])
        self.assertIsNone(r['queue']['occupancy_time_series'])

    def test_cursor_before_after_and_ordinal_gaps(self):
        for field,value in [('cursor_before',{'time_msc':1000,'ordinal':3}),
                            ('cursor_after',{'time_msc':1000,'ordinal':8})]:
            r=complete_rows();r[2][field]=value
            report=self.run_rows(r)
            self.assertFalse(report['cursor_continuity']['checks'][0]['verified'])
            self.assertEqual(report['quality']['classification'],'INCOMPLETO')
        r=complete_rows();r[2]['ticks'][1]['cursor_ordinal']=3
        report=self.run_rows(r)
        self.assertIn('ordinal_or_time_discontinuity',report['cursor_continuity']['checks'][0]['problems'])

    def test_error_code_overrides_inconsistent_success_flag(self):
        raw=complete_rows();raw[2]['error']=4403
        r=self.run_rows(raw)
        self.assertEqual(r['raw_ticks'],0)
        self.assertEqual(r['query_errors'],1)
        self.assertEqual(r['record_errors'][0]['error'],4403)
        self.assertEqual(r['quality']['classification'],'INCOMPLETO')

    def test_missing_fields_are_unknown_not_zero(self):
        r=self.run_rows(rows())
        self.assertIsNone(r['queue']['peak_observed_in_summary'])
        self.assertIsNone(r['queue']['capacity_reached'])
        self.assertEqual(r['cursor_continuity']['batches_missing_fields'],1)
        self.assertIsNone(r['tail']['scheduled_end_us'])
        self.assertEqual(r['quality']['classification'],'INDETERMINADO')

    def test_long_copyticks_is_not_claimed_network_latency(self):
        r=complete_rows()
        r[2]['query_end_us']=220000;r[2]['handler_end_us']=220100
        report=self.run_rows(r)
        self.assertEqual(len(report['copy_ticks_potentially_blocking']['calls']),1)
        self.assertEqual(report['copy_ticks_potentially_blocking']['calls'][0]['duration_ms'],200)
        self.assertIn('long_synchronous_query',report['segments'][0]['reasons'])

    def test_final_tail_and_full_batch_not_invented_missing_ticks(self):
        r=self.run_rows(complete_rows())
        self.assertEqual(r['tail']['classification'],'INDETERMINADO')
        self.assertIsNone(r['tail']['missing_market_events'])
        self.assertAlmostEqual(r['tail']['tick_query_to_scheduled_end_ms'],980.9)
        raw=complete_rows();raw[2]['returned_rows']=2048
        r=self.run_rows(raw)
        self.assertEqual(r['tail']['classification'],'INDETERMINADO')
        self.assertTrue(r['tail']['last_batch_full'])
        self.assertIsNone(r['tail']['missing_market_events'])

    def test_truncated_ndjson_report_without_source_rewrite(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'cut.ndjson'
            p.write_text('\n'.join(json.dumps(r) for r in complete_rows()[:-1])+'\n{"kind":"sum',encoding='utf-8')
            before=p.read_bytes();r=audit(p)
            self.assertEqual(before,p.read_bytes())
            self.assertEqual(len(r['parse_errors']),1)
            self.assertTrue(r['parse_errors'][0]['unterminated_line'])
            self.assertEqual(r['quality']['classification'],'INCOMPLETO')

    def test_segment_usable_does_not_certify_final_tail(self):
        r=complete_rows();r[-1]['end_us']=150000;r[0]['requested_seconds']=.149
        r[3]['query_start_us']=140000;r[3]['query_end_us']=140100
        r[3]['handler_start_us']=140000;r[3]['handler_end_us']=140100
        report=self.run_rows(r,segment_seconds=.1)
        self.assertEqual(report['segments'][0]['classification'],'UTILIZAVEL')
        self.assertEqual(report['segments'][-1]['classification'],'INDETERMINADO')
        self.assertEqual(report['quality']['classification'],'INDETERMINADO')
        self.assertIn('not validate',report['limitations'][0])

    def test_queue_drop_is_not_localized_to_guessed_event(self):
        r=complete_rows();r[-1]['dropped_observations']=3;r[-1]['fault']='OBSERVATION_QUEUE_OVERFLOW'
        report=self.run_rows(r)
        self.assertEqual(report['queue']['dropped_observations_reported'],3)
        self.assertTrue(report['queue']['overflow_reported'])
        self.assertIsNone(report['queue']['saturation_event_count'])
        self.assertIn('unlocated_integrity_or_drop_evidence',report['segments'][0]['reasons'])

    def test_cli_writes_json_and_markdown_without_collecting(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p=root/'capture.ndjson';out=root/'report.json'
            p.write_text('\n'.join(json.dumps(r) for r in complete_rows()),encoding='utf-8')
            with patch('sys.argv',['audit',str(p),'--output',str(out)]),patch('sys.stdout'):main()
            self.assertTrue(out.exists());self.assertTrue(out.with_suffix('.md').exists())
            report=json.loads(out.read_text(encoding='utf-8'))
            self.assertEqual(report['report_version'],2)
            self.assertIn('P99',out.with_suffix('.md').read_text(encoding='utf-8'))
            with patch('sys.argv',['audit',str(p),'--output',str(p)]),patch('sys.stderr'):
                with self.assertRaises(SystemExit):main()


if __name__=='__main__':unittest.main()
