import { defineConfig, loadEnv } from '../../../frontend/node_modules/vite/dist/node/index.js';
import { createRequire } from 'node:module';

// Load from the shared toolchain without bundling the plugin into this app's config.
const requireTool = createRequire(new URL('../../../frontend/package.json', import.meta.url));
const react = requireTool('@vitejs/plugin-react');
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';

export default defineConfig(({ mode }) => {
  const env=loadEnv(mode, process.cwd(), "");
  return {
  define: { 'import.meta.env.VITE_API_URL': JSON.stringify(env.VITE_API_URL ?? 'http://127.0.0.1:8082'), 'import.meta.env.VITE_USE_MOCK_API': JSON.stringify(env.VITE_USE_MOCK_API ?? 'false') },
  publicDir: fileURLToPath(new URL("../../../frontend/public", import.meta.url)),
  plugins: [react(), { name: "absorption-offline-report",
    configureServer(server) { server.middlewares.use("/absorption/replay.json", (_req,res) => {
      void readFile(new URL("../../../panic-scanner/dom-captures/experimental-absorption-capture-clock.json",import.meta.url)).then(data=>{res.setHeader("Content-Type","application/json");res.end(data);}).catch(()=>{res.statusCode=404;res.end();});
    }); },
    async generateBundle() { this.emitFile({type:"asset",fileName:"absorption/replay.json",source:await readFile(new URL("../../../panic-scanner/dom-captures/experimental-absorption-capture-clock.json",import.meta.url))}); }
  }, { name: 'application-boundary', generateBundle(_options, bundle) {
    const modules=Object.values(bundle).filter(item=>item.type==='chunk').flatMap(chunk=>Object.keys(chunk.modules)).map(id=>id.replaceAll('\\','/'));
    const forbidden=['/features/scanner/', '/features/stock/', '/features/dashboard/', '/features/threecandles/'];
    const violations=modules.filter(id=>forbidden.some(part=>id.includes(part)));
    if(violations.length) throw new Error('Cross-application runtime imports: '+violations.join(', '));
    this.emitFile({type:'asset',fileName:'application-modules.json',source:JSON.stringify(modules,null,2)});
  } }],
  resolve: { alias: {
    '@': fileURLToPath(new URL('../../../frontend/src', import.meta.url)),
    'react': fileURLToPath(new URL('../../../frontend/node_modules/react', import.meta.url)),
    'react-dom': fileURLToPath(new URL('../../../frontend/node_modules/react-dom', import.meta.url)),
  } },
  server: { port: 5174, strictPort: true, fs: { allow: [fileURLToPath(new URL('../../../', import.meta.url))] } },
  preview: { port: 4174, strictPort: true },
};
});
