/** Absorption visualization only. Never infers an exchange/capture clock offset. */
export const POSSIBLE = 'POSSÍVEL_ABSORÇÃO';
export const CONFIRMED = 'REAÇÃO_CONFIRMADA';
export const INVALID = 'INVALIDADA';
export const INSUFFICIENT = 'DADOS_INSUFICIENTES';
export type AbsorptionTransition = {
  event_id: number; state: string; reason: string; suspected_type: string;
  reference_price: number; reference_market_time_msc: number;
  known_at_local_ms: number | null; observed_order: number;
  capture_known_utc_interval_ms: [number, number] | null;
  volume_buy: number; volume_sell: number; volume_ambiguous: number; delta: number;
  dom_evidence: unknown[]; quality: Record<string, unknown>;
  reaction?: { price: number; market_time_msc: number };
};
export type AbsorptionCapture = {
  source: string; source_sha256: string;
  transitions: AbsorptionTransition[];
  temporal_reference: { utc_resolution_ms: number; basis: string; limitation: string };
  summary: { candidates: number; possible_absorption_events: number;
    final_states: Record<string, number>; integrity_faults: string[]; missing_inputs: string[] };
};
export type AbsorptionReport = { schema_version: number; captures: AbsorptionCapture[] };
export type AbsorptionCandle = {
  time: number; open: number; high: number; low: number; close: number;
  tickVolume: number; realVolume: number; sma9?: number | null; sma21?: number | null;
  ema9?: number | null; vwap?: number | null; vwapSession?: string;
  rawTime?: number;
};
export type CandleSession = {
  capture_sha256: string; symbol: string; time_reference: 'capture_utc';
  timeframe_seconds: number; source: string; candles: AbsorptionCandle[];
  clock_mapping?: Record<string, unknown>;
  data_quality?: { missing_m1_utc: string[]; mismatched_complete_minutes: number; matched_complete_minutes: number };
};
export type CandleBundle = { schema_version: number; sessions: CandleSession[]; limitation?: string };
export type AbsorptionMarker = {
  id: string; transition: AbsorptionTransition; index: number;
  timeMs: number | null; utcInterval: [number, number] | null;
  price: number; color: string; shape: 'bubble' | 'up' | 'down' | 'cross' | 'diamond';
};
export type PlacedAbsorption = AbsorptionMarker & { candleIndex: number; candleTime: number; fraction: number; widthFraction: number };

export function parseAbsorptionReport(value: unknown): AbsorptionReport {
  const r = value as AbsorptionReport;
  if (!r || r.schema_version !== 2 || !Array.isArray(r.captures)) throw new Error('Replay de absorção incompatível (schema 2 necessário).');
  for (const c of r.captures) {
    if (typeof c.source_sha256 !== 'string' || !Array.isArray(c.transitions) || !c.summary || !c.temporal_reference) throw new Error('Captura sem identificação/evidências.');
    for (const t of c.transitions) {
      if (!Number.isSafeInteger(t.event_id) || typeof t.state !== 'string' || !Number.isFinite(t.reference_price)
        || !Number.isFinite(t.reference_market_time_msc) || !Number.isFinite(t.observed_order)
        || (t.known_at_local_ms !== null && !Number.isFinite(t.known_at_local_ms))) throw new Error('Transição inválida.');
      if (t.capture_known_utc_interval_ms !== null && (!Array.isArray(t.capture_known_utc_interval_ms)
        || t.capture_known_utc_interval_ms.length !== 2 || !t.capture_known_utc_interval_ms.every(Number.isFinite)
        || t.capture_known_utc_interval_ms[1] <= t.capture_known_utc_interval_ms[0])) throw new Error('Faixa UTC inválida.');
    }
  }
  return r;
}

export function parseCandleBundle(value: unknown): CandleBundle {
  const bundle = value as CandleBundle;
  if (!bundle || bundle.schema_version !== 1 || !Array.isArray(bundle.sessions)) throw new Error('Candles: esperado schema_version 1 e sessions.');
  const seen = new Set<string>();
  for (const s of bundle.sessions) {
    if (!s || typeof s.capture_sha256 !== 'string' || seen.has(s.capture_sha256) || s.symbol !== 'WINZ26'
      || s.time_reference !== 'capture_utc' || ![60, 300].includes(s.timeframe_seconds)
      || typeof s.source !== 'string' || !s.source.trim() || !Array.isArray(s.candles)) {
      throw new Error('Candles exigem sessão única, contrato WINZ26, M1/M5 (60/300 s), origem e referência capture_utc declarados; sem ajuste automático.');
    }
    seen.add(s.capture_sha256);
    for (let i = 0; i < s.candles.length; i++) {
      const c = s.candles[i];
      if (!Number.isSafeInteger(c.time) || c.time % s.timeframe_seconds !== 0
        || ![c.open, c.high, c.low, c.close, c.tickVolume, c.realVolume].every(Number.isFinite)
        || c.low <= 0 || c.high < c.low || c.open < c.low || c.open > c.high || c.close < c.low || c.close > c.high
        || c.tickVolume < 0 || c.realVolume < 0 || (i > 0 && c.time <= s.candles[i - 1].time)) throw new Error('Candles inválidos ou fora de ordem.');
      for (const optional of [c.sma9, c.sma21, c.ema9, c.vwap]) if (optional != null && !Number.isFinite(optional)) throw new Error('Indicador inválido.');
    }
  }
  return bundle;
}

export function absorptionMarkers(capture: AbsorptionCapture): AbsorptionMarker[] {
  return capture.transitions.flatMap((t, index) => {
    if (![POSSIBLE, CONFIRMED, INVALID, INSUFFICIENT].includes(t.state)) return [];
    return [{ id: `${capture.source_sha256}:${t.event_id}:${index}`, transition: t, index,
      timeMs: t.known_at_local_ms, utcInterval: t.capture_known_utc_interval_ms,
      price: [CONFIRMED, INVALID].includes(t.state) && t.reaction ? t.reaction.price : t.reference_price,
      color: t.state === POSSIBLE ? '#fdd835' : t.state === CONFIRMED ? (t.suspected_type === 'COMPRADORA' ? '#00c853' : '#ff1744')
        : t.state === INVALID ? '#ef6c00' : '#90a4ae',
      shape: t.state === POSSIBLE ? 'bubble' : t.state === CONFIRMED ? (t.suspected_type === 'COMPRADORA' ? 'up' : 'down')
        : t.state === INVALID ? 'cross' : 'diamond',
    } as AbsorptionMarker];
  });
}

/** Never use the final replay time or market epoch as a fallback for capture time. */
export function locateAbsorption(marker: AbsorptionMarker, session?: CandleSession): { placed?: PlacedAbsorption; reason?: string } {
  if (marker.timeMs == null || !marker.utcInterval) return { reason: 'Estado sem instante de captura conhecido (ex.: EOF sem timestamp).' };
  if (!session || !session.candles.length) return { reason: 'Sem candles WINZ26 com referência capture_utc comprovada para esta captura.' };
  const start = marker.utcInterval[0];
  const end = marker.utcInterval[1];
  if (marker.timeMs !== start) return { reason: 'Horário e faixa de conhecimento incompatíveis.' };
  const seconds = session.timeframe_seconds;
  const candleIndex = session.candles.findIndex(c => c.time * 1000 <= start && start < (c.time + seconds) * 1000);
  if (candleIndex < 0) return { reason: 'Sem candle no período em que o estado ficou conhecido; nenhuma aproximação aplicada.' };
  const candleTime = session.candles[candleIndex].time;
  // Do not resolve an interval spanning candles by choosing one arbitrarily.
  if (end > (candleTime + seconds) * 1000) return { reason: 'Faixa UTC atravessa a borda de candle; instante exato desconhecido.' };
  return { placed: { ...marker, candleIndex, candleTime, fraction: (start/1000 - candleTime)/seconds,
    widthFraction: (end - start)/1000/seconds } };
}

/** File order is authoritative for states in the same second or batch. */
export function visibleAbsorption(capture: AbsorptionCapture, cursorIndex: number) {
  return absorptionMarkers(capture).filter(m => m.index <= cursorIndex);
}
export function knownFinalState(capture: AbsorptionCapture, eventId: number, cursorIndex: number) {
  return capture.transitions.slice(0, cursorIndex + 1).filter(t => t.event_id === eventId).at(-1)?.state;
}

export function createAbsorptionOverlay() {
  let snapshot: { markers: PlacedAbsorption[]; selectedId?: string; focusId?: string } = { markers: [] };
  let select: (id: string) => void = () => {};
  const listeners = new Set<() => void>();
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    update(next: typeof snapshot, onSelect: typeof select) { snapshot = next; select = onSelect; listeners.forEach(l => l()); },
    select(id: string) { select(id); },
  };
}
export type AbsorptionOverlay = ReturnType<typeof createAbsorptionOverlay>;
