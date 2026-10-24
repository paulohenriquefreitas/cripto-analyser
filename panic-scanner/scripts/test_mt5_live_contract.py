import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from mt5_live_symbol import configured_symbol, validate_live_symbol, validate_live_tick
from mt5_trade_stream import aggressor_side, configured_resume, emit, StreamCounters, SYMBOL

D = dict(BID=2, ASK=4, LAST=8, VOLUME=16, BUY=32, SELL=64)


class LiveContractTest(unittest.TestCase):
    def test_allowlist_and_exclusive_direction(self):
        for flags, side in [(56,'BUY'),(88,'SELL'),(1080,'BUY'),(1112,'SELL'),(1144,'AMBIGUOUS'),
                            (1336,'BUY'),(1368,'SELL'),(120,'AMBIGUOUS'),(24,'AMBIGUOUS'),
                            (32,'AMBIGUOUS'),(1026,'AMBIGUOUS'),(-1,'AMBIGUOUS'),
                            (56|128,'AMBIGUOUS'),(56|256,'AMBIGUOUS'),(56|2048,'AMBIGUOUS'),
                            (1080|2048,'AMBIGUOUS')]:
            with self.subTest(flags=flags):self.assertEqual(side,aggressor_side(flags,D))

    def api(self):
        api=Mock();api.terminal_info.return_value=SimpleNamespace(connected=True)
        api.symbol_select.return_value=True
        api.symbol_info.return_value=SimpleNamespace(name='WINZ26',custom=False,expiration_time=2000,trade_mode=4)
        return api

    def test_validation_is_fail_closed_before_capture(self):
        api=self.api();self.assertEqual('WINZ26',validate_live_symbol(api,'WINZ26',lambda:1000).name)
        for field,value in [('expiration_time',999),('expiration_time',float('nan')),('custom',True),('name','WINV26'),('trade_mode',0)]:
            api=self.api();setattr(api.symbol_info.return_value,field,value)
            with self.subTest(field=field,value=value),self.assertRaises(RuntimeError):validate_live_symbol(api,'WINZ26',lambda:1000)
        api=self.api();api.symbol_select.return_value=False
        with self.assertRaises(RuntimeError):validate_live_symbol(api,'WINZ26',lambda:1000)
        api=self.api();api.terminal_info.return_value.connected=False
        with self.assertRaises(RuntimeError):validate_live_symbol(api,'WINZ26',lambda:1000)
        api.symbol_select.assert_not_called()

    def test_shared_default_and_environment_selection(self):
        with patch.dict('os.environ',{},clear=True):self.assertEqual('WINZ26',configured_symbol())
        with patch.dict('os.environ',{'MT5_SYMBOL':' winv26 '}):self.assertEqual('WINV26',configured_symbol())
        with patch.dict('os.environ',{'MT5_SYMBOL':'WIN$N'}),self.assertRaises(ValueError):configured_symbol()

    def test_initial_tick_must_exist_and_have_valid_price_and_time(self):
        validate_live_tick(SimpleNamespace(time_msc=1,last=100),'WINZ26')
        for tick in [None,SimpleNamespace(time_msc=0,last=100),SimpleNamespace(time_msc=1,last=0),
                     SimpleNamespace(time_msc=1,last=float('nan'))]:
            with self.subTest(tick=tick),self.assertRaises(RuntimeError):validate_live_tick(tick,'WINZ26')

    def test_contract_switch_discards_foreign_or_unbound_cursor(self):
        env=dict(MT5_TRADE_CURSOR_MSC='1234',MT5_TRADE_CURSOR_ORDINAL='2',MT5_TRADE_CURSOR_SYMBOL=SYMBOL)
        self.assertEqual(2,configured_resume(env).emitted_at_time)
        env['MT5_TRADE_CURSOR_SYMBOL']='WINV26' if SYMBOL!='WINV26' else 'WINZ26'
        self.assertIsNone(configured_resume(env))
        del env['MT5_TRADE_CURSOR_SYMBOL'];self.assertIsNone(configured_resume(env))

    def test_raw_flags_survive_protocol_and_real_sample_has_expected_sides(self):
        path=Path(__file__).resolve().parents[1]/'dom-captures/WINZ26-joint-probe-30s.ndjson'
        if not path.exists():self.skipTest('Local real capture unavailable')
        rows=[]
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            record=json.loads(line)
            if record.get('kind')=='tick_batch':rows.extend(t['raw'] for t in record['ticks'] if t['classification']=='trade')
        output=io.StringIO();emit(rows,D,StreamCounters(),output,io.StringIO())
        messages=[json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([r['flags'] for r in rows],[m['flags'] for m in messages])
        totals={s:sum(m['volume'] for m in messages if m['side']==s) for s in ['BUY','SELL','AMBIGUOUS']}
        self.assertEqual(dict(BUY=1444,SELL=3188,AMBIGUOUS=53),totals)
        self.assertEqual(566,len(messages));self.assertEqual(4685,sum(totals.values()))


if __name__=='__main__':unittest.main()
