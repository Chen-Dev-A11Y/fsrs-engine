/**
 * Vite configuration.
 *
 * Deliberately plain JavaScript (`.mjs`) rather than TypeScript: Vite loads a
 * `.ts` config by bundling it with esbuild, which needs a child process. A
 * `.mjs` config is imported natively by Node, so no bundler is involved in
 * starting Vite at all. Application code is still fully TypeScript - this only
 * affects the build configuration file itself.
 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  /**
   * Load this file natively instead of letting Vite bundle it with esbuild.
   *
   * Vite's default config loader spawns esbuild (blocked in restricted
   * environments), and its `runner` loader copies the config into a temp
   * directory that it then tries to `fs.watch` — which fails with `EBUSY` here.
   * A plain `.mjs` config can simply be imported, so no bundler and no temp
   * directory are involved.
   */
  configLoader: 'native',
  plugins: [vue()],
  resolve: {
    alias: {
      // Resolved from this file's own URL, so no Node path helpers are needed.
      '@': decodeURIComponent(new URL('./src', import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1'),
    },
  },
  /**
   * Dependencies are declared explicitly so Vite never has to *discover* them.
   *
   * Discovery and the dev-server pre-bundle both call
   * `esbuild.build({ bundle: true })`, which needs esbuild's child process. In
   * environments where that process cannot start (see
   * scripts/esbuild-loader.cjs) the scan fails with
   * `The working directory "..." is not an absolute path`.
   *
   * Setting `FSRS_NO_DEP_OPTIMIZE=1` turns pre-bundling off completely, which
   * lets the dev server serve dependencies straight from `node_modules`. Slower
   * to start and to load, but fully functional — used to verify the UI in a
   * restricted environment. On a normal machine it is unnecessary.
   */
  optimizeDeps: process.env.FSRS_NO_DEP_OPTIMIZE
    ? { noDiscovery: true, include: [] }
    : {
        include: [
          'vue',
          'vue-router',
          'pinia',
          'axios',
          'element-plus',
          'echarts/core',
          'echarts/charts',
          'echarts/components',
          'echarts/renderers',
        ],
      },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
})
