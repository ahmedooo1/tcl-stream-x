/**
 * Build settings shared by the TV editions. TV web apps load from file:// (Tizen WRT, webOS), where
 * Chromium refuses ES module scripts, so the app is one classic script (IIFE, deferred) and every
 * asset path is relative.
 */
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import type { Plugin, UserConfig } from 'vite';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const VERSION: string = JSON.parse(readFileSync(resolve(ROOT, 'package.json'), 'utf8')).version;

/** Turns Vite's module script tag into a deferred classic one. */
function classicScript(): Plugin {
  return {
    name: 'tv-classic-script',
    enforce: 'post',
    transformIndexHtml(html) {
      return html
        .replace(/<script type="module" crossorigin src="([^"]+)"><\/script>/g, '<script defer src="$1"></script>')
        .replace(/<link rel="stylesheet" crossorigin href=/g, '<link rel="stylesheet" href=')
        .replace(/<link rel="modulepreload"[^>]*>/g, '');
    },
  };
}

/** Copies the platform manifest (version filled in) and icon next to index.html. */
function manifest(files: { from: string; to: string; version?: boolean }[], outDir: string): Plugin {
  return {
    name: 'tv-manifest',
    closeBundle() {
      mkdirSync(outDir, { recursive: true });
      for (const f of files) {
        if (f.version) writeFileSync(resolve(outDir, f.to), readFileSync(f.from, 'utf8').split('@VERSION@').join(VERSION));
        else copyFileSync(f.from, resolve(outDir, f.to));
      }
    },
  };
}

export function tvConfig(edition: 'samsung-tizen' | 'lg-webos', files: { from: string; to: string; version?: boolean }[]): UserConfig {
  const outDir = resolve(ROOT, 'dist', edition === 'samsung-tizen' ? 'samsung' : 'lg');
  return {
    root: resolve(ROOT, edition),
    base: './',
    publicDir: false,
    define: { __APP_VERSION__: JSON.stringify(VERSION) },
    resolve: { alias: { '@shared': resolve(ROOT, 'shared/src') } },
    plugins: [classicScript(), manifest(files.map((f) => ({ ...f, from: resolve(ROOT, edition, f.from) })), outDir)],
    build: {
      outDir,
      emptyOutDir: true,
      // Samsung 2023+ (Tizen 7, Chromium 94) and LG webOS 23+ (Chromium 94): see docs/PLATFORMS.md.
      target: 'chrome94',
      cssTarget: 'chrome94',
      modulePreload: false,
      assetsInlineLimit: 0,
      sourcemap: false,
      rollupOptions: {
        input: resolve(ROOT, edition, 'index.html'),
        output: { format: 'iife', inlineDynamicImports: true, entryFileNames: 'app.js', assetFileNames: 'assets/[name]-[hash][extname]' },
      },
    },
  };
}
