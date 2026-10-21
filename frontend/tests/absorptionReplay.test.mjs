import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { absorptionMarkers, visibleAbsorption, knownFinalState, locateAbsorption, parseAbsorptionReport, parseCandleBundle,
  POSSIBLE, CONFIRMED, INVALID, INSUFFICIENT } from '../src/features/replay/models/absorptionReplay.ts';

const raw = JSON.parse(readFileSync(new URL('../../panic-scanner/dom-captures/experimental-absorption-capture-clock.json', import.meta.url), 'utf8'));
const report = parseAbsorptionReport(raw);
const recovered = parseCandleBundle(JSON.parse(readFileSync(new URL('../public/absorption/candles.json', import.meta.url), 'utf8')));
const all = report.captures.flatMap(c => absorptionMarkers(c));
const targets = all.filter(m => [POSSIBLE, CONFIRMED].includes(m.transition.state));
const compatible = marker => {
  const time = Math.floor(marker.timeMs / 300000) * 300;
  // SYNTHETIC test fixture ONLY. Never exported as real candles or shown in UI.
  return { capture_sha256: marker.id.split(':')[0], symbol: 'WINZ26', time_reference: 'capture_utc', timeframe_seconds: 300,
    source: 'synthetic unit-test fixture', candles: [{ time, open: 210000, high: 220000, low: 200000, close: 210000, tickVolume: 1, realVolume: 1 }] };
};

test('nine REAL capture files contain 17 possible entries and 5 confirmed reactions', () => {
  assert.equal(report.captures.length, 9);
  assert.equal(targets.filter(m => m.transition.state === POSSIBLE).length, 17);
  assert.equal(targets.filter(m => m.transition.state === CONFIRMED).length, 5);
  assert.equal(all.filter(m => m.transition.state === INVALID).length, 109);
  assert.equal(all.filter(m => m.transition.state === INSUFFICIENT).length, 78);
});

test('without compatible candles no event is positioned or forced to a nearby candle', () => {
  for (const m of targets) {
    assert.equal(locateAbsorption(m).placed, undefined);
    assert.match(locateAbsorption(m).reason, /Sem candles WINZ26/);
  }
});

test('Clear REAL M1 history positions 16 possible states and all 5 reactions; Python event 9 remains explicitly unplaced', () => {
  assert.equal(recovered.sessions.length, 6);
  assert.equal(recovered.sessions.reduce((n,s) => n+s.candles.length,0), 51);
  const placed=[],missing=[];
  for (const capture of report.captures) {
    const session=recovered.sessions.find(s => s.capture_sha256===capture.source_sha256);
    for (const marker of absorptionMarkers(capture).filter(m => [POSSIBLE,CONFIRMED].includes(m.transition.state))) {
      const result=locateAbsorption(marker,session);
      if (result.placed) {
        placed.push(marker);
        assert.ok(Math.abs(result.placed.candleTime*1000+result.placed.fraction*60000-marker.timeMs)<.001);
      } else missing.push({marker,source:capture.source,reason:result.reason});
    }
  }
  assert.equal(placed.filter(m => m.transition.state===POSSIBLE).length,16);
  assert.equal(placed.filter(m => m.transition.state===CONFIRMED).length,5);
  assert.equal(missing.length,1);assert.equal(missing[0].marker.transition.event_id,9);
  assert.match(missing[0].source,/joint-probe-30s/);
  for(const s of recovered.sessions) {
    assert.equal(s.timeframe_seconds,60);
    assert.equal(s.clock_mapping.latency_calibration_established,false);
    for(const candle of s.candles) assert.equal(candle.time,candle.rawTime+s.clock_mapping.source_wall_to_utc_seconds);
  }
});

test('each REAL possible/confirmed state is placed at its OWN knowledge interval and recorded price, given compatible test candles', () => {
  for (const m of targets) {
    const { placed, reason } = locateAbsorption(m, compatible(m));
    assert.equal(reason, undefined);
    assert.ok(placed);
    assert.equal(placed.candleTime*1000 + placed.fraction*300000, m.transition.known_at_local_ms);
    assert.equal(placed.price, m.transition.state === CONFIRMED ? m.transition.reaction.price : m.transition.reference_price);
    assert.deepEqual(placed.utcInterval, m.transition.capture_known_utc_interval_ms);
    assert.ok(placed.widthFraction > 0);
  }
});

test('no future markers or final state leak at ANY prefix of all nine real captures', () => {
  for (const capture of report.captures) {
    for (let cursor = -1; cursor < capture.transitions.length; cursor++) {
      const prefix = { ...capture, transitions: capture.transitions.slice(0, cursor+1) };
      assert.deepEqual(visibleAbsorption(capture, cursor), absorptionMarkers(prefix));
      for (const marker of visibleAbsorption(capture, cursor)) {
        assert.ok(marker.index <= cursor);
        assert.equal(knownFinalState(capture, marker.transition.event_id, cursor), prefix.transitions.filter(t => t.event_id === marker.transition.event_id).at(-1).state);
      }
    }
  }
});

test('all 17 suspected episodes remain present after invalidation/insufficient data; directional arrows use hypothesis', () => {
  for (const c of report.captures) {
    const markers = absorptionMarkers(c);
    for (const m of markers.filter(m => m.transition.state === POSSIBLE)) {
      assert.equal(m.shape, 'bubble'); assert.equal(m.color, '#fdd835');
      assert.ok(visibleAbsorption(c, c.transitions.length-1).some(v => v.id === m.id));
      const final = c.transitions.filter(t => t.event_id === m.transition.event_id).at(-1);
      assert.notEqual(final.state, POSSIBLE);
    }
    for (const m of markers.filter(m => m.transition.state === CONFIRMED)) {
      const buy = m.transition.suspected_type === 'COMPRADORA';
      assert.equal(m.shape, buy ? 'up' : 'down');
      assert.equal(m.color, buy ? '#00c853' : '#ff1744');
    }
  }
});

test('no nearest-candle, price, market-time, end-of-replay, or timezone fallback is allowed', () => {
  const m = targets[0], session = compatible(m);
  assert.match(locateAbsorption({ ...m, timeMs: null, utcInterval: null }, session).reason, /sem instante/);
  assert.match(locateAbsorption(m, { ...session, candles: session.candles.map(c => ({ ...c, time: c.time+300 })) }).reason, /Sem candle no período/);
  const boundary = (session.candles[0].time+300)*1000;
  assert.match(locateAbsorption({ ...m, timeMs: boundary-500, utcInterval: [boundary-500, boundary+500] }, session).reason, /atravessa/);
  for (const changed of [{ symbol: 'WINV26' }, { symbol: 'WIN$N' }, { time_reference: 'market_raw' }, { source: '' }, { timeframe_seconds: 180 }]) {
    assert.throws(() => parseCandleBundle({ schema_version: 1, sessions: [{ ...session, ...changed }] }));
  }
});

test('capture interval resolution and same-second FILE order survive unchanged', () => {
  for (const capture of report.captures) {
    for (const m of absorptionMarkers(capture)) {
      assert.deepEqual(m.utcInterval, m.transition.capture_known_utc_interval_ms);
      assert.equal(m.timeMs, m.transition.known_at_local_ms);
      if (capture.temporal_reference.basis === 'ea_capture' && m.timeMs !== null) assert.equal(m.timeMs % 1000, 0);
    }
  }
});
