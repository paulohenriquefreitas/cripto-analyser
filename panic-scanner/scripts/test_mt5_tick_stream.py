"""Deterministic tests: no MT5 import, sleeps, network or terminal required."""
import contextlib
import io
import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from mt5_tick_stream import POLL_SECONDS, stream_ticks


def tick(ms):
    return SimpleNamespace(time=1789991940, time_msc=ms, bid=187635,
                           ask=187640, last=187640, volume=4)


class StreamTests(unittest.TestCase):
    def test_new_milliseconds_same_price_and_duplicates(self):
        mt5 = Mock()
        mt5.initialize.return_value = True
        mt5.symbol_info_tick.side_effect = [tick(1789991940389), tick(1789991940389), tick(1789991940520)]
        stop = Mock()
        stop.is_set.side_effect = [False, False, False, True]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            stream_ticks(mt5, stop)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([r['timeMsc'] for r in rows], [1789991940389, 1789991940520])
        self.assertEqual(rows[0]['last'], rows[1]['last'])
        self.assertEqual(rows[0]['time'], 1789991940)
        self.assertEqual(stop.wait.call_count, 3)
        stop.wait.assert_called_with(POLL_SECONDS)
        mt5.initialize.assert_called_once()
        mt5.shutdown.assert_called_once()

    def test_initialization_failure_shuts_down_without_stdout(self):
        mt5 = Mock()
        mt5.initialize.return_value = False
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaisesRegex(RuntimeError, 'initialize'):
            stream_ticks(mt5, Mock())
        self.assertEqual(output.getvalue(), '')
        mt5.shutdown.assert_called_once()

    def test_missing_tick_shuts_down(self):
        mt5 = Mock()
        mt5.initialize.return_value = True
        mt5.symbol_info_tick.return_value = None
        stop = Mock()
        stop.is_set.return_value = False
        with self.assertRaisesRegex(RuntimeError, 'Sem tick'):
            stream_ticks(mt5, stop)
        mt5.shutdown.assert_called_once()

    def test_flushes_each_tick(self):
        mt5 = Mock()
        mt5.initialize.return_value = True
        mt5.symbol_info_tick.return_value = tick(1789991940389)
        stop = Mock()
        stop.is_set.side_effect = [False, True]
        output = Mock()
        with contextlib.redirect_stdout(output):
            stream_ticks(mt5, stop)
        output.flush.assert_called_once()


if __name__ == '__main__':
    unittest.main()
