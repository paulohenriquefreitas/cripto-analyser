import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { readFile } from 'node:fs/promises';

// Serve/embed only this fixed offline artifact; no Java endpoint or arbitrary file access.
const absorptionReport = new URL('../panic-scanner/dom-captures/experimental-absorption-capture-clock.json', import.meta.url);

export default defineConfig({
  plugins: [react(), {
    name: 'absorption-offline-report',
    configureServer(server) {
      server.middlewares.use('/absorption/replay.json', (_request, response) => {
        void readFile(absorptionReport).then(data => {
          response.setHeader('Content-Type', 'application/json; charset=utf-8');
          response.setHeader('Cache-Control', 'no-store');
          response.end(data);
        }).catch(() => { response.statusCode = 404; response.end('Offline absorption report unavailable'); });
      });
    },
    async generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'absorption/replay.json', source: await readFile(absorptionReport) });
    },
  }],
  resolve: {
    alias: {
      '@': '/src',
    },
  },
  server: {
    port: 5173,
  },
});
