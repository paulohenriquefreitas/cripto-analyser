import io
import json
from collections import namedtuple
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from mt5_dom_trades_probe import run

Tick = namedtuple('Tick', 'time time_msc last flags volume volume_real bid ask')
Book = namedtuple('Book', 'type price volume volume_dbl')


class Clock:
    now = 0
    def __call__(self): return self.now
    def sleep(self, seconds): self.now += max(seconds, .000001)


class JointProbeTest(unittest.TestCase):
    def api(self):
        api = Mock()
        api.initialize.return_value = True
        api.account_info.return_value = SimpleNamespace(company='Clear',server='Clear-Test')
        api.symbol_info.return_value = namedtuple('Info','trade_tick_size')(5)
        api.symbol_select.return_value = True
        api.symbol_info_tick.return_value = Tick(1,1000,100,8,1,1.,95,105)
        api.market_book_add.return_value = True
        api.market_book_release.return_value = True
        api.market_book_get.return_value = (Book(1,105,10,10.),Book(2,100,20,20.))
        api.last_error.return_value = (-1,'synthetic')
        api.COPY_TICKS_ALL = -1
        for field,bit in (('BID',2),('ASK',4),('LAST',8),('VOLUME',16),('BUY',32),('SELL',64)):
            setattr(api,'TICK_FLAG_'+field,bit)
        self.rows = [dict(time=1,time_msc=1000,last=100,flags=40,volume=2,volume_real=2.,bid=95,ask=105),
                     dict(time=1,time_msc=1000,last=100,flags=40,volume=2,volume_real=2.,bid=95,ask=105),
                     dict(time=1,time_msc=1000,last=100,flags=2,volume=2,volume_real=2.,bid=95,ask=105),
                     dict(time=1,time_msc=1001,last=100,flags=80,volume=3,volume_real=3.,bid=95,ask=105)]
        api.copy_ticks_from.side_effect = lambda *args: self.rows
        return api

    def capture(self, api, seconds=.3, clock=None):
        clock = clock or Clock()
        out = io.StringIO()
        summary = run(api,out,seconds,clock,clock.sleep)
        return summary,[json.loads(line) for line in out.getvalue().splitlines()]

    def test_serial_session_polling_raw_flags_duplicates_and_cleanup(self):
        api = self.api()
        s,records = self.capture(api)
        self.assertEqual(s['status'],'both_observed')
        self.assertEqual(s['dom_queries'],3)
        self.assertIn(s['tick_queries'],(15,16))
        self.assertEqual((s['ticks'],s['trade_ticks'],s['quote_ticks']),(4,3,1))
        self.assertEqual(s['trade_sides'],{'BUY':2,'SELL':1})
        ticks=[t for b in records if b['kind']=='tick_batch' for t in b['ticks']]
        self.assertEqual(ticks[0]['raw'],ticks[1]['raw'])
        self.assertEqual(ticks[0]['raw']['flags'],40)
        self.assertEqual(ticks[2]['classification'],'quote')
        self.assertEqual(s['tick_cursor'],{'time_msc':1001,'ordinal':1})
        api.initialize.assert_called_once_with()
        api.market_book_add.assert_called_once_with('WINZ26')
        api.market_book_release.assert_called_once_with('WINZ26')
        api.shutdown.assert_called_once()
        self.assertTrue(all(c.args[0]=='WINZ26' for c in api.copy_ticks_from.call_args_list))
        self.assertRegex(records[0]['captured_utc'],r'\.\d{3}\+00:00$')

    def test_empty_and_failed_dom_stay_explicit(self):
        api=self.api()
        api.market_book_get.side_effect=[(),None,api.market_book_get.return_value]
        s,_=self.capture(api)
        self.assertEqual((s['dom_empty'],s['dom_failures'],s['dom_nonempty']),(1,1,1))
        self.assertEqual(s['status'],'incomplete')
        api.shutdown.assert_called_once()

    def test_query_error_stops_without_cursor_reset(self):
        api=self.api()
        api.copy_ticks_from.return_value=None
        api.copy_ticks_from.side_effect=None
        s,records=self.capture(api)
        self.assertEqual(s['tick_queries'],1)
        self.assertEqual(s['status'],'incomplete')
        self.assertEqual(s['tick_cursor'],{'time_msc':1000,'ordinal':0})
        self.assertTrue(any(r['kind']=='tick_query_error' for r in records))
        api.market_book_release.assert_called_once()
        api.shutdown.assert_called_once()

    def test_invalid_initial_tick_and_subscription_failure(self):
        for scenario in ('zero','add'):
            api=self.api()
            if scenario=='zero': api.symbol_info_tick.return_value=Tick(0,0,0,0,0,0,0,0)
            else: api.market_book_add.return_value=False
            s,_=self.capture(api)
            self.assertEqual(s['status'],'incomplete')
            api.market_book_release.assert_not_called()
            api.copy_ticks_from.assert_not_called()
            api.shutdown.assert_called_once()

    def test_interrupt_and_release_exception(self):
        for scenario in ('interrupt','release'):
            api=self.api()
            if scenario=='interrupt': api.market_book_get.side_effect=KeyboardInterrupt()
            else: api.market_book_release.side_effect=RuntimeError('release failed')
            s,_=self.capture(api)
            self.assertEqual(s['status'],'incomplete')
            api.market_book_release.assert_called_once()
            api.shutdown.assert_called_once()

    def test_slow_ticks_do_not_starve_dom(self):
        api=self.api()
        clock=Clock()
        def slow(*args):
            clock.now+=.06
            return self.rows
        api.copy_ticks_from.side_effect=slow
        s,_=self.capture(api,.35,clock)
        self.assertGreaterEqual(s['dom_queries'],2)
        self.assertGreater(s['latency_ms']['ticks']['max'],50)
        self.assertGreater(s['schedule_lateness_ms']['dom']['max'],0)

    def test_unknown_bits_preserved_without_hiding_side(self):
        api=self.api()
        self.rows[0]['flags']=1080
        self.rows[1]['flags']=1112
        self.rows[2]['flags']=1026
        self.rows[3]['flags']=1144
        s,records=self.capture(api)
        self.assertEqual(s['trade_sides'],{'BUY':1,'SELL':1,'AMBIGUOUS':1})
        self.assertEqual(s['ticks_with_unknown_flags'],4)
        self.assertEqual(s['unknown_flag_masks'],{1024:4})
        ticks=[t for b in records if b['kind']=='tick_batch' for t in b['ticks']]
        self.assertEqual([t['raw']['flags'] for t in ticks],[1080,1112,1026,1144])
        self.assertTrue(all(t['unknown_flag_mask']==1024 for t in ticks))
        self.assertIsNone(ticks[2]['side'])

    def test_initial_tick_alone_does_not_prove_joint_capture(self):
        api=self.api()
        self.rows=self.rows[:3]
        s,_=self.capture(api)
        self.assertEqual(s['valid_trade_ticks'],2)
        self.assertEqual(s['valid_trades_after_initial_tick'],0)
        self.assertEqual(s['status'],'joint_capture_unproven')

    def test_flags_ambiguous_invalid_volume_and_no_trades(self):
        api=self.api()
        self.rows[0]['flags']=104
        self.rows[1]['volume_real']=0
        s,_=self.capture(api)
        self.assertEqual(s['trade_sides']['AMBIGUOUS'],1)
        self.assertEqual(s['invalid_trade_ticks'],1)
        api=self.api()
        self.rows=[]
        s,_=self.capture(api)
        self.assertEqual(s['status'],'joint_capture_unproven')
        self.assertEqual(s['trade_ticks'],0)


if __name__=='__main__': unittest.main()
