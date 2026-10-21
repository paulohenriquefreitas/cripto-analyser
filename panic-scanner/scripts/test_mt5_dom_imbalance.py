import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from mt5_dom_imbalance import analyze_file


def book(bid=10, ask=20, count=10):
    return ([{"type": 1, "price": 105 + 5*i, "volume": ask+i, "volume_dbl": float(ask+i)}
             for i in reversed(range(count))] +
            [{"type": 2, "price": 100 - 5*i, "volume": bid+i, "volume_dbl": float(bid+i)}
             for i in range(count)])


class ImbalanceTest(unittest.TestCase):
    def run_rows(self, samples, times=None, symbol="WINZ26"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.ndjson"
            rows = [{"kind": "symbol_info", "value": {"name": symbol}}]
            for i, raw in enumerate(samples):
                millis = times[i] if times else i * 100
                rows.append({"kind": "snapshot", "sequence": i+1,
                    "captured_utc": f"2026-10-15T19:00:{millis//1000:02d}.{millis%1000:03d}+00:00",
                    "levels": raw, "error": None})
            path.write_text('\n'.join(json.dumps(r) for r in rows), encoding='utf-8')
            original = path.read_bytes()
            result = analyze_file(path)
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(result['source_sha256'], hashlib.sha256(original).hexdigest())
            return result

    def test_depths_select_best_asks_and_original_volumes(self):
        r = self.run_rows([book()])
        for n in (1,3,5,10):
            v = r['snapshots'][0]['depths'][str(n)]
            bid = n*10 + n*(n-1)/2
            ask = n*20 + n*(n-1)/2
            self.assertEqual(v['bid_volume'], bid)
            self.assertEqual(v['ask_volume'], ask)
            self.assertAlmostEqual(v['imbalance'], (bid-ask)/(bid+ask))
        self.assertEqual(r['depths']['1']['imbalance']['count'], 1)
        self.assertIsNone(r['depths']['1']['direct_sign_flip_fraction'])

    def test_sign_flips_zero_and_repeated_snapshots(self):
        r = self.run_rows([book(20,10),book(10,20),book(10,10),book(20,10),book(20,10)])
        for d in r['depths'].values():
            self.assertEqual(d['direct_sign_flips'], 1)
            self.assertEqual(d['comparable_intervals'], 4)
            self.assertEqual(d['direct_sign_flip_fraction'], .25)
            self.assertEqual(d['direct_sign_flips_per_second'], 2.5)
            self.assertEqual(d['zero_transitions'], 2)
            self.assertEqual(d['imbalance']['count'], 5)
            self.assertEqual(d['evolution_5s'][0]['imbalance']['count'], 5)

    def test_unknowns_and_gap_break_transitions(self):
        invalid = book()
        invalid[0]['volume_dbl'] = -1
        for bad in ([],None,invalid):
            r = self.run_rows([book(20,10),bad,book(10,20)])
            self.assertEqual(r['depths']['1']['direct_sign_flips'], 0)
            self.assertEqual(r['depths']['1']['comparable_intervals'], 0)
        r = self.run_rows([book(20,10),book(10,20)], [0,1000])
        self.assertEqual(r['quality']['large_gaps'], 1)
        self.assertEqual(r['depths']['1']['direct_sign_flips'], 0)

    def test_insufficient_depth_and_missing_side(self):
        one_sided = [r for r in book() if r['type']==2]
        r = self.run_rows([book(count=3),one_sided,book(count=3)])
        self.assertEqual(r['depths']['3']['imbalance']['count'], 2)
        self.assertEqual(r['depths']['3']['comparable_intervals'], 0)
        self.assertEqual(r['depths']['10']['statuses']['insufficient_levels'], 3)
        self.assertIsNone(r['snapshots'][0]['depths']['5']['bid_volume'])

    def test_zero_denominator_is_explicit_and_breaks_continuity(self):
        r = self.run_rows([book(20,10,count=1),book(0,0,count=1),book(10,20,count=1)])
        v = r['snapshots'][1]['depths']['1']
        self.assertEqual(v['status'],'zero_denominator')
        self.assertEqual(v['bid_volume'],0)
        self.assertIsNone(v['imbalance'])
        self.assertEqual(r['depths']['1']['direct_sign_flips'],0)
        one = self.run_rows([book(0,10,count=1)])
        self.assertEqual(one['snapshots'][0]['depths']['1']['imbalance'],-1)

    def test_distributions_and_temporal_bins(self):
        r = self.run_rows([book(10,10),book(20,10),book(10,20)],[0,100,5100])
        d = r['depths']['1']
        self.assertAlmostEqual(d['imbalance']['mean'],0)
        self.assertEqual(d['imbalance']['median'],0)
        self.assertAlmostEqual(d['imbalance']['p90'],1/3)
        self.assertEqual(len(d['evolution_5s']),2)

    def test_wrong_symbol_and_time_reversal_rejected(self):
        with self.assertRaises(ValueError): self.run_rows([book()],symbol='WINV26')
        with self.assertRaises(ValueError): self.run_rows([book(),book()],[100,0])


if __name__ == '__main__':
    unittest.main()
