"""Bounded read-only backend observer. Python standard library; no MT5 connection.

Only GET /api/mt5/config, GET /api/mt5/volume-profile and /ws/mt5/ticks.
The candles REST API starts another MT5 process, so it is deliberately not used.
"""
import argparse
import base64
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import struct
import threading
import time
from urllib.parse import urlsplit
from urllib.request import urlopen

from validate_volume_profile_live import inspect, changes, brief


def utc():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


class WebSocket:
    """Small RFC6455 read client: buffered frames, fragmentation, ping and masking.

    Loopback HTTP only. Reconnection never implies recovery of missing messages.
    """
    MAX = 16 * 1024 * 1024

    def __init__(self, origin, timeout=2):
        self.sock = socket.create_connection((origin.hostname, origin.port or 80), timeout)
        self.buffer = bytearray()
        self.fragments = bytearray()
        self.fragmented = False
        try:
            key = base64.b64encode(os.urandom(16)).decode()
            host = origin.netloc
            request = (f'GET /ws/mt5/ticks HTTP/1.1\r\nHost: {host}\r\n'
                       f'Upgrade: websocket\r\nConnection: Upgrade\r\n'
                       f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
            self.sock.sendall(request.encode('ascii'))
            while b'\r\n\r\n' not in self.buffer:
                data = self.sock.recv(4096)
                if not data:
                    raise ConnectionError('EOF during WebSocket handshake')
                self.buffer.extend(data)
                if len(self.buffer) > 65536:
                    raise ValueError('oversized WebSocket handshake')
            head, tail = self.buffer.split(b'\r\n\r\n', 1)
            self.buffer = bytearray(tail)
            lines = head.decode('ascii').split('\r\n')
            headers = {name.lower(): value for name, value in
                       (line.split(':', 1) for line in lines[1:] if ':' in line)}
            expected = base64.b64encode(hashlib.sha1(
                (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            if (lines[0].split()[1] != '101'
                    or headers.get('sec-websocket-accept', '').strip() != expected
                    or headers.get('upgrade', '').strip() != 'websocket'):
                raise ConnectionError('WebSocket upgrade rejected: ' + lines[0])
            self.sock.settimeout(.5)
        except Exception:
            self.close()
            raise

    def close(self):
        self.sock.close()

    def receive(self):
        while True:
            if len(self.buffer) >= 2:
                first, second = self.buffer[:2]
                final, opcode = bool(first & 128), first & 15
                if first & 112 or second & 128:
                    raise ValueError('unsupported WebSocket frame')
                size, offset = second & 127, 2
                if size == 126:
                    if len(self.buffer) < 4:
                        self._read(); continue
                    size, offset = struct.unpack('!H', self.buffer[2:4])[0], 4
                elif size == 127:
                    if len(self.buffer) < 10:
                        self._read(); continue
                    size, offset = struct.unpack('!Q', self.buffer[2:10])[0], 10
                if size > self.MAX or len(self.fragments) + size > self.MAX:
                    raise ValueError('oversized WebSocket message')
                if opcode >= 8 and (not final or size > 125):
                    raise ValueError('invalid WebSocket control frame')
                if len(self.buffer) < offset + size:
                    self._read(); continue
                payload = bytes(self.buffer[offset:offset + size])
                del self.buffer[:offset + size]
                if opcode == 8:
                    raise ConnectionError('server closed WebSocket')
                if opcode == 9:
                    mask = os.urandom(4)
                    self.sock.sendall(bytes([138, 128 | size]) + mask +
                                      bytes(v ^ mask[i % 4] for i, v in enumerate(payload)))
                    continue
                if opcode == 10:
                    continue
                if opcode == 1 and not self.fragmented:
                    self.fragments.extend(payload)
                    self.fragmented = not final
                elif opcode == 0 and self.fragmented:
                    self.fragments.extend(payload)
                    self.fragmented = not final
                else:
                    raise ValueError('unexpected WebSocket opcode/fragment')
                if final:
                    message = self.fragments.decode('utf-8')
                    self.fragments.clear()
                    return message
            else:
                self._read()

    def _read(self):
        data = self.sock.recv(65536)
        if not data:
            raise ConnectionError('WebSocket EOF')
        self.buffer.extend(data)


class Observer:
    def __init__(self, directory, symbol, interval, stale):
        self.directory, self.symbol = directory, symbol
        self.interval, self.stale = interval, stale
        self.lock = threading.RLock()
        self.out = (directory / 'observations.ndjson').open('x', encoding='utf-8')
        self.started = utc()
        self.start_clock = time.monotonic()
        self.sequence = 0
        self.counts, self.problems = Counter(), Counter()
        self.problems_by_source = {source: Counter() for source in ('FLOW', 'volume_profile', 'tick', 'shared')}
        self.flow_qualities = Counter()
        self.last_seen, self.first_seen, self.last_source = {}, {}, {}
        self.previous_profile = self.initial_profile = None
        self.profile_key = None
        self.bars = {}
        self.alerts = {}
        self.stop = threading.Event()
        self.record('start', symbol=symbol, intervalSeconds=interval,
                    sourceTimeBasisVerified=False, coverage='PARTIAL',
                    endpoints=['/api/mt5/config', '/api/mt5/volume-profile', '/ws/mt5/ticks'])

    def record(self, kind, **data):
        with self.lock:
            self.sequence += 1
            self.out.write(json.dumps(dict(sequence=self.sequence, receivedUtc=utc(),
                                          elapsedSeconds=time.monotonic()-self.start_clock,
                                          type=kind, **data), ensure_ascii=False, default=str)+'\n')
            self.out.flush()

    def issue(self, code, detail='', severity='WARN', source='shared'):
        with self.lock:
            self.problems[(severity, code)] += 1
            self.problems_by_source[source][(severity, code)] += 1
            self.record('issue', severity=severity, code=code, detail=detail, source=source)

    def flow_summary(self):
        # Backend quality is preserved verbatim. Profile/tick issues cannot
        # change this assessment; shared transport diagnostics remain separate.
        problems = self.problems_by_source['FLOW']
        return dict(samples=self.counts['FLOW'], windowQualities=dict(self.flow_qualities),
                    assessment='NO_SAMPLES' if not self.counts['FLOW'] else
                    'ISSUES_DETECTED' if problems else 'NO_FLOW_ISSUES_DETECTED',
                    problems=[dict(severity=severity, code=code, count=count)
                              for (severity, code), count in sorted(problems.items())])

    def seen(self, source, stamp=None):
        now = time.monotonic()
        self.last_seen[source] = now
        self.first_seen.setdefault(source, utc())
        self.counts[source] += 1
        if stamp is not None:
            if source in self.last_source and stamp < self.last_source[source]:
                self.issue('SOURCE_TIME_REGRESSION', source, 'ERROR', source=source)
            self.last_source[source] = stamp

    def message(self, payload):
        with self.lock:
            value = json.loads(payload)
            self.record('websocket', payload=value, rawText=payload)
            kind = value.get('type')
            if kind == 'status':
                self.seen('status')
                if not value['available']:
                    self.issue('PRICE_FEED_UNAVAILABLE', source='tick')
                return
            if kind not in ('tick', 'FLOW'):
                self.issue('UNKNOWN_WS_TYPE', str(kind)); return
            if value['symbol'] != self.symbol:
                self.issue('SYMBOL_MISMATCH', value['symbol'], 'ERROR', source=kind); return
            stamp = value['timeMsc']
            if type(stamp) is not int or stamp < 0:
                raise ValueError('invalid timeMsc')
            self.seen(kind, stamp)
            if kind == 'tick':
                price = value['last']
                if not isinstance(price, (float, int)) or not math.isfinite(price) or price <= 0:
                    raise ValueError('invalid LAST')
                self.record('clock_comparison', rawTimeMsc=stamp,
                            rawAsUtc=datetime.fromtimestamp(stamp/1000, timezone.utc).isoformat(),
                            receiptMinusRawMs=time.time()*1000-stamp,
                            sourceTimeBasisVerified=False)
                # LAST updates are not executed trades. Never sum their volume.
                for seconds in (60, 300):
                    bucket = stamp // (seconds*1000) * seconds
                    old = self.bars.get(seconds)
                    if old is not None and bucket < old['time']:
                        continue
                    if old is None or bucket > old['time']:
                        if old is not None:
                            self.record('derived_bar_closed', bar=old)
                        old = dict(seconds=seconds, time=bucket, open=price, high=price,
                                   low=price, close=price, updates=0, partial=True,
                                   origin='OBSERVED_LAST_NOT_OFFICIAL_CANDLE', volume=None)
                        self.bars[seconds] = old
                    old.update(high=max(old['high'], price), low=min(old['low'], price),
                               close=price, updates=old['updates']+1)
            else:
                for window in value['windows']:
                    b, s, a = (Decimal(str(window[k])) for k in
                               ('buyVolume', 'sellVolume', 'unknownVolume'))
                    delta = Decimal(str(window['delta']))
                    if any(not n.is_finite() for n in (b, s, a, delta)) or min(b, s, a) < 0 or abs(delta-(b-s)) > Decimal('0.000001'):
                        self.issue('FLOW_INVALID_VOLUMES', str(window), 'ERROR', source='FLOW')
                    state = (window['quality'], window['alert'])
                    self.flow_qualities[state[0]] += 1
                    key = window['seconds']
                    if self.alerts.get(key) != state:
                        self.record('flow_state', seconds=key, quality=state[0], alert=state[1],
                                    note='Descriptive monitoring only; not absorption detector transitions')
                        self.alerts[key] = state
                    if state[0] not in ('GOOD', 'WARMING_UP'):
                        self.issue('FLOW_QUALITY_' + state[0], str(key), source='FLOW')

    def profile(self, value):
        with self.lock:
            sample, problems = inspect(value, self.symbol)
            self.seen('volume_profile')
            for problem in problems:
                self.issue('PROFILE_VALIDATION', problem, 'ERROR' if problem.startswith('ERROR:') else 'WARN', source='volume_profile')
            if sample is None:
                return
            key = (sample['symbol'], sample['session'])
            if self.profile_key is not None and key != self.profile_key:
                self.issue('SESSION_CHANGED', repr((self.profile_key, key)), source='volume_profile')
                self.previous_profile = None
            if self.previous_profile is not None:
                levels, problems = changes(self.previous_profile, sample)
                for problem in problems:
                    self.issue('PROFILE_CONTINUITY', problem, 'ERROR', source='volume_profile')
                self.record('profile_change', changedPrices=levels,
                            volumeIncrement=sample['totalVolume']-self.previous_profile['totalVolume'],
                            tradesIncrement=sample['trades']-self.previous_profile['trades'])
                if sample['trades'] > self.previous_profile['trades']:
                    self.seen('profile_progress')
            else:
                self.seen('profile_progress')
            self.profile_key = key
            if self.initial_profile is None:
                self.initial_profile = brief(sample)
            self.previous_profile = sample
            raw = value['profile']['lastTimeMsc']
            tick = self.last_source.get('tick')
            self.record('synchronization', tickRawTimeMsc=tick, profileRawTimeMsc=raw,
                        differenceMs=None if tick is None or raw is None else tick-raw,
                        backendReceivedUtcMsc=value['lastReceivedUtcMsc'],
                        note='Asynchronous LAST versus trades; not an atomic snapshot')
            if tick is not None and raw is not None and abs(tick-raw) > self.stale*1000:
                self.issue('SOURCE_TIME_SKEW', f'LAST - profile = {tick-raw}ms')
            received = value['lastReceivedUtcMsc']
            if received is None or time.time()*1000-received > self.stale*1000:
                self.issue('BACKEND_RECEIPT_STALE', source='volume_profile')

    def health(self):
        with self.lock:
            now = time.monotonic()
            for source in ('tick', 'FLOW', 'volume_profile', 'profile_progress'):
                elapsed = now-self.last_seen.get(source, self.start_clock)
                if elapsed > self.stale:
                    scope = 'volume_profile' if source == 'profile_progress' else source
                    self.issue('NO_RECENT_' + source.upper(), f'{elapsed:.1f}s; does not prove zero market trades', source=scope)

    def finish(self, interrupted=False):
        unavailable = {
            'DOM': 'No read-only DOM API exposed by the existing backend.',
            'Absorption detector': 'No detector transitions API exposed. FLOW alert is descriptive only.',
            'Individual Times & Trades': 'WebSocket exposes rolling FLOW aggregates, not raw trades/cursor/duplicates.',
            'Official M1': 'No safe existing read-only cached route identified.',
            'Official M5': 'Candles endpoint launches another MT5 process; not called.'}
        for source in ('tick', 'FLOW', 'volume_profile'):
            if not self.counts[source]:
                unavailable[source] = 'No valid sample received during this observation.'
        self.record('finish', interrupted=interrupted, counts=dict(self.counts),
                    unavailable=unavailable, derivedBars=list(self.bars.values()), flowSummary=self.flow_summary())
        elapsed = time.monotonic()-self.start_clock
        lines = ['Integrated WIN observation (backend only)', f'Symbol: {self.symbol}',
                 f'Start UTC: {self.started}', f'End UTC: {utc()}', f'Duration: {elapsed:.3f}s',
                 f'Interrupted: {interrupted}', f'Counts: {dict(self.counts)}',
                 f'Initial profile: {self.initial_profile}',
                 f'Final profile: {brief(self.previous_profile) if self.previous_profile else None}',
                 'Unavailable sources:']
        lines += [f'  {key}: {reason}' for key, reason in unavailable.items()]
        lines += [f'FLOW summary: {json.dumps(self.flow_summary())}',
                  'FLOW uses its own received samples and checks; profile/tick errors do not override backend quality.',
                  'Overall exit status covers all sources; it is not a FLOW quality verdict.']
        for source, problems in self.problems_by_source.items():
            lines.append(f'Problems [{source}]:')
            lines += [f'  {severity} {code}: {n}' for (severity, code), n in sorted(problems.items())]
            if not problems:
                lines.append('  None detected in available samples.')
        lines += ['Limits: partial observation; no independent source-clock confirmation.',
                  'No proof of tick completeness, cursor recovery or absence of duplicate trades.',
                  'WebSocket reconnects are gaps and are never considered recovered.',
                  'Raw timestamps are preserved. No fixed time shift is applied by this observer.',
                  'Derived M1/M5 are partial LAST bars without volume, not official candles.',
                  'Raw payloads/events: observations.ndjson (local receipt order, not upstream total order).']
        (self.directory/'report.txt').write_text('\n'.join(lines)+'\n', encoding='utf-8')
        self.out.close()


def stream(observer, origin, deadline):
    while not observer.stop.is_set() and time.monotonic() < deadline:
        connection = None
        try:
            connection = WebSocket(origin, min(2, max(.1, deadline-time.monotonic())))
            observer.record('ws_connected', continuityVerified=False)
            while not observer.stop.is_set() and time.monotonic() < deadline:
                try:
                    payload = connection.receive()
                except socket.timeout:
                    continue
                try:
                    observer.message(payload)
                except (ValueError, KeyError, TypeError, ArithmeticError) as exc:
                    observer.record('invalid_ws_payload', raw=payload)
                    try:
                        kind = json.loads(payload).get('type')
                    except (ValueError, AttributeError):
                        kind = None
                    source = kind if kind in ('FLOW', 'tick') else 'shared'
                    observer.issue('WS_SCHEMA_ERROR', str(exc), 'ERROR', source=source)
        except (OSError, ValueError) as exc:
            if not observer.stop.is_set() and time.monotonic() < deadline:
                observer.issue('WS_GAP', str(exc), 'ERROR')
        finally:
            if connection:
                connection.close()
        observer.stop.wait(min(2, max(0, deadline-time.monotonic())))


def run(args):
    origin = urlsplit(args.base_url)
    if origin.scheme != 'http' or origin.hostname not in ('localhost', '127.0.0.1') or origin.path not in ('', '/') or origin.query or origin.fragment or origin.username:
        raise ValueError('Use a loopback HTTP backend origin, e.g. http://localhost:8080')
    directory = args.output_root / (datetime.now(timezone.utc).strftime('integrated-%Y%m%dT%H%M%S%fZ'))
    directory.mkdir(parents=True, exist_ok=False)
    observer = Observer(directory, args.symbol, args.interval, args.stale_seconds)
    deadline = observer.start_clock+args.seconds
    worker = threading.Thread(target=stream, args=(observer, origin, deadline), daemon=True)
    interrupted = False
    worker.start()
    scheduled = observer.start_clock
    try:
        while time.monotonic() < deadline:
            for source, path in (('config', '/api/mt5/config'), ('profile', '/api/mt5/volume-profile')):
                if time.monotonic() >= deadline:
                    break
                began = time.monotonic()
                try:
                    with urlopen(args.base_url.rstrip('/')+path, timeout=min(4, deadline-began)) as response:
                        raw = response.read().decode('utf-8')
                    value = json.loads(raw, parse_float=Decimal)
                    observer.record('http', endpoint=path, durationMs=(time.monotonic()-began)*1000,
                                    payload=value, rawText=raw)
                    if source == 'config':
                        if value['symbol'] != args.symbol:
                            observer.issue('CONFIG_SYMBOL_MISMATCH', str(value), 'ERROR')
                    else:
                        observer.profile(value)
                except (OSError, ValueError, KeyError, TypeError, ArithmeticError) as exc:
                    observer.issue('HTTP_QUERY_OR_SCHEMA', path+': '+str(exc), 'ERROR',
                                   source='volume_profile' if source == 'profile' else 'shared')
            observer.health()
            scheduled += args.interval
            while scheduled < time.monotonic():
                scheduled += args.interval
            observer.stop.wait(max(0, min(scheduled, deadline)-time.monotonic()))
    except KeyboardInterrupt:
        interrupted = True
    finally:
        observer.stop.set()
        worker.join(timeout=3)
        # Socket reads/connects are bounded; wait for the writer before closing files.
        if worker.is_alive():
            worker.join()
        observer.finish(interrupted)
    print(directory.resolve())
    return 1 if interrupted or any(severity == 'ERROR' for severity, _ in observer.problems) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--symbol', default='WINZ26')
    parser.add_argument('--seconds', type=float, default=1800)
    parser.add_argument('--interval', type=float, default=5)
    parser.add_argument('--stale-seconds', type=float, default=15)
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[1]/'dom-captures')
    args = parser.parse_args()
    if any(not math.isfinite(v) or v <= 0 for v in (args.seconds, args.interval, args.stale_seconds)):
        parser.error('Durations must be finite and positive')
    return run(args)


if __name__ == '__main__':
    raise SystemExit(main())
