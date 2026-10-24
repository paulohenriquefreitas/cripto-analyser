import { z } from 'zod';

const quantity = z.number().finite().nonnegative();
const price = z.number().finite().positive();
const region = z.object({ low: price, high: price });
export const profileLevelSchema = z.object({
  price, totalVolume: quantity, buyVolume: quantity, sellVolume: quantity, ambiguousVolume: quantity,
  delta: z.number().finite(), buyPercent: quantity.max(100), sellPercent: quantity.max(100),
  ambiguousPercent: quantity.max(100), trades: z.number().int().safe().nonnegative(),
});
export const volumeProfileSchema = z.object({
  symbol: z.string(), session: z.string(), timezone: z.literal('America/Sao_Paulo'),
  volumeSource: z.literal('MT5_VOLUME_REAL'), volumeUnit: z.literal('MT5_VOLUME_REAL_UNITS'), tickSize: price,
  status: z.enum(['COMPLETE', 'PARTIAL', 'INCOMPLETE']), partial: z.boolean(), integrityCompromised: z.boolean(),
  coverageReasons: z.array(z.string()), faults: z.array(z.string()),
  firstTimeMsc: z.number().int().safe().nullable(), lastTimeMsc: z.number().int().safe().nullable(),
  trades: z.number().int().safe().nonnegative(), totalVolume: quantity, delta: z.number().finite(),
  poc: price.nullable(), vah: price.nullable(), val: price.nullable(),
  valueAreaVolume: quantity, valueAreaPercent: quantity.max(100),
  levels: z.array(profileLevelSchema), hvn: z.array(region), lvn: z.array(region),
});
export const volumeProfileViewSchema = z.object({
  connected: z.boolean(), lastReceivedUtcMsc: z.number().int().safe().nullable(),
  profile: volumeProfileSchema.nullable(), previousSession: volumeProfileSchema.nullable(),
  sourceFaults: z.array(z.string()),
});
export type ProfileLevel = z.infer<typeof profileLevelSchema>;
export type VolumeProfileSnapshot = z.infer<typeof volumeProfileSchema>;
export type VolumeProfileView = z.infer<typeof volumeProfileViewSchema>;

/** Bars use one maximum for every price; ambiguous executions retain their own segment. */
export function profileBars(profile: VolumeProfileSnapshot) {
  const maximum = Math.max(0, ...profile.levels.map(level => level.totalVolume));
  return [...profile.levels].reverse().map(level => ({
    ...level, poc: level.price === profile.poc, vah: level.price === profile.vah, val: level.price === profile.val,
    buyWidth: maximum ? level.buyVolume / maximum * 100 : 0,
    sellWidth: maximum ? level.sellVolume / maximum * 100 : 0,
    ambiguousWidth: maximum ? level.ambiguousVolume / maximum * 100 : 0,
  }));
}

export function profileStatus(profile: VolumeProfileSnapshot): string {
  if (profile.integrityCompromised || profile.status === 'INCOMPLETE') return 'Integridade comprometida';
  return profile.status === 'COMPLETE' ? 'Sessão completa' : 'Sessão parcial';
}
