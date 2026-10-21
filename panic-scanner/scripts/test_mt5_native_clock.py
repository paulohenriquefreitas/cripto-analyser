import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from mt5_native_clock_compare import compare
from mt5_native_clock_probe import TerminalEvidence


def native(ms=1000,volume=2,last=100):
    return {'sequence':1,'symbol':'WINZ26','server':'Clear-Test','terminal_data_path':'C:\\Terminal',
        'ok':1,'time':ms//1000,'time_msc':ms,'bid':95,'ask':105,'last':last,
        'volume':volume,'volume_real':float(volume),'flags':1080,
        'time_gmt_after':10801,'time_current':1,'time_trade_server':1}


class NativeClockTest(unittest.TestCase):
    def execute(self,rows,raws,path='C:\\Terminal'):
        with tempfile.TemporaryDirectory() as d:
            n,p=Path(d)/'native.tsv',Path(d)/'python.json'
            with n.open('w',encoding='utf-8',newline='') as f:
                writer=csv.DictWriter(f,fieldnames=list(rows[0]),delimiter='\t')
                writer.writeheader();writer.writerows(rows)
            report={'symbol':'WINZ26','mt5':{'server':'Clear-Test'},
                'native_comparison_metadata':{'terminal':{'data_path':path}},
                'batches':[{'ticks':[{'raw':r} for r in raws]}]}
            p.write_text(json.dumps(report),encoding='utf-8')
            before={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in (n,p)}
            result=compare(n,p)
            self.assertEqual(before,result['source_sha256'])
            self.assertEqual(before,{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in (n,p)})
            return result

    def test_exact_payload_and_repeated_observations_not_deal_identity(self):
        raw=native()
        r=self.execute([raw,raw],[raw,raw])
        self.assertEqual(r['exact_payload_agreement_groups'],1)
        self.assertEqual(r['exact_matches'][0]['native_observations'],2)
        self.assertFalse(r['exact_matches'][0]['individual_deal_identity_established'])
        self.assertEqual(r['native_clock_differences']['time_current_minus_gmt_s']['median'],-10800)
        self.assertEqual(r['conclusion'],'raw_payload_agreement_observed')

    def test_different_last_tick_not_matched_by_query_proximity(self):
        r=self.execute([native()],[native(last=105)])
        self.assertEqual(r['exact_payload_agreement_groups'],0)
        self.assertEqual(r['timestamp_difference_candidates'],[])
        r=self.execute([native()],[native(volume=3)])
        self.assertEqual(r['exact_payload_agreement_groups'],0)

    def test_timestamp_mismatch_is_only_candidate_and_repeated_fingerprint_ambiguous(self):
        r=self.execute([native()],[native(ms=10801000)])
        self.assertEqual(r['exact_payload_agreement_groups'],0)
        self.assertEqual(r['timestamp_difference_candidates'][0]['python_minus_native_ms'],10800000)
        self.assertFalse(r['timestamp_difference_candidates'][0]['same_tick_established'])
        r=self.execute([native(),native(ms=2000)],[native(ms=10801000)])
        self.assertEqual(r['timestamp_difference_candidates'],[])

    def test_terminal_mismatch_and_contract(self):
        r=self.execute([native()],[native()],path='C:\\Other')
        self.assertFalse(r['same_terminal_and_server_evidence'])
        self.assertEqual(r['conclusion'],'insufficient_verified_overlap')
        row=native();row['symbol']='WINV26'
        with self.assertRaises(ValueError):self.execute([row],[native()])

    def test_proxy_uses_one_connection_and_records_metadata(self):
        api=Mock()
        api.initialize.return_value=True
        api.terminal_info.return_value={'data_path':'C:\\Terminal'}
        api.version.return_value=(500,1,'fixture')
        proxy=TerminalEvidence(api)
        self.assertTrue(proxy.initialize(timeout=3000))
        proxy.shutdown()
        api.initialize.assert_called_once_with(timeout=3000)
        api.shutdown.assert_called_once()
        self.assertEqual(proxy.metadata['terminal']['data_path'],'C:\\Terminal')
        api.symbol_select.assert_not_called()


if __name__=='__main__':unittest.main()
