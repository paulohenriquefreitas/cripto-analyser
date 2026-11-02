import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

for (const [app, entry] of [
  ['scanner', 'ScannerRoutes.tsx'],
  ['win-m5', 'WinRoutes.tsx'],
  ['three-candles', 'chartBootstrap.tsx'],
]) {
  test(`${app}: development React transform resolves Fast Refresh from shared toolchain`, async () => {
    const root = fileURLToPath(new URL(`../../apps/${app}/frontend/`, import.meta.url));
    const server = await createServer({
      root,
      configFile: `${root}/vite.config.mjs`,
      server: { middlewareMode: true, watch: null, ws: false },
      optimizeDeps: { noDiscovery: true, include: [] },
    });
    try {
      const file = fileURLToPath(new URL(`../src/standalone/${entry}`, import.meta.url)).replaceAll('\\', '/');
      const result = await server.transformRequest(`/@fs/${file}`);
      assert.ok(result?.code.includes('/@react-refresh'), 'React Fast Refresh must be enabled');
    } finally {
      await server.close();
    }
  });
}
