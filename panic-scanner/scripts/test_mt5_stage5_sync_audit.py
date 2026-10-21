import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from mt5_stage5_sync_audit import scenario, build_report


def dom(seq,start,end,valid=True):
    return {'sequence':seq,'start_ms':start,'end_ms':end,'valid':valid}


def trade(index,t,available):
    return {'trade_index':index,'market_time_msc':t,'available_local_ms':available}


class Stage5Test(unittest.TestCase):
    def test_nearest_is_future_but_asof_only_prior(self):
        ds=[dom(1,0,1),dom(2,100,101)]
        ts=[trade(1,90,95)]
        r=scenario(ts,ds,[],0,(25,100))
        m=r['tolerances']['25']
        self.assertEqual(m['events_with_proximity_candidate'],1)
        self.assertEqual(m['nearest_uses_future_at_event'],1)
        self.assertEqual(m['nearest_unavailable_at_trade_receipt'],1)
        self.assertEqual(m['asof_eligible_count'],0)
        self.assertEqual(r['tolerances']['100']['asof_eligible_count'],1)

    def test_ambiguous_tolerance_and_repeated_events_preserved(self):
        ts=[trade(1,50,60),trade(2,50,60)]
        r=scenario(ts,[dom(1,0,1),dom(2,100,101)],[],0,(25,50))
        self.assertEqual(r['tolerances']['25']['proximity_counts'],{'none':2})
        self.assertEqual(r['tolerances']['50']['proximity_counts'],{'ambiguous':2})
        self.assertEqual(len(r['normalized_events']),2)
        self.assertEqual(ts[0]['market_time_msc'],50)

    def test_unknown_dom_breaks_asof(self):
        r=scenario([trade(1,150,160)],[dom(1,0,1),dom(2,100,101,False)],[],0,(250,))
        self.assertEqual(r['tolerances']['250']['asof_eligible_count'],0)

    def test_scenario_offset_does_not_change_original(self):
        ts=[trade(1,-10799950,60)]
        r=scenario(ts,[dom(1,0,1),dom(2,100,101)],[],10800000,(100,))
        self.assertEqual(r['normalized_events'][0]['experimental_normalized_ms'],50)
        self.assertEqual(ts[0]['market_time_msc'],-10799950)
        self.assertEqual(r['offset_status'],'hypothesis_only')

    def test_clock_evidence_not_transferred_between_sessions(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            py={'windows_clock':{'utc':'2026-10-16T14:00:00+00:00'},'finished_clock':{'utc':'2026-10-16T14:00:10+00:00'},
                'batches':[{'ticks':[], 'query_start':{'utc':'2026-10-16T14:00:00+00:00'},'query_end':{'utc':'2026-10-16T14:00:10+00:00'}}]}
            (root/'WINZ26-python-native-check.json').write_text(json.dumps(py))
            native=root/'native.tsv'
            native.write_text('sequence\tok\ttime_msc\ttime_gmt_before\ttime_gmt_after\n1\t1\t1792148400000\t1792159200\t1792159210\n')
            joint=root/'joint.ndjson';joint.write_text(json.dumps({'kind':'session'})+'\n'+json.dumps({'kind':'tick_batch'})+'\n')
            comparison={'same_terminal_and_server_evidence':True,
                        'native_clock_differences':{'time_current_minus_gmt_s':{'median':-10800}}}
            session={'session_id':'joint.ndjson','scenarios':{'C':{'status':'unavailable_same_session_clock_evidence'}}}
            before={p.name:p.read_bytes() for p in root.iterdir()}
            with patch('mt5_stage5_sync_audit.native_compare',return_value=comparison),patch('mt5_stage5_sync_audit.joint_session',return_value=session):
                r=build_report(root,native)
            self.assertEqual(r['conclusion'],'INDETERMINADO')
            self.assertFalse(r['native_python_session']['clock_scenario_C']['transfer_to_other_sessions'])
            self.assertEqual(r['native_python_session']['clock_scenario_C']['estimated_offset_ms'],10800000)
            self.assertEqual(r['joint_sessions'][0]['scenarios']['C']['status'],'unavailable_same_session_clock_evidence')
            self.assertEqual(before,{p.name:p.read_bytes() for p in root.iterdir()})


if __name__=='__main__':unittest.main()
