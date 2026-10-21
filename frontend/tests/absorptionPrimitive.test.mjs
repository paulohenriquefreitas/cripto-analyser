import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { AbsorptionPrimitive } from '../src/features/win/components/AbsorptionPrimitive.ts';
import { absorptionMarkers, locateAbsorption, POSSIBLE, CONFIRMED } from '../src/features/replay/models/absorptionReplay.ts';

const report = JSON.parse(readFileSync(new URL('../../panic-scanner/dom-captures/experimental-absorption-capture-clock.json', import.meta.url), 'utf8'));
test('real 17 bubbles and 5 directional arrows draw at capture coordinates and recorded prices; click and zoom follow them', () => {
  let bubbles = 0, arrows = 0, scale = 1;
  for (const capture of report.captures) {
    for (const marker of absorptionMarkers(capture).filter(m => [POSSIBLE, CONFIRMED].includes(m.transition.state))) {
      const bucket = Math.floor(marker.timeMs/300000)*300;
      // Coordinates/candles are a synthetic renderer fixture, not a historical asset.
      const placed = locateAbsorption(marker, { timeframe_seconds: 300, candles: [{ time: bucket }] }).placed;
      assert.ok(placed);
      const p = new AbsorptionPrimitive();
      let updates = 0;
      p.attached({ chart: { timeScale: () => ({ timeToCoordinate: () => 20*scale, logicalToCoordinate: () => 80*scale }) },
        series: { priceToCoordinate: price => { assert.equal(price, marker.price); return 150; } }, requestUpdate() { updates++; } });
      p.setMarkers([placed], marker.id);
      assert.deepEqual(p.autoscaleInfo(-1, 2).priceRange, { minValue: marker.price, maxValue: marker.price });
      assert.equal(p.autoscaleInfo(4, 8), null);
      const arcs = [], lines = [];
      const context = { save() {}, restore() {}, beginPath() {}, closePath() {}, fill() {}, stroke() {}, fillRect() {},
        arc(...args) { arcs.push(args); }, moveTo(...args) { lines.push(args); }, lineTo(...args) { lines.push(args); } };
      const render = () => p.paneViews()[0].renderer().draw({ useMediaCoordinateSpace: f => f({ context }) });
      render();
      let x = (20 + 60*placed.fraction)*scale;
      assert.equal(p.pick(x, 150), marker.id);
      assert.equal(p.pick(x, 200), undefined);
      assert.equal(updates, 1);
      if (marker.transition.state === POSSIBLE) { bubbles++; assert.equal(arcs[0][2], 7); }
      else { arrows++; assert.ok(lines.length >= 5); }
      scale = 2; render(); x = (20 + 60*placed.fraction)*scale;
      assert.equal(p.pick(x, 150), marker.id);
      p.setMarkers([]); render(); assert.equal(p.pick(x, 150), undefined);
      p.detached(); scale = 1;
    }
  }
  assert.equal(bubbles, 17); assert.equal(arrows, 5);
});
