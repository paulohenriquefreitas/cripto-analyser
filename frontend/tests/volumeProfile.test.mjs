import { test } from 'node:test';
import assert from 'node:assert/strict';
import { profileBars, profileStatus, volumeProfileViewSchema } from '../src/features/win/models/volumeProfile.ts';

function profile() {
  return { symbol: 'WINZ26', session: '2026-10-20', timezone: 'America/Sao_Paulo', volumeSource: 'MT5_VOLUME_REAL',
    volumeUnit: 'MT5_VOLUME_REAL_UNITS', tickSize: 5, status: 'PARTIAL', partial: true, integrityCompromised: false,
    coverageReasons: ['FULL_SESSION_HISTORY_UNVERIFIED'], faults: [], firstTimeMsc: 1, lastTimeMsc: 2,
    trades: 4, totalVolume: 20, delta: 2, poc: 105, vah: 105, val: 100, valueAreaVolume: 20, valueAreaPercent: 100,
    levels: [{ price: 100, totalVolume: 8, buyVolume: 4, sellVolume: 2, ambiguousVolume: 2,
      delta: 2, buyPercent: 50, sellPercent: 25, ambiguousPercent: 25, trades: 2 },
    { price: 105, totalVolume: 12, buyVolume: 6, sellVolume: 6, ambiguousVolume: 0,
      delta: 0, buyPercent: 50, sellPercent: 50, ambiguousPercent: 0, trades: 2 }],
    hvn: [{ low: 105, high: 105 }], lvn: [] };
}
test('histogram uses shared scale, preserves neutral volume and marks POC/VA boundaries', () => {
  const p=profile(); const bars=profileBars(p);
  assert.deepEqual(bars.map(b=>b.price),[105,100]);
  assert.equal(bars[0].buyWidth,50); assert.equal(bars[0].sellWidth,50);
  assert.ok(Math.abs(bars[1].ambiguousWidth-100/6)<1e-10);
  assert.equal(bars[0].poc,true); assert.equal(bars[0].vah,true); assert.equal(bars[1].val,true);
  assert.equal(p.levels[0].price,100);
});
test('partial, complete and latched integrity have distinct labels', () => {
  const p=profile(); assert.equal(profileStatus(p),'Sessão parcial');
  p.status='COMPLETE';p.partial=false;assert.equal(profileStatus(p),'Sessão completa');
  p.integrityCompromised=true;assert.equal(profileStatus(p),'Integridade comprometida');
});
test('API schema rejects bad quantities or a different volume source', () => {
  const view={connected:true,lastReceivedUtcMsc:10,profile:profile(),previousSession:null,sourceFaults:[]};
  assert.deepEqual(volumeProfileViewSchema.parse(view),view);
  view.profile.volumeSource='DOM';assert.equal(volumeProfileViewSchema.safeParse(view).success,false);
  view.profile.volumeSource='MT5_VOLUME_REAL';view.profile.levels[0].ambiguousVolume=-1;
  assert.equal(volumeProfileViewSchema.safeParse(view).success,false);
});
test('empty profile has no invented bars or level values', () => {
  const p=profile();p.levels=[];p.poc=null;p.val=null;p.vah=null;
  assert.deepEqual(profileBars(p),[]);
  assert.equal(volumeProfileViewSchema.safeParse({connected:false,lastReceivedUtcMsc:null,profile:null,
    previousSession:null,sourceFaults:['SOURCE_DISCONNECTED']}).success,true);
});
