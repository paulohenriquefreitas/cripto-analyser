import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ml_baselines", Path(__file__).with_name("ml-baselines.py"))
ml = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ml)


class BaselineSafetyTests(unittest.TestCase):
    def rows(self, lines):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.csv"
            path.write_text("symbol,session_date_utc,temporal_split,bucket_start_msc,label_direction\n" + lines, encoding="utf8")
            return list(ml.sealed_rows(path))

    def test_september_is_not_parsed_even_with_poisoned_payload(self):
        rows = self.rows("WIN$N,2026-08-01,VALIDATION,1,FLAT\nWIN$N,2026-09-01,TEST,INVALID,DO_NOT_READ\n")
        self.assertEqual(len(rows), 1)

    def test_duplicate_and_wrong_partition_fail(self):
        with self.assertRaises(AssertionError):
            self.rows("WIN$N,2026-08-01,VALIDATION,1,FLAT\nWIN$N,2026-08-01,VALIDATION,1,FLAT\n")
        with self.assertRaises(AssertionError):
            self.rows("WIN$N,2026-08-01,DISCOVERY,1,FLAT\n")

    def test_predictors_exclude_labels_identity_and_exact_redundancy(self):
        self.assertEqual(len(ml.FEATURES), 129)
        self.assertFalse(any(f.startswith("label_") or "close_ema" in f for f in ml.FEATURES))
        self.assertFalse(set(ml.FEATURES) & {"temporal_split", "session_date_utc", "timestamp_utc", "open", "high", "low", "close", "atr14"})

    def test_scaler_fit_only_on_discovery_then_transform_validation(self):
        import numpy as np
        from sklearn.preprocessing import StandardScaler
        discovery = np.array([[0., 1.], [2., 3.]])
        validation = np.array([[100., 200.]])
        scaler = StandardScaler().fit(discovery)
        before = scaler.mean_.copy()
        scaler.transform(validation)
        np.testing.assert_array_equal(scaler.mean_, before)
        np.testing.assert_array_equal(scaler.mean_, [1., 2.])


if __name__ == "__main__":
    unittest.main()
