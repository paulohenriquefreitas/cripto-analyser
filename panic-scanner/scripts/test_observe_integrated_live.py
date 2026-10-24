import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import observe_integrated_live as live


def profile(volume=10, trades=1):
    stamp = int(time.time()*1000)
    return dict(connected=True, lastReceivedUtcMsc=stamp, sourceFaults=[], profile=dict(
        symbol='WINZ26', session='2026-10-21', timezone='America/Sao_Paulo',
        volumeSource='MT5_VOLUME_REAL', volumeUnit='MT5_VOLUME_REAL_UNITS', tickSize=5,
        status='PARTIAL', partial=True, integrityCompromised=False,
        coverageReasons=['FULL_SESSION_HISTORY_UNVERIFIED'], faults=[],
        firstTimeMsc=1, lastTimeMsc=stamp, trades=trades, totalVolume=volume, delta=volume,
        poc=200000, vah=200000, val=200000, valueAreaVolume=volume, valueAreaPercent=100,
        levels=[dict(price=200000, totalVolume=volume, buyVolume=volume, sellVolume=0,
                     ambiguousVolume=0, delta=volume, buyPercent=100, sellPercent=0,
                     ambiguousPercent=0, trades=trades)]))


def tick(stamp=1792587046715):
    return dict(type='tick', symbol='WINZ26', timeMsc=stamp, last=200000)


def flow():
    return dict(type='FLOW', symbol='WINZ26', timeMsc=1792597846715,
                lastTimeMsc=1792587046715, last=200000, volumeSource='MT5_VOLUME_REAL',
                windows=[dict(seconds=5, buyVolume=10, sellVolume=3, unknownVolume=2,
                              delta=7, quality='GOOD', alert=None)])


def frame(payload, opcode=1, final=True):
    data = payload.encode() if isinstance(payload, str) else payload
    size = len(data)
    prefix = bytes([(128 if final else 0) | opcode])
    return prefix + (bytes([size]) if size < 126 else bytes([126])+struct.pack('!H', size)) + data


class ObservationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.observer = live.Observer(Path(self.temp.name), 'WINZ26', .05, 15)

    def tearDown(self):
        if not self.observer.out.closed:
            self.observer.finish()
        self.temp.cleanup()

    def test_profile_accumulation_and_reset_detected(self):
        self.observer.profile(profile())
        self.observer.profile(profile(20, 2))
        self.assertEqual(self.observer.counts['profile_progress'], 2)
        self.observer.profile(profile(5, 1))
        self.assertGreater(self.observer.problems[('ERROR', 'PROFILE_CONTINUITY')], 0)

    def test_integrity_fault_and_side_sum(self):
        value = profile()
        value['profile'].update(status='INCOMPLETE', integrityCompromised=True,
                                faults=['SOURCE_GAP'])
        value['profile']['levels'][0]['sellVolume'] = 3
        self.observer.profile(value)
        self.assertGreaterEqual(self.observer.problems[('ERROR', 'PROFILE_VALIDATION')], 2)

    def test_profile_fault_does_not_invalidate_good_flow_in_either_arrival_order(self):
        value = profile()
        value['sourceFaults'] = ['SOURCE_DISCONNECTED']
        value['profile'].update(status='INCOMPLETE', integrityCompromised=True,
                                faults=['SOURCE_DISCONNECTED'])
        for profile_first in (True, False):
            with self.subTest(profile_first=profile_first), tempfile.TemporaryDirectory() as directory:
                observer = live.Observer(Path(directory), 'WINZ26', .05, 15)
                try:
                    actions = [lambda: observer.profile(value), lambda: observer.message(json.dumps(flow()))]
                    for action in actions if profile_first else reversed(actions):
                        action()
                    self.assertTrue(observer.problems_by_source['volume_profile'])
                    self.assertFalse(observer.problems_by_source['FLOW'])
                    self.assertEqual(observer.flow_summary()['windowQualities'], {'GOOD': 1})
                    self.assertEqual(observer.flow_summary()['assessment'], 'NO_FLOW_ISSUES_DETECTED')
                finally:
                    observer.finish()
                report = (Path(directory)/'report.txt').read_text()
                self.assertIn('Problems [volume_profile]:', report)
                self.assertIn('SOURCE_DISCONNECTED', (Path(directory)/'observations.ndjson').read_text())
                flow_section = report.split('Problems [FLOW]:')[1].split('Problems [volume_profile]:')[0]
                self.assertNotIn('PROFILE_VALIDATION', flow_section)
                rows = [json.loads(line) for line in (Path(directory)/'observations.ndjson').read_text().splitlines()]
                self.assertEqual(rows[-1]['flowSummary'], observer.flow_summary())
                self.assertTrue(all(row['source'] == 'volume_profile' for row in rows if row['type'] == 'issue'))

    def test_flow_integrity_and_quality_still_fail_with_healthy_profile(self):
        self.observer.profile(profile())
        self.observer.message(json.dumps(flow()))
        value = flow()
        value['timeMsc'] -= 1
        value['windows'][0].update(delta=8, quality='STALE')
        self.observer.message(json.dumps(value))
        wrong_symbol = flow()
        wrong_symbol['symbol'] = 'WINV26'
        self.observer.message(json.dumps(wrong_symbol))
        problems = self.observer.problems_by_source['FLOW']
        for severity, code in (('ERROR', 'FLOW_INVALID_VOLUMES'), ('ERROR', 'SOURCE_TIME_REGRESSION'),
                               ('ERROR', 'SYMBOL_MISMATCH'), ('WARN', 'FLOW_QUALITY_STALE')):
            self.assertGreater(problems[(severity, code)], 0)
        self.assertEqual(self.observer.flow_summary()['assessment'], 'ISSUES_DETECTED')
        self.assertFalse(self.observer.problems_by_source['volume_profile'])

    def test_tick_unavailability_and_profile_errors_do_not_mask_missing_flow(self):
        self.observer.message(json.dumps(dict(type='status', available=False)))
        self.observer.profile(profile())
        self.assertEqual(self.observer.flow_summary()['assessment'], 'NO_SAMPLES')
        self.observer.message(json.dumps(flow()))
        self.assertEqual(self.observer.flow_summary()['assessment'], 'NO_FLOW_ISSUES_DETECTED')
        self.observer.last_seen['FLOW'] -= 20
        self.observer.health()
        self.assertGreater(self.observer.problems_by_source['FLOW'][('WARN', 'NO_RECENT_FLOW')], 0)
        self.assertEqual(self.observer.flow_summary()['assessment'], 'ISSUES_DETECTED')

    def test_flow_schema_failure_is_reported_as_flow_issue(self):
        value = flow()
        del value['windows'][0]['delta']
        with patch.object(live, 'WebSocket') as connection:
            connection.return_value.receive.side_effect = [json.dumps(value), ConnectionError('end')]
            live.stream(self.observer, None, time.monotonic()+.01)
        self.assertEqual(self.observer.problems_by_source['FLOW'][('ERROR', 'WS_SCHEMA_ERROR')], 1)
        self.assertEqual(self.observer.flow_summary()['assessment'], 'ISSUES_DETECTED')

    def test_raw_duplicates_preserved_without_invented_trade_volume(self):
        message = json.dumps(tick())
        self.observer.message(message)
        self.observer.message(message)
        self.observer.message(json.dumps(tick(1792587106715)))
        self.observer.finish()
        rows = [json.loads(x) for x in (Path(self.temp.name)/'observations.ndjson').read_text().splitlines()]
        messages = [x for x in rows if x['type'] == 'websocket']
        self.assertEqual(messages[0]['rawText'], messages[1]['rawText'])
        self.assertEqual(len(messages), 3)
        self.assertTrue(all(bar['volume'] is None and bar['partial'] for bar in self.observer.bars.values()))
        self.assertEqual([x['sequence'] for x in rows], list(range(1, len(rows)+1)))

    def test_symbol_regression_and_flow_transition(self):
        self.observer.message(json.dumps(tick()))
        self.observer.message(json.dumps(tick(1)))
        bad = tick(); bad['symbol'] = 'WINV26'
        self.observer.message(json.dumps(bad))
        self.observer.message(json.dumps(flow()))
        bad_flow = flow(); bad_flow['windows'][0]['delta'] = 8
        self.observer.message(json.dumps(bad_flow))
        for code in ('SOURCE_TIME_REGRESSION', 'SYMBOL_MISMATCH', 'FLOW_INVALID_VOLUMES'):
            self.assertGreater(self.observer.problems[('ERROR', code)], 0)

    def test_staleness_and_unavailable_report(self):
        self.observer.start_clock -= 20
        self.observer.health()
        self.observer.finish(interrupted=True)
        report = (Path(self.temp.name)/'report.txt').read_text()
        self.assertIn('Official M1', report)
        self.assertIn('DOM', report)
        self.assertIn('FLOW', report)
        self.assertIn('Interrupted: True', report)
        self.assertIn('NO_RECENT_TICK', report)

    def test_websocket_fragment_timeout_ping_and_extended_length(self):
        client, server = socket.socketpair()
        client.settimeout(.01)
        ws = live.WebSocket.__new__(live.WebSocket)
        ws.sock, ws.buffer, ws.fragments, ws.fragmented = client, bytearray(), bytearray(), False
        try:
            server.sendall(frame('abc', final=False) + frame(b'x', opcode=9) + b'\x80')
            with self.assertRaises(socket.timeout):
                ws.receive()
            # The first byte of a partial frame and the partial UTF8 message survive timeout.
            server.sendall(b'\x03def')
            self.assertEqual(ws.receive(), 'abcdef')
            pong = server.recv(20)
            self.assertEqual(pong[0], 138)
            self.assertTrue(pong[1] & 128)
            server.sendall(frame('z'*200))
            self.assertEqual(ws.receive(), 'z'*200)
            server.sendall(frame(b'', opcode=8))
            with self.assertRaises(ConnectionError):
                ws.receive()
        finally:
            client.close(); server.close()

    def test_short_integrated_run_real_local_http_and_ws(self):
        paths = []
        class Handler(BaseHTTPRequestHandler):
            profile_fault = False

            def log_message(self, *args):
                pass

            def do_GET(self):
                paths.append(self.path)
                if self.path == '/ws/mt5/ticks':
                    key = self.headers['Sec-WebSocket-Key']
                    accept = base64.b64encode(hashlib.sha1(
                        (key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                    self.send_response(101)
                    self.send_header('Upgrade', 'websocket')
                    self.send_header('Connection', 'Upgrade')
                    self.send_header('Sec-WebSocket-Accept', accept)
                    self.end_headers()
                    self.wfile.write(frame(json.dumps(tick(int(time.time()*1000)))) + frame(json.dumps(flow())))
                    self.wfile.flush()
                    time.sleep(.5)
                else:
                    body = {'symbol': 'WINZ26'} if self.path == '/api/mt5/config' else profile()
                    if self.path == '/api/mt5/volume-profile' and self.profile_fault:
                        body['sourceFaults'] = ['SOURCE_DISCONNECTED']
                        body['profile'].update(status='INCOMPLETE', integrityCompromised=True,
                                               faults=['SOURCE_DISCONNECTED'])
                    self.send_response(200); self.end_headers()
                    self.wfile.write(json.dumps(body).encode())

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            args = argparse.Namespace(base_url=f'http://127.0.0.1:{server.server_port}',
                symbol='WINZ26', seconds=.2, interval=.05, stale_seconds=15,
                output_root=Path(self.temp.name)/'run')
            result = live.run(args)
            directory = next(args.output_root.iterdir())
            self.assertEqual(result, 0, (directory/'report.txt').read_text())
            self.assertEqual(set(paths), {'/api/mt5/config', '/api/mt5/volume-profile', '/ws/mt5/ticks'})
            rows = [json.loads(x) for x in (directory/'observations.ndjson').read_text().splitlines()]
            self.assertTrue(any(row['type'] == 'synchronization' for row in rows))
            self.assertIn('Final profile:', (directory/'report.txt').read_text())
            Handler.profile_fault = True
            args.output_root = Path(self.temp.name)/'profile-fault-run'
            self.assertEqual(live.run(args), 1)  # Overall errors remain visible.
            directory = next(args.output_root.iterdir())
            rows = [json.loads(x) for x in (directory/'observations.ndjson').read_text().splitlines()]
            summary = rows[-1]['flowSummary']
            self.assertGreater(summary['samples'], 0)
            self.assertEqual(summary['windowQualities'], {'GOOD': summary['samples']})
            self.assertEqual(summary['assessment'], 'NO_FLOW_ISSUES_DETECTED')
            self.assertEqual(summary['problems'], [])
            self.assertTrue(any(row.get('source') == 'volume_profile' and row.get('severity') == 'ERROR'
                                for row in rows))
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_ws_failure_is_explicit_gap(self):
        with patch.object(live, 'WebSocket', side_effect=ConnectionError('test disconnect')):
            live.stream(self.observer, None, time.monotonic()+.01)
        self.assertGreaterEqual(self.observer.problems[('ERROR', 'WS_GAP')], 1)


if __name__ == '__main__':
    unittest.main()
