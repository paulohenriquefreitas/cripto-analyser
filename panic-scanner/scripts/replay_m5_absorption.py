"""Offline descriptive M5 level/absorption study. Standard library; no MT5/network.

Raw timestamps are never shifted. Receipt UTC is checked as a separate clock.
This consumes existing GOOD FLOW alerts, not the operational absorption detector.
"""
import argparse
from bisect import bisect_left
from collections import Counter
import csv
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics


VERSION = 'm5-absorption-offline-v1'
DISTANCES = (25, 50, 100)
HORIZONS = (10, 20, 30, 60)
GAP_MS = 2000
ENDPOINT_TOLERANCE_MS = 1000
CLOCK_TOLERANCE_MS = 2000
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_EXPORT = ROOT / 'panic-scanner/dom-captures/offline-absorption/WINZ26-M5-20261015-20261023-20261023T152747947257Z'
DEFAULT_OBSERVATIONS = ROOT / 'panic-scanner/dom-captures/integrated-20261023T135325712039Z/observations.ndjson'
PIVOT_SOURCE = ROOT / 'frontend/src/features/win/models/mt5SwingPivots.ts'


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def integer(value):
    return type(value) is int


def utc_msc(value):
    stamp = datetime.fromisoformat(value)
    if stamp.utcoffset() is None:
        raise ValueError('Receipt timestamps must have an explicit timezone')
    return stamp.timestamp() * 1000


def raw_label(ms):
    # Rendering only: the suffix explicitly avoids asserting an audited UTC basis.
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat() + ' [raw rendered as UTC]'


def validate_candles(candles):
    if not isinstance(candles, list) or len(candles) < 6:
        raise ValueError('Insufficient M5 history')
    previous = -1
    gaps = []
    for candle in candles:
        t = candle['time']
        if not integer(t) or t < 0 or t % 300 or t <= previous:
            raise ValueError('Invalid M5 timestamp, grid, ordering or duplicate')
        if any(not finite(candle[k]) or candle[k] <= 0 for k in ('open', 'high', 'low', 'close')):
            raise ValueError('Invalid M5 price')
        if not candle['low'] <= min(candle['open'], candle['close']) <= max(candle['open'], candle['close']) <= candle['high']:
            raise ValueError('Invalid M5 OHLC bounds')
        if any(not integer(candle[k]) or candle[k] < 0 for k in ('tickVolume', 'realVolume')):
            raise ValueError('Invalid M5 volume')
        if previous >= 0 and t - previous != 300:
            gaps.append(dict(from_raw=previous, to_raw=t, seconds=t-previous))
        previous = t
    return gaps


def load_inputs(export_dir, observations):
    export_dir, observations = Path(export_dir), Path(observations)
    names = ('candles.json', 'metadata.json', 'validation.json')
    paths = {name: export_dir / name for name in names}
    paths['observations.ndjson'] = observations
    hashes = {name: sha256(path) for name, path in paths.items()}
    manifest = {}
    for line in (export_dir / 'SHA256SUMS.txt').read_text(encoding='ascii').splitlines():
        digest, name = line.split('  ', 1)
        if name in manifest:
            raise ValueError('Duplicate hash manifest entry')
        manifest[name] = digest
    for name in names:
        if manifest.get(name) != hashes[name]:
            raise ValueError('Export SHA-256 mismatch: ' + name)
    meta = json.loads(paths['metadata.json'].read_bytes())
    validation = json.loads(paths['validation.json'].read_bytes())
    candles = json.loads(paths['candles.json'].read_bytes())
    gaps = validate_candles(candles)
    if meta['symbol'] != 'WINZ26' or meta['timeframe'] != 'M5':
        raise ValueError('Wrong symbol/timeframe')
    if meta['timestamps']['appliedShiftSeconds'] != 0 or not meta['timestamps']['preserved']:
        raise ValueError('Expected preserved, unshifted raw timestamps')
    if meta['candlesSha256'] != hashes['candles.json']:
        raise ValueError('Metadata candle hash mismatch')
    if any(meta[key] != hashes['observations.ndjson'] for key in ('observationsSha256Before', 'observationsSha256After')):
        raise ValueError('Observations do not match the exported study provenance')
    if meta['candleCount'] != len(candles) or validation['candleCount'] != len(candles):
        raise ValueError('Candle count differs from export metadata/validation')
    if validation['integrityErrors'] or not validation['collectionCoverage']['allCollectionBucketsPresent']:
        raise ValueError('Export validation did not pass')
    for t in validation['collectionCoverage']['bucketStartRawTimes']:
        if t not in {c['time'] for c in candles}:
            raise ValueError('Missing target candle')
    return candles, meta, dict(input_sha256=hashes, candle_gaps=gaps,
                               export_edge_caveats=validation['edgeCaveats'],
                               script_sha256=sha256(__file__), pivot_source_sha256=sha256(PIVOT_SOURCE))


@dataclass
class Level:
    kind: str
    pivot_time: int
    price: float
    confirmed_at: int
    broken_at: int | None = None

    @property
    def id(self):
        return f'{self.kind}:{self.pivot_time}'


class PivotReplay:
    """Port of createSwingPivotTracker.close/setHistory, including overnight delay.

    close(i) runs only at candle[i+1].time, never while i is forming. The final
    historical bar is not closed without a successor, exactly like the chart.
    Historical retrieval/HTTP latency is unknown; ideal next-bar availability is
    an explicit experimental assumption, not a reconstruction of UI state.
    """
    def __init__(self, candles):
        self.candles = candles
        self.index = 0
        self.window = []
        self.levels = []

    def advance(self, time_msc):
        while self.index + 1 < len(self.candles):
            candle = self.candles[self.index]
            available = self.candles[self.index + 1]['time']
            if available * 1000 > time_msc:
                break
            if available < candle['time'] + 300:
                raise ValueError('M5 candle available before closure')
            # Retire first; equality and wick-only penetrations do not retire.
            for level in self.levels:
                if level.broken_at is None and (
                    level.kind == 'HIGH' and candle['close'] > level.price or
                    level.kind == 'LOW' and candle['close'] < level.price
                ):
                    level.broken_at = available
            self.window.append(candle)
            if len(self.window) == 5:
                pivot = self.window[2]
                neighbors = self.window[:2] + self.window[3:]
                if all(pivot['high'] > c['high'] for c in neighbors):
                    self.levels.append(Level('HIGH', pivot['time'], pivot['high'], available))
                if all(pivot['low'] < c['low'] for c in neighbors):
                    self.levels.append(Level('LOW', pivot['time'], pivot['low'], available))
                self.window.pop(0)
            self.index += 1

    def nearest(self, price, broken=False):
        levels = [level for level in self.levels if (level.broken_at is not None) == broken]
        # Chart returns highs followed by lows. Explicit deterministic tie rule.
        return min(levels, key=lambda level: (abs(price-level.price), level.kind != 'HIGH', level.pivot_time), default=None)


def check_alignment(tick_offsets, flow_offsets, shift_minutes):
    if not tick_offsets or not flow_offsets or len(shift_minutes) != 1:
        raise ValueError('Missing clocks or changing FLOW time convention')
    expected = next(iter(shift_minutes)) * 60_000
    result = {'applied_shift_ms': 0, 'hypothesis_receipt_minus_raw_ms': expected,
              'independently_confirmed': False, 'tolerance_ms': CLOCK_TOLERANCE_MS}
    for name, values in (('tick', tick_offsets), ('FLOW', flow_offsets)):
        median = statistics.median(values)
        result[name] = dict(median_receipt_minus_raw_ms=median,
                            samples=len(values),
                            outside_tolerance=sum(abs(v-expected) > CLOCK_TOLERANCE_MS for v in values))
        if abs(median-expected) > CLOCK_TOLERANCE_MS:
            raise ValueError('Raw/receipt clock convention inconsistent for ' + name)
    if abs(result['tick']['median_receipt_minus_raw_ms']-result['FLOW']['median_receipt_minus_raw_ms']) > CLOCK_TOLERANCE_MS:
        raise ValueError('Tick and FLOW clock domains differ')
    return result


def read_observations(path, candles):
    ticks, flows, exclusions, gaps, transport_sequences = [], [], [], [], []
    counts, qualities = Counter(), Counter()
    offsets = {'tick': [], 'FLOW': []}
    shifts, bases = set(), {}
    previous_sequence, previous_elapsed = 0, -1
    previous_flow_time = -1
    last_row = None
    seen_ws_connection = False
    by_bucket = {c['time']*1000: c for c in candles}
    price_consistency = Counter()
    with Path(path).open(encoding='utf-8') as source:
        for line_number, line in enumerate(source, 1):
            row = json.loads(line)
            seq, elapsed = row['sequence'], row['elapsedSeconds']
            if not integer(seq) or seq <= previous_sequence or not finite(elapsed) or elapsed < previous_elapsed:
                raise ValueError(f'Observation order invalid at line {line_number}')
            previous_sequence, previous_elapsed, last_row = seq, elapsed, row
            if row['type'] == 'ws_connected':
                if seen_ws_connection:
                    transport_sequences.append(seq)
                seen_ws_connection = True
            if row['type'] == 'issue' and row.get('code') in ('WS_GAP', 'WS_SCHEMA_ERROR'):
                transport_sequences.append(seq)
            if row['type'] != 'websocket':
                continue
            value = row['payload']
            kind = value.get('type')
            counts[kind] += 1
            if kind not in ('tick', 'FLOW'):
                continue
            stamp = value.get('timeMsc')
            if value.get('symbol') != 'WINZ26' or not integer(stamp) or stamp < 0:
                exclusions.append(dict(sequence=seq, source=kind, reason='SYMBOL_OR_TIMESTAMP'))
                continue
            receipt = utc_msc(row['receivedUtc'])
            offsets[kind].append(receipt-stamp)
            item = dict(sequence=seq, elapsed=elapsed, received_utc=row['receivedUtc'], time_msc=stamp, value=value)
            if kind == 'tick':
                if not finite(value.get('last')) or value['last'] <= 0 or value.get('time') != stamp//1000:
                    exclusions.append(dict(sequence=seq, source=kind, reason='INVALID_TICK_PRICE_OR_TIME'))
                    continue
                if ticks and stamp < ticks[-1]['time_msc']:
                    exclusions.append(dict(sequence=seq, source=kind, reason='TICK_TIME_REGRESSION'))
                    continue
                if ticks:
                    before = ticks[-1]
                    raw_gap, receipt_gap = stamp-before['time_msc'], (elapsed-before['elapsed'])*1000
                    if raw_gap > GAP_MS or receipt_gap > GAP_MS:
                        gaps.append(dict(from_raw_msc=before['time_msc'], to_raw_msc=stamp,
                                         from_sequence=before['sequence'], to_sequence=seq,
                                         source_gap_ms=raw_gap, receipt_gap_ms=receipt_gap))
                bucket = stamp//300_000*300_000
                candle = by_bucket.get(bucket)
                price_consistency['ticks'] += 1
                if candle is None:
                    price_consistency['missing_candle'] += 1
                elif not candle['low'] <= value['last'] <= candle['high']:
                    price_consistency['outside_official_range'] += 1
                ticks.append(item)
            else:
                basis = value.get('timeBasis', {})
                shift = basis.get('feedTimeShiftMinutes')
                if not integer(shift):
                    raise ValueError('Missing FLOW clock convention')
                shifts.add(shift)
                bases.setdefault('first', basis)
                bases['last'] = basis
                if stamp < previous_flow_time:
                    exclusions.append(dict(sequence=seq, source=kind, reason='FLOW_TIME_REGRESSION'))
                    continue
                previous_flow_time = stamp
                for window in value.get('windows', []):
                    qualities[window.get('quality')] += 1
                flows.append(item)
    if not last_row or last_row['type'] != 'finish':
        raise ValueError('Observation is incomplete: missing finish record')
    if any(counts[k] != last_row['counts'].get(k, 0) for k in ('tick', 'FLOW')):
        raise ValueError('Observation finish counts differ from raw messages')
    alignment = check_alignment(offsets['tick'], offsets['FLOW'], shifts)
    if price_consistency['missing_candle']:
        raise ValueError('Missing official M5 buckets for observed ticks')
    if price_consistency['outside_official_range']:
        raise ValueError('Raw alignment inconsistent with official OHLC price bounds')
    expected = alignment['hypothesis_receipt_minus_raw_ms']
    # Keep late ticks in the gap audit but never use them as outcome endpoints.
    for source, items in (('tick', ticks), ('FLOW', flows)):
        for item in items:
            item['clock_consistent'] = abs(utc_msc(item['received_utc'])-item['time_msc']-expected) <= CLOCK_TOLERANCE_MS
            if source == 'tick' and not item['clock_consistent']:
                exclusions.append(dict(sequence=item['sequence'], source='tick', reason='TICK_RECEIPT_CLOCK_INCONSISTENT'))
    return dict(ticks=ticks, flows=flows, exclusions=exclusions, gaps=gaps,
                transport_sequences=sorted(set(transport_sequences)), counts=dict(counts),
                qualities=dict(qualities), alignment=alignment, time_basis=bases,
                price_consistency=dict(price_consistency))


def valid_window(window, stamp):
    seconds = window.get('seconds')
    if seconds not in (5, 10, 30) or not integer(seconds):
        return 'INVALID_WINDOW_DURATION'
    if window.get('toTimeMsc') != stamp or window.get('fromTimeMsc') != stamp-seconds*1000:
        return 'WINDOW_CLOCK_MISMATCH'
    if not integer(window.get('events')) or window['events'] < 0:
        return 'INVALID_EVENT_COUNT'
    names = ('buyVolume', 'sellVolume', 'unknownVolume', 'delta')
    if any(not finite(window.get(k)) for k in names) or min(window[k] for k in names[:3]) < 0:
        return 'INVALID_VOLUMES'
    buy, sell, unknown = (window[k] for k in names[:3])
    if not math.isclose(window['delta'], buy-sell, abs_tol=1e-6, rel_tol=1e-12):
        return 'DELTA_MISMATCH'
    if window.get('quality') == 'GOOD':
        total = buy+sell+unknown
        if total <= 0 or window['events'] <= 0 or not finite(window.get('lastDisplacement')):
            return 'INVALID_GOOD_WINDOW'
        for key, volume in (('buyPercent', buy), ('sellPercent', sell), ('unknownPercent', unknown)):
            if not finite(window.get(key)) or not math.isclose(window[key], volume*100/total, abs_tol=1e-5):
                return 'PERCENTAGE_MISMATCH'
    return None


class Episodes:
    """Union overlapping GOOD alert windows per aggression direction.

    Onset/price/levels never move after creation. Volume/delta are NOT summed.
    Opposite directions remain separate; episodes are not asserted independent.
    """
    def __init__(self):
        self.events = []
        self.latest = {}

    def accept(self, direction, window, item, pivots):
        previous = self.latest.get(direction)
        if previous and window['fromTimeMsc'] < previous['episode_end_msc']:
            previous['episode_end_msc'] = max(previous['episode_end_msc'], item['time_msc'])
            previous['alert_window_observations'] += 1
            previous['source_windows'] = sorted(set(previous['source_windows']+[window['seconds']]))
            return False
        price = item['value']['last']
        nearest, broken = pivots.nearest(price), pivots.nearest(price, broken=True)
        event = dict(event_id=len(self.events)+1, direction=direction,
                     time_msc=item['time_msc'], sequence=item['sequence'], elapsed=item['elapsed'],
                     received_utc=item['received_utc'], baseline_price=price,
                     baseline_time_msc=item['value']['lastTimeMsc'], onset_window_seconds=window['seconds'],
                     onset_delta=window['delta'], onset_displacement=window['lastDisplacement'],
                     episode_end_msc=item['time_msc'], alert_window_observations=1,
                     source_windows=[window['seconds']],
                     active_level_count=sum(level.broken_at is None for level in pivots.levels),
                     broken_level_count=sum(level.broken_at is not None for level in pivots.levels))
        for prefix, level in (('nearest_active', nearest), ('nearest_broken', broken)):
            event.update({prefix+'_id': level.id if level else None,
                          prefix+'_kind': level.kind if level else None,
                          prefix+'_price': level.price if level else None,
                          prefix+'_distance': abs(price-level.price) if level else None,
                          prefix+'_confirmed_at': level.confirmed_at if level else None,
                          prefix+'_broken_at': level.broken_at if level else None})
        self.events.append(event)
        self.latest[direction] = event
        return True


def build_events(candles, data):
    pivots, episodes = PivotReplay(candles), Episodes()
    counts = Counter()
    excluded = list(data['exclusions'])
    for item in data['flows']:
        f, stamp = item['value'], item['time_msc']
        pivots.advance(stamp)
        reason = None
        if not item['clock_consistent']:
            reason = 'FLOW_RECEIPT_CLOCK_INCONSISTENT'
        elif f.get('volumeSource') != 'MT5_VOLUME_REAL':
            reason = 'UNEXPECTED_FLOW_VOLUME_SOURCE'
        elif not finite(f.get('last')) or f['last'] <= 0 or not integer(f.get('lastTimeMsc')) or not 0 <= stamp-f['lastTimeMsc'] <= GAP_MS:
            reason = 'MISSING_OR_STALE_FLOW_BASELINE'
        windows = f.get('windows')
        if not isinstance(windows, list) or sorted(w.get('seconds', -1) for w in windows) != [5, 10, 30]:
            reason = 'MISSING_OR_DUPLICATE_FLOW_WINDOWS'
        if reason:
            excluded.append(dict(sequence=item['sequence'], source='FLOW', reason=reason))
            continue
        for window in sorted(windows, key=lambda w: w['seconds']):
            error = valid_window(window, stamp)
            if error:
                excluded.append(dict(sequence=item['sequence'], source='FLOW_WINDOW', window_seconds=window['seconds'], reason=error))
                continue
            if window.get('alert') != 'POSSIBLE_ABSORPTION':
                continue
            counts['alert_window_observations'] += 1
            if window['quality'] != 'GOOD':
                excluded.append(dict(sequence=item['sequence'], source='FLOW_WINDOW', window_seconds=window['seconds'], reason='ALERT_WITHOUT_GOOD'))
                continue
            if window['buyVolume'] == window['sellVolume']:
                excluded.append(dict(sequence=item['sequence'], source='FLOW_WINDOW', window_seconds=window['seconds'], reason='AMBIGUOUS_AGGRESSION'))
                continue
            direction = 'BUY_AGGRESSION' if window['buyVolume'] > window['sellVolume'] else 'SELL_AGGRESSION'
            counts['eligible_alert_window_observations'] += 1
            if not episodes.accept(direction, window, item, pivots):
                counts['merged_alert_window_observations'] += 1
    counts['episodes'] = len(episodes.events)
    return episodes.events, pivots, excluded, dict(counts)


def outcome(event, horizon, ticks, tick_times, gaps, transport_sequences):
    target = event['time_msc'] + horizon*1000
    index = bisect_left(tick_times, target)
    while index < len(ticks) and ticks[index]['sequence'] <= event['sequence']:
        index += 1
    if index == len(ticks):
        return dict(status='RIGHT_CENSORED_NO_FUTURE_TICK')
    tick = ticks[index]
    if tick['time_msc']-target > ENDPOINT_TOLERANCE_MS:
        return dict(status='NO_TICK_WITHIN_ENDPOINT_TOLERANCE')
    if not tick['clock_consistent']:
        return dict(status='TICK_RECEIPT_CLOCK_INCONSISTENT')
    if any(event['time_msc'] < gap['to_raw_msc'] and tick['time_msc'] > gap['from_raw_msc'] for gap in gaps):
        return dict(status='TICK_GAP_IN_HORIZON')
    if any(event['sequence'] < seq <= tick['sequence'] for seq in transport_sequences):
        return dict(status='TRANSPORT_GAP_IN_HORIZON')
    change = tick['value']['last']-event['baseline_price']
    return dict(status='OK', tick_time_msc=tick['time_msc'], tick_sequence=tick['sequence'],
                tick_price=tick['value']['last'], endpoint_delay_ms=tick['time_msc']-target,
                observed_horizon_ms=tick['time_msc']-event['time_msc'],
                displacement=change,
                # Positive = price moved against the dominant aggressive side.
                against_aggression=change*(-1 if event['direction'] == 'BUY_AGGRESSION' else 1))


def aggregate(events, outcomes):
    rows, comparisons = [], []
    lookup = {(o['event_id'], o['horizon_seconds']): o for o in outcomes}
    for distance in DISTANCES:
        for direction in ('BUY_AGGRESSION', 'SELL_AGGRESSION'):
            for horizon in HORIZONS:
                groups = {}
                for group in ('NEAR', 'FAR', 'NO_ACTIVE_LEVEL'):
                    members = []
                    for event in events:
                        d = event['nearest_active_distance']
                        category = 'NO_ACTIVE_LEVEL' if d is None else 'NEAR' if d <= distance else 'FAR'
                        if event['direction'] == direction and category == group:
                            members.append(lookup[event['event_id'], horizon])
                    valid = [m for m in members if m['status'] == 'OK']
                    changes = [m['displacement'] for m in valid]
                    reactions = [m['against_aggression'] for m in valid]
                    row = dict(distance_points=distance, direction=direction, horizon_seconds=horizon,
                               group=group, episodes=len(members), valid_outcomes=len(valid),
                               excluded_outcomes=len(members)-len(valid),
                               mean_displacement=statistics.mean(changes) if changes else None,
                               median_displacement=statistics.median(changes) if changes else None,
                               mean_against_aggression=statistics.mean(reactions) if reactions else None,
                               median_against_aggression=statistics.median(reactions) if reactions else None,
                               stdev_against_aggression=statistics.stdev(reactions) if len(reactions)>1 else None,
                               fraction_against_aggression=sum(r>0 for r in reactions)/len(reactions) if reactions else None)
                    rows.append(row)
                    groups[group] = row
                near, far = groups['NEAR'], groups['FAR']
                comparable = near['valid_outcomes'] > 0 and far['valid_outcomes'] > 0
                comparisons.append(dict(distance_points=distance, direction=direction, horizon_seconds=horizon,
                                        near_n=near['valid_outcomes'], far_n=far['valid_outcomes'],
                                        near_mean=near['mean_against_aggression'], far_mean=far['mean_against_aggression'],
                                        near_minus_far=near['mean_against_aggression']-far['mean_against_aggression'] if comparable else None))
    return rows, comparisons


def write_csv(path, rows, empty_fields):
    fields = list(dict.fromkeys(k for row in rows for k in row)) if rows else empty_fields
    with path.open('w', newline='', encoding='utf-8') as out:
        writer = csv.DictWriter(out, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: json.dumps(value, sort_keys=True) if isinstance(value, (list, dict)) else value
                             for key, value in row.items()})


def number(value):
    return '--' if value is None else f'{value:.2f}'


def write_outputs(directory, export_dir, observations, candles, meta, provenance, data, events, pivots, exclusions, counts):
    directory = Path(directory)
    if directory.exists():
        raise ValueError('Output directory already exists; choose a new one')
    # Outcomes use only subsequent recorded ticks; historical OHLC is never a price fallback.
    times = [tick['time_msc'] for tick in data['ticks']]
    outcomes = [dict(event_id=event['event_id'], horizon_seconds=h,
                     **outcome(event, h, data['ticks'], times, data['gaps'], data['transport_sequences']))
                for event in events for h in HORIZONS]
    summary, comparisons = aggregate(events, outcomes)
    exclusion_counts = Counter(e['reason'] for e in exclusions)
    outcome_counts = Counter(o['status'] for o in outcomes)
    level_rows = [dict(level_id=level.id, **asdict(level), state='ACTIVE' if level.broken_at is None else 'BROKEN') for level in pivots.levels]
    run = dict(version=VERSION, inputs=provenance,
               settings=dict(distances_points=DISTANCES, horizons_seconds=HORIZONS,
                             tick_gap_ms=GAP_MS, endpoint_tolerance_ms=ENDPOINT_TOLERANCE_MS,
                             clock_tolerance_ms=CLOCK_TOLERANCE_MS, symbol=meta['symbol'],
                             level_set='ACTIVE_ONLY; BROKEN recorded separately',
                             deduplication='Union overlapping GOOD alert windows per aggression; fixed onset, no volume/delta summation',
                             candle_availability='Next candle opening, excluding final historical bar; no HTTP latency reconstruction',
                             timestamp_shift_applied_ms=0),
               alignment=data['alignment'], time_basis=data['time_basis'],
               official_price_consistency=data['price_consistency'], observed_counts=data['counts'],
               observed_window_qualities=data['qualities'], episode_counts=counts,
               excluded_inputs=dict(exclusion_counts), outcomes=dict(outcome_counts),
               tick_gap_count=len(data['gaps']), transport_gap_count=len(data['transport_sequences']),
               levels=dict(confirmed=len(level_rows), active=sum(l.broken_at is None for l in pivots.levels),
                           broken=sum(l.broken_at is not None for l in pivots.levels)))
    # Refuse to publish if an input changed during analysis.
    for name, path in (('candles.json', Path(export_dir)/'candles.json'),
                       ('metadata.json', Path(export_dir)/'metadata.json'),
                       ('validation.json', Path(export_dir)/'validation.json'),
                       ('observations.ndjson', Path(observations))):
        if sha256(path) != provenance['input_sha256'][name]:
            raise ValueError('Input modified during replay: ' + name)
    directory.mkdir(parents=True)
    (directory/'run.json').write_text(json.dumps(run, indent=2, sort_keys=True)+'\n', encoding='utf-8')
    for name, rows, fields in (
        ('events.csv', events, ['event_id']), ('outcomes.csv', outcomes, ['event_id', 'horizon_seconds', 'status']),
        ('summary.csv', summary, []), ('comparisons.csv', comparisons, []),
        ('levels.csv', level_rows, ['level_id']), ('tick_gaps.csv', data['gaps'], ['from_raw_msc', 'to_raw_msc']),
        ('exclusions.csv', exclusions, ['sequence', 'source', 'reason'])):
        write_csv(directory/name, rows, fields)
    lines = ['# Estudo experimental offline de absorção em níveis M5', '',
             f'Versão: `{VERSION}`. Dados de WINZ26; nenhuma conexão MT5/backend foi iniciada.', '',
             '## Método e causalidade', '',
             '- Pivôs estritos 2+2, preço do pavio e rompimento apenas por fechamento estritamente além do nível.',
             '- Cada candle só entra no cálculo na abertura do sucessor; confirmedAt é respeitado. O último candle exportado não é fechado pelo replay.',
             '- Supõe-se disponibilidade ideal na abertura seguinte. A latência HTTP e o histórico real da tela não estão registrados.',
             '- Somente alertas POSSIBLE_ABSORPTION em janelas GOOD. Direção = agressão dominante; não é lado de uma ordem recomendada.',
             '- Episódios unem janelas de alerta sobrepostas da mesma direção, inclusive entre durações. Primeiro instante/preço/nível ficam fixos. Não são somados volumes nem Deltas.',
             '- Distância absoluta ao nível ativo mais próximo, suporte OU resistência; empate: HIGH antes de LOW, depois pivô mais antigo. Rompidos não contam como ativos.',
             '- Limiares fixos de 25/50/100 pontos; NEAR inclui igualdade. Sem nível ativo: grupo separado, não FAR.',
             '- Horizonte: primeiro tick posterior com timestamp >= início+h, recebido depois do alerta, atraso máximo de 1 s; sem interpolação ou preenchimento.',
             '- Exclui horizontes atravessados por lacunas de ticks >2 s (fonte ou recebimento), gaps de transporte ou endpoint temporalmente inconsistente.',
             '- Deslocamento = preço futuro - preço FLOW no início. Contra-agressão positivo: queda após agressão compradora ou alta após agressão vendedora.', '',
             '## Integridade e relógio', '',
             f'- {len(candles)} candles; hashes de candles, metadados e validação conferidos no manifesto; observations confere com os hashes dos metadados.',
             '- Nenhum timestamp original alterado. rate.time em segundos e timeMsc em milissegundos são comparados no domínio bruto.',
             f"- Hipótese recibo UTC menos tempo bruto: {data['alignment']['hypothesis_receipt_minus_raw_ms']/60000:g} minutos. Não é uma correção aplicada nem validação independente do relógio.",
             f"- Mediana tick: {number(data['alignment']['tick']['median_receipt_minus_raw_ms'])} ms; FLOW: {number(data['alignment']['FLOW']['median_receipt_minus_raw_ms'])} ms.",
             f"- Todos os {data['price_consistency']['ticks']} ticks válidos possuem candle no mesmo bucket bruto e preço dentro de seu OHLC oficial.",
             f"- Intervalo FLOW bruto: {raw_label(data['flows'][0]['time_msc'])} até {raw_label(data['flows'][-1]['time_msc'])}.",
             '- Candles exportados após a coleta podem conferir cobertura, mas não antecipam pivôs ou rompimentos no replay.', '',
             '## Amostras e exclusões', '',
             f"- Mensagens: {json.dumps(data['counts'], sort_keys=True)}.",
             f'- Alertas/episódios: {json.dumps(counts, sort_keys=True)}.',
             f'- Qualidades das janelas: {json.dumps(data["qualities"], sort_keys=True)}.',
             f'- Exclusões de entrada: {json.dumps(dict(exclusion_counts), sort_keys=True)}.',
             f'- Desfechos: {json.dumps(dict(outcome_counts), sort_keys=True)}.',
             f"- Lacunas de ticks: {len(data['gaps'])}; gaps de transporte: {len(data['transport_sequences'])}.",
             f"- Níveis ao final do replay: {run['levels']}.", '',
             '## Próximos versus distantes', '',
             'Médias em pontos contra a agressão. A diferença é NEAR menos FAR; -- indica ausência de amostra comparável.', '',
             '| Distância | Agressão | Horizonte | n perto | n longe | Média perto | Média longe | Diferença |',
             '|---:|---|---:|---:|---:|---:|---:|---:|']
    for c in comparisons:
        lines.append(f"| {c['distance_points']} | {c['direction']} | {c['horizon_seconds']}s | {c['near_n']} | {c['far_n']} | {number(c['near_mean'])} | {number(c['far_mean'])} | {number(c['near_minus_far'])} |")
    lines += ['', '## Leitura descritiva da amostra', '']
    for direction in ('BUY_AGGRESSION', 'SELL_AGGRESSION'):
        selected = [e for e in events if e['direction'] == direction]
        nearby = {d: sum(e['nearest_active_distance'] is not None and e['nearest_active_distance'] <= d for e in selected)
                  for d in DISTANCES}
        lines.append(f'- {direction}: {len(selected)} episódios; próximos por limite: {nearby}.')
    lines += ['- Células vazias não permitem comparação. Uma diferença de médias com um ou dois episódios é apenas descritiva e pode ser dominada por uma única interação.',
              '- O sinal da diferença na tabela não demonstra eficácia de absorção nem vantagem operacional. Não foram escolhidos parâmetros pelos resultados.',
              '', '## Limitações', '',
              '- Uma única janela de 30 minutos: sem inferência causal, significância estatística, validação fora da amostra ou recomendação operacional.',
              '- Episódios de direções diferentes e horizontes podem se sobrepor. Os limiares são aninhados: as linhas não são amostras independentes.',
              '- Grupos pequenos e diferentes instantes/regimes/direções confundem médias. Não selecionar limiar ou horizonte vencedor nesta coleta.',
              '- Alertas sustentados contam uma vez no início. Um episódio pode incluir mais de uma interação real; sem tape completo não é possível provar identidade econômica.',
              '- Cada nível requer apenas 2+2 barras; não há filtro de sessão, distância ou força extra. O estado inicial usa a exportação disponível, não o histórico completo da tela.',
              '- O arquivo histórico pode conter revisões posteriores. Não existe registro de versões/horários de disponibilização de cada candle na coleta.',
              '- Ticks são LASTs amostrados, não negócios individuais; não se reconstrói agressão nem o detector operacional a partir deles.',
              '- Falhas históricas do Volume Profile não alteram esta análise. Problemas do FLOW e lacunas de ticks são preservados.',
              '- Barras iniciais/finais parciais e avisos da exportação: '+json.dumps(provenance['export_edge_caveats'], ensure_ascii=False), '',
              '## Reprodução', '',
              '```powershell',
              f'python -B .\\panic-scanner\\scripts\\replay_m5_absorption.py --export-dir "{Path(export_dir).resolve()}" --observations "{Path(observations).resolve()}" --output-dir "NOVO_DIRETORIO"',
              '```', '',
              'run.json registra hashes e regras. events.csv contém episódios e níveis ativos/rompidos no início; outcomes.csv todos os horizontes e exclusões; summary.csv inclui grupos vazios e tamanhos; comparisons.csv contém diferenças descritivas; levels.csv e tick_gaps.csv permitem auditoria.', '']
    (directory/'report.md').write_text('\n'.join(lines), encoding='utf-8')
    outputs = sorted(p for p in directory.iterdir() if p.is_file())
    (directory/'SHA256SUMS.txt').write_text(''.join(sha256(p)+'  '+p.name+'\n' for p in outputs), encoding='ascii')
    return run, comparisons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-dir', type=Path, default=DEFAULT_EXPORT)
    parser.add_argument('--observations', type=Path, default=DEFAULT_OBSERVATIONS)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.output_dir.exists():
            raise ValueError('Output directory already exists; refusing to overwrite')
        candles, meta, provenance = load_inputs(args.export_dir, args.observations)
        data = read_observations(args.observations, candles)
        events, pivots, exclusions, counts = build_events(candles, data)
        run, comparisons = write_outputs(args.output_dir, args.export_dir, args.observations, candles, meta,
                                         provenance, data, events, pivots, exclusions, counts)
        print(json.dumps(dict(output=str(args.output_dir.resolve()), episode_counts=counts,
                              exclusions=run['excluded_inputs'], outcomes=run['outcomes'], comparisons=comparisons), indent=2))
    except (ValueError, KeyError, TypeError, OSError) as exc:
        parser.exit(1, 'Replay rejected: '+str(exc)+'\n')


if __name__ == '__main__':
    main()
