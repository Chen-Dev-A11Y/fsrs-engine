/**
 * esbuild compatibility shim — works around a *restricted environment*, and is a
 * no-op on a normal developer machine.
 *
 * Why this file exists
 * --------------------
 * Vite cannot transform TypeScript without esbuild, and the `esbuild` npm package
 * always talks to a **child process** over pipes:
 *
 *     spawn(binary, args, { stdio: ['pipe', 'pipe', 'inherit'] })
 *
 * Some locked-down environments (including the sandbox this project was built
 * in) refuse `spawn` with piped stdio and fail with `EPERM`. There, `vite build`
 * and `vite dev` die before a single module is read:
 *
 *     Error: spawn EPERM
 *       at ensureServiceIsRunning (.../esbuild/lib/main.js)
 *       at bundleConfigFile (.../vite/dist/node/chunks/...)
 *
 * A second, unrelated pipe is involved: Vite probes for mapped network drives on
 * Windows via `exec('net use')`, which throws the same `EPERM` *outside* Vite's
 * own try/catch.
 *
 * What it does
 * ------------
 * 1. Wraps `require('esbuild')` in a transparent proxy. Every call goes to
 *    **native esbuild first**; the first spawn failure permanently switches the
 *    process to `esbuild-wasm`'s pure-WASM build running **in-thread** (no
 *    worker, no child process).
 * 2. Answers Vite's `net use` drive probe with "no mapped drives" on Windows.
 *
 * Scope of the fallback
 * ---------------------
 * The WASM build can `transform()` (transpiling `.ts` / `.vue` sources), which is
 * what a production build needs once the config is loaded natively — that is why
 * `vite.config.mjs` is plain JavaScript and the scripts pass
 * `--configLoader runner`. It cannot `build({ bundle: true })`, so Vite's
 * dependency *discovery* / pre-bundle step is unavailable; that is why
 * `optimizeDeps.include` lists every dependency explicitly.
 *
 * On a normal machine neither path is exercised: the proxy forwards to native
 * esbuild and behaviour is identical to not having this file at all.
 *
 * Usage: see the `dev` / `typecheck` / `build` / `preview` scripts in package.json.
 */

'use strict'

const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const childProcess = require('node:child_process')

const NATIVE_REQUEST = 'esbuild'
const WASM_BROWSER = 'esbuild-wasm/lib/browser.js'
const WASM_WASM = 'esbuild-wasm/esbuild.wasm'

// --------------------------------------------------------------------------
// 1. Neutralise Vite's mapped-network-drive probe (Windows only)
// --------------------------------------------------------------------------

if (process.platform === 'win32' && typeof childProcess.exec === 'function') {
  const originalExec = childProcess.exec
  childProcess.exec = function guardedExec(command, options, callback) {
    if (typeof command === 'string' && command.trim() === 'net use') {
      const done = typeof options === 'function' ? options : callback
      if (typeof done === 'function') {
        const error = new Error('spawn net ENOENT (skipped in this environment)')
        error.code = 'ENOENT'
        setImmediate(() => done(error, '', ''))
      }
      return undefined
    }
    return originalExec.call(this, command, options, callback)
  }
}

// --------------------------------------------------------------------------
// 2. Transparent esbuild proxy with an in-thread WASM fallback
// --------------------------------------------------------------------------

/** Async API surface Vite uses. */
const ASYNC_METHODS = ['transform', 'build', 'formatMessages', 'analyzeMetafile', 'context']

/** Sync counterparts: forwarded to native esbuild, unavailable in the fallback. */
const SYNC_METHODS = ['transformSync', 'buildSync', 'formatMessagesSync', 'analyzeMetafileSync']

const root = path.resolve(__dirname, '..')
const originalLoad = Module._load

let mode = 'native'
let wasmApiPromise = null
let nativeModule = null
let proxy = null

async function loadWasmEsbuild() {
  // The browser build assumes a worker/web environment; `self` and `location`
  // shims let its in-thread branch run under Node. No `globalThis.fs` bridge is
  // installed on purpose: esbuild's Go runtime boots from the in-thread branch's
  // own `globalThis` object, and supplying a partial `fs` there makes it panic.
  // Only `transform()` is used through this path, and it needs no filesystem.
  if (!globalThis.self) globalThis.self = globalThis
  if (!globalThis.location) globalThis.location = { href: `file://${__filename}`, protocol: 'file:' }

  const wasmBytes = fs.readFileSync(require.resolve(WASM_WASM))
  const browser = require(WASM_BROWSER)
  await browser.initialize({ wasmModule: await WebAssembly.compile(wasmBytes), worker: false })
  return browser
}

function wasmApi() {
  if (!wasmApiPromise) wasmApiPromise = loadWasmEsbuild()
  return wasmApiPromise
}

/** Load the real package with the hook suspended. */
function loadNative() {
  if (nativeModule) return nativeModule
  Module._load = originalLoad
  try {
    nativeModule = require(NATIVE_REQUEST)
  } finally {
    Module._load = hookedLoad
  }
  return nativeModule
}

function isSpawnFailure(error) {
  const message = String((error && error.message) || error)
  return (error && error.code === 'EPERM') || /EPERM/.test(message) || /spawn/.test(message)
}

/**
 * Copies the native module's exports onto the proxy.
 *
 * Getter-only accessors are materialised as plain writable properties; `version`
 * is redefined explicitly by the caller.
 */
function copyExports(target, source) {
  for (const key of Object.getOwnPropertyNames(source)) {
    if (key === 'default' || key in target) continue
    if (SYNC_METHODS.includes(key)) continue
    const descriptor = Object.getOwnPropertyDescriptor(source, key)
    if (!descriptor) continue
    if (!descriptor.writable && !descriptor.set) {
      try {
        Object.defineProperty(target, key, {
          value: source[key],
          writable: true,
          enumerable: descriptor.enumerable,
          configurable: true,
        })
      } catch {
        /* not copyable - ignore */
      }
      continue
    }
    try {
      Object.defineProperty(target, key, descriptor)
    } catch {
      /* not copyable - ignore */
    }
  }
}

function buildProxy() {
  if (proxy) return proxy

  const native = loadNative()
  const nativeExports = native.default && typeof native.default === 'object' ? native.default : native

  const target = {}
  copyExports(target, nativeExports)
  Object.defineProperty(target, 'version', {
    value: nativeExports.version,
    writable: true,
    enumerable: true,
    configurable: true,
  })

  target.initialize = async () => {
    await wasmApi()
    return undefined
  }
  target.stop = async () => {
    if (mode === 'wasm') return (await wasmApi()).stop()
    return undefined
  }

  for (const method of ASYNC_METHODS) {
    target[method] = async (...args) => {
      if (process.env.FSRS_ESBUILD_TRACE) {
        const options = args[0]
        const shape =
          method === 'transform'
            ? `loader=${args[1] && args[1].loader} file=${(args[1] && args[1].sourcefile) || ''}`
            : `bundle=${options && options.bundle} absWorkingDir=${options && options.absWorkingDir}`
        console.warn(`[esbuild-loader:trace] ${method}(${shape})`)
      }
      if (mode === 'wasm') return (await wasmApi())[method](...args)
      try {
        return await nativeExports[method](...args)
      } catch (error) {
        if (!isSpawnFailure(error) || typeof nativeExports[method] !== 'function') throw error
        mode = 'wasm'
        console.warn(
          '[esbuild-loader] native esbuild cannot spawn here (spawn EPERM) — switching to the ' +
            'in-thread esbuild-wasm fallback for the rest of this process.',
        )
        return (await wasmApi())[method](...args)
      }
    }
  }

  for (const method of SYNC_METHODS) {
    target[method] = (...args) => {
      if (mode === 'wasm') {
        throw new Error(`[esbuild-loader] ${method}() is unavailable in the esbuild-wasm fallback`)
      }
      return nativeExports[method](...args)
    }
  }

  proxy = target
  return proxy
}

function hookedLoad(request, parent, isMain) {
  if (request === NATIVE_REQUEST) return buildProxy()
  return originalLoad.call(this, request, parent, isMain)
}

Module._load = hookedLoad

/**
 * Seed the require cache so `import 'esbuild'` — which does not necessarily go
 * through `Module._load` — also resolves to the proxy. Vite consumes the package
 * through a generated ESM facade that reads `module.exports`, hence the
 * `_isESM` / `default` shape.
 */
try {
  const esbuildPath = require.resolve(NATIVE_REQUEST)
  const target = buildProxy()
  const replacement = { ...target, default: target }
  Object.defineProperty(replacement, '_isESM', { value: true })
  Object.defineProperty(replacement, '__esModule', { value: true })
  require.cache[esbuildPath] = {
    id: esbuildPath,
    filename: esbuildPath,
    loaded: true,
    exports: replacement,
    children: [],
    paths: [],
  }
} catch (error) {
  console.warn('[esbuild-loader] could not pre-seed the esbuild cache:', error && error.message)
}

module.exports = {
  get mode() {
    return mode
  },
  root,
}
