from collections import namedtuple
from datetime import datetime,timezone,timedelta
from types import SimpleNamespace
from unittest.mock import Mock
import subprocess
import unittest

from mt5_clock_probe import run, windows_sync, clock_sample, duration

Tick=namedtuple('Tick','time time_msc last flags volume volume_real bid ask')
Info=namedtuple('Info','time')


class Clock:
    def __init__(self): self.now=0
    def __call__(self): return self.now
    def sleep(self,value): self.now+=max(value,.000001)
    def utc(self): return datetime(2026,10,15,20,0,0,tzinfo=timezone.utc)+timedelta(seconds=self.now)


class ClockProbeTest(unittest.TestCase):
    def setup_api(self):
        api=Mock()
        api.initialize.return_value=True
        api.account_info.return_value=SimpleNamespace(company='Clear',server='Clear-Test')
        base=int(datetime(2026,10,15,17,tzinfo=timezone.utc).timestamp()*1000)
        api.symbol_info.return_value=Info(base//1000)
        api.symbol_info_tick.return_value=Tick(base//1000,base,100,1080,1,1.,95,105)
        api.COPY_TICKS_ALL=-1
        rows=[dict(time=base//1000,time_msc=base,last=100,flags=1080,volume=1,volume_real=1.,bid=95,ask=105),
              dict(time=base//1000,time_msc=base+1,last=100,flags=1112,volume=1,volume_real=1.,bid=95,ask=105)]
        api.copy_ticks_from.side_effect=lambda *args: rows
        api.last_error.return_value=(-1,'fixture')
        return api,rows

    def runner(self):
        return Mock(side_effect=[SimpleNamespace(returncode=0,stdout='Source: test\nPhase Offset: +0.001s\n',stderr=''),
                                 SimpleNamespace(returncode=0,stdout='test\n',stderr='')])

    def execute(self,api,runner=None):
        timer=Clock()
        return run(api,.05,clock=timer,sleep=timer.sleep,now=timer.utc,runner=runner or self.runner())

    def test_read_only_connection_raw_timestamps_and_differences(self):
        api,rows=self.setup_api()
        original=[dict(r) for r in rows]
        r=self.execute(api)
        self.assertEqual(r['status'],'new_ticks_observed')
        self.assertEqual(r['counts']['ticks'],2)
        self.assertEqual(r['counts']['ticks_strictly_after_initial_time'],1)
        self.assertEqual(r['interpretation']['apparent_offset']['nearest_whole_hour_ms'],10800000)
        self.assertFalse(r['interpretation']['apparent_offset']['timestamps_corrected'])
        self.assertFalse(r['interpretation']['reception_delay']['measured_independently'])
        self.assertEqual(r['batches'][0]['ticks'][0]['raw'],rows[0])
        self.assertEqual(r['observed_differences_ms']['query_start_minus_market']['max'],10800000)
        self.assertEqual(rows,original)
        api.initialize.assert_called_once_with(timeout=3000)
        api.shutdown.assert_called_once()
        api.symbol_select.assert_not_called()
        api.market_book_add.assert_not_called()

    def test_w32tm_commands_and_offset_evidence_only(self):
        runner=self.runner()
        r=windows_sync(runner)
        self.assertEqual([c.args[0] for c in runner.call_args_list],
            [['w32tm','/query','/status','/verbose'],['w32tm','/query','/source']])
        self.assertTrue(r['queries'][0]['offset_evidence_lines'])
        self.assertFalse(r['external_clock_accuracy_verified'])

    def test_w32tm_unavailable_does_not_fabricate_offset_or_block_mt5(self):
        api,_=self.setup_api()
        runner=Mock(side_effect=[FileNotFoundError('missing'),subprocess.TimeoutExpired('w32tm',2)])
        r=self.execute(api,runner)
        self.assertTrue(all(not q['available'] for q in r['windows_sync']['queries']))
        self.assertEqual(r['status'],'new_ticks_observed')

    def test_tick_zero_and_api_error_cleanup(self):
        api,_=self.setup_api()
        api.symbol_info_tick.return_value=Tick(0,0,0,0,0,0.,0,0)
        r=self.execute(api)
        self.assertEqual(r['status'],'incomplete')
        self.assertIsNone(r['interpretation']['apparent_offset']['nearest_whole_hour_ms'])
        api.copy_ticks_from.assert_not_called()
        api.shutdown.assert_called_once()
        api,_=self.setup_api()
        api.copy_ticks_from.side_effect=RuntimeError('query failed')
        r=self.execute(api)
        self.assertEqual(r['status'],'incomplete')
        self.assertIn('error',r['batches'][0])
        api.shutdown.assert_called_once()

    def test_no_new_tick_and_interrupt(self):
        api,rows=self.setup_api()
        api.copy_ticks_from.side_effect=lambda *args:rows[:1]
        r=self.execute(api)
        self.assertEqual(r['status'],'no_new_ticks_observed')
        api,_=self.setup_api()
        api.copy_ticks_from.side_effect=KeyboardInterrupt()
        r=self.execute(api)
        self.assertIn('interrupted',r['errors'])
        api.shutdown.assert_called_once()

    def test_duration_bounds_and_explicit_timezone(self):
        for v in ('0','11','nan','inf'):
            with self.assertRaises(Exception): duration(v)
        self.assertEqual(duration('10'),10)
        r=clock_sample(lambda:datetime(2026,10,15,17,tzinfo=timezone(timedelta(hours=-3))))
        self.assertTrue(r['utc'].startswith('2026-10-15T20:00:00'))
        self.assertIsNotNone(datetime.fromisoformat(r['local']).utcoffset())


if __name__=='__main__': unittest.main()
