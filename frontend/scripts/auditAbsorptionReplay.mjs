// Offline visualization inventory. Does not run or modify the detector.
import { readFileSync, writeFileSync } from 'node:fs';
import { absorptionMarkers, locateAbsorption, parseAbsorptionReport, parseCandleBundle,
  POSSIBLE, CONFIRMED } from '../src/features/replay/models/absorptionReplay.ts';

const report = parseAbsorptionReport(JSON.parse(readFileSync(new URL('../../panic-scanner/dom-captures/experimental-absorption-capture-clock.json', import.meta.url), 'utf8')));
const candles = parseCandleBundle(JSON.parse(readFileSync(new URL('../public/absorption/candles.json', import.meta.url), 'utf8')));
const captures = report.captures.map(c => {
  const markers = absorptionMarkers(c).filter(m => [POSSIBLE, CONFIRMED].includes(m.transition.state));
  const session = candles.sessions.find(s => s.capture_sha256 === c.source_sha256);
  return { source: c.source, sha256: c.source_sha256,
    possible: markers.filter(m => m.transition.state === POSSIBLE).length,
    confirmed: markers.filter(m => m.transition.state === CONFIRMED).length,
    markers: markers.map(m => {
      const result = locateAbsorption(m, session);
      return { event_id: m.transition.event_id, state: m.transition.state, known_at_utc_ms: m.timeMs,
        utc_interval_ms: m.utcInterval, price: m.price, observed_order: m.transition.observed_order,
        positioned: !!result.placed, reason: result.reason ?? null };
    }) };
});
const result = { captures, possible: captures.reduce((n,c) => n+c.possible,0), confirmed: captures.reduce((n,c) => n+c.confirmed,0),
  positioned: captures.flatMap(c => c.markers).filter(m => m.positioned).length,
  limitation: candles.limitation };
const encoded = JSON.stringify(result, null, 2)+'\n';
if (process.argv[2]) writeFileSync(process.argv[2], encoded, { flag: 'wx' });
else process.stdout.write(encoded);
