import { defineConfig, loadEnv } from '../../../frontend/node_modules/vite/dist/node/index.js';
import { createRequire } from 'node:module';

// Load from the shared toolchain without bundling the plugin into this app's config.
const requireTool = createRequire(new URL('../../../frontend/package.json', import.meta.url));
const react = requireTool('@vitejs/plugin-react');
import { fileURLToPath } from 'node:url';

export default defineConfig(({ mode }) => {
  const env=loadEnv(mode, process.cwd(), "");
  return {
  define: { 'import.meta.env.VITE_API_URL': JSON.stringify(env.VITE_API_URL ?? 'http://127.0.0.1:8083'), 'import.meta.env.VITE_USE_MOCK_API': JSON.stringify(env.VITE_USE_MOCK_API ?? 'false') },
  plugins: [react(), { name: 'application-boundary', generateBundle(_options, bundle) {
    const modules=Object.values(bundle).filter(item=>item.type==='chunk').flatMap(chunk=>Object.keys(chunk.modules)).map(id=>id.replaceAll('\\','/'));
    const forbidden=['/features/scanner/', '/features/stock/', '/features/dashboard/', '/features/replay/'];
    const violations=modules.filter(id=>forbidden.some(part=>id.includes(part)) || (id.includes('/features/win/') && !id.endsWith('/SwingPivotPrimitive.ts')));
    if(violations.length) throw new Error('Cross-application runtime imports: '+violations.join(', '));
    this.emitFile({type:'asset',fileName:'application-modules.json',source:JSON.stringify(modules,null,2)});
  } }],
  resolve: { alias: {
    '@': fileURLToPath(new URL('../../../frontend/src', import.meta.url)),
    'react': fileURLToPath(new URL('../../../frontend/node_modules/react', import.meta.url)),
    'react-dom': fileURLToPath(new URL('../../../frontend/node_modules/react-dom', import.meta.url)),
  } },
  server: { port: 5175, strictPort: true, fs: { allow: [fileURLToPath(new URL('../../../', import.meta.url))] } },
  preview: { port: 4175, strictPort: true },
};
});
