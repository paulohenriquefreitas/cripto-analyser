import unittest
from datetime import date, datetime, timezone
from unittest.mock import MagicMock, patch
import mt5_reader


class ReaderTest(unittest.TestCase):
    def mt5(self):
        mt5 = MagicMock()
        mt5.TIMEFRAME_M5 = 5
        mt5.TIMEFRAME_D1 = 16408
        return mt5

    def row(self, day, real=1000):
        return dict(time=int(datetime(2026, 10, day, tzinfo=timezone.utc).timestamp()),
                    open=10, high=12, low=9, close=11, tick_volume=99, real_volume=real, spread=1)

    def test_legacy_m5_is_unchanged(self):
        mt5 = self.mt5()
        mt5.copy_rates_from_pos.return_value = [self.row(6)]
        with patch.dict('sys.modules', MetaTrader5=mt5):
            result = mt5_reader.read_candles()
        mt5.copy_rates_from_pos.assert_called_once_with('WINV26', 5, 0, 1000)
        mt5.symbol_select.assert_not_called()
        self.assertEqual(1000, result[0]['realVolume'])
        self.assertEqual(99, result[0]['tickVolume'])
        mt5.shutdown.assert_called_once()

    def test_d1_range_preserves_session_and_filters_extra_bars(self):
        mt5 = self.mt5()
        mt5.copy_rates_range.return_value = [self.row(4), self.row(5), self.row(6), self.row(7)]
        with patch.dict('sys.modules', MetaTrader5=mt5):
            result = mt5_reader.read_candles(symbol='PETR4', timeframe='D1',
                                           from_date=date(2026, 10, 5), to_date=date(2026, 10, 6))
        mt5.symbol_select.assert_called_once_with('PETR4', True)
        mt5.copy_rates_range.assert_called_once_with('PETR4', 16408,
            datetime(2026, 10, 5, tzinfo=timezone.utc),
            datetime(2026, 10, 6, 23, 59, 59, tzinfo=timezone.utc))
        self.assertEqual([self.row(5)['time'], self.row(6)['time']], [r['time'] for r in result])
        mt5.copy_rates_from_pos.assert_not_called()

    def test_legacy_tick(self):
        mt5 = self.mt5()
        with patch.dict('sys.modules', MetaTrader5=mt5):
            tick = mt5.symbol_info_tick.return_value
            tick.time = 1; tick.time_msc = 1000
            tick.bid = 10; tick.ask = 11; tick.last = 10; tick.volume = 2
            result = mt5_reader.read_candles(tick_only=True)
        mt5.symbol_info_tick.assert_called_once_with('WINV26')
        self.assertEqual(1000, result['time_msc'])
        mt5.copy_rates_from_pos.assert_not_called()

    def test_invalid_range(self):
        with self.assertRaises(ValueError):
            mt5_reader.read_candles(from_date=date(2026, 10, 6))
        with self.assertRaises(ValueError):
            mt5_reader.read_candles(from_date=date(2026, 10, 6), to_date=date(2026, 10, 5))

    def test_query_failure_shuts_down(self):
        mt5 = self.mt5()
        mt5.copy_rates_from_pos.return_value = None
        with patch.dict('sys.modules', MetaTrader5=mt5), self.assertRaises(RuntimeError):
            mt5_reader.read_candles()
        mt5.shutdown.assert_called_once()


if __name__ == '__main__':
    unittest.main()
