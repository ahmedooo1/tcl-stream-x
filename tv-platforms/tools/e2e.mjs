/**
 * End-to-end check of a built edition in real Chrome, loaded from file:// like on the TV, driven only
 * with remote keys (arrows, OK, Back). Network is mocked: a generated playlist of N entries, the
 * Internet Archive and ESPN answers. Writes screenshots and measured times.
 *
 *   node tools/e2e.mjs [samsung|lg] [entries] [outDir]
 *
 * This is not a test on a TV: the TV engine, its memory and its video decoder are not involved.
 */
import { chromium } from 'playwright-core';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const edition = process.argv[2] ?? 'samsung';
const N = Number(process.argv[3] ?? 200000);
const out = resolve(process.argv[4] ?? resolve(ROOT, 'docs/screenshots'));
mkdirSync(out, { recursive: true });

function playlist(n) {
  const groupsLive = ['FR | Info', 'FR | Sport', 'AR | News', 'AR | Divertissement', 'UK | Sport', 'DE | Kino', 'TR | Haber', 'ES | Deportes'];
  const parts = ['#EXTM3U url-tvg="http://big.example/guide.xml"\n'];
  for (let i = 1; i <= n; i++) {
    const r = i % 10;
    if (r < 6) {
      const g = groupsLive[i % groupsLive.length];
      parts.push(`#EXTINF:-1 tvg-id="ch${i}" tvg-logo="http://img.example/${i}.png" group-title="${g}",Chaîne ${i} HD\nhttp://cdn.example/live/${i}.m3u8\n`);
    } else if (r < 8) {
      parts.push(`#EXTINF:-1 tvg-logo="http://img.example/m${i}.jpg" group-title="VOD | ${i % 2 ? 'Action' : 'Comédie'}",Film ${i} (19${50 + (i % 50)})\nhttp://cdn.example/movie/${i}.mp4\n`);
    } else {
      const show = Math.floor(i / 40);
      parts.push(`#EXTINF:-1 group-title="Séries | ${show % 2 ? 'Drame' : 'Animation'}",Série ${show} S0${1 + (i % 3)}E${String(i % 24).padStart(2, '0')}\nhttp://cdn.example/series/${i}.mkv\n`);
    }
  }
  return parts.join('');
}

const metrics = { edition, entries: N, engine: 'Chrome (file://), not a TV' };
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', args: ['--allow-file-access-from-files'] });
// bypassCSP only lets the harness evaluate its own checks; the app itself runs under its CSP.
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, bypassCSP: true });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));

const body = playlist(N);
metrics.playlistBytes = body.length;
await page.route('http://big.example/**', (r) => r.fulfill({ status: 200, contentType: 'audio/x-mpegurl', body, headers: { 'access-control-allow-origin': '*' } }));
await page.route('http://img.example/**', (r) => r.abort());
await page.route('http://cdn.example/**', (r) => r.abort());
await page.route('https://archive.org/**', (r) => {
  const u = r.request().url();
  const json = u.includes('advancedsearch')
    ? { response: { docs: Array.from({ length: 12 }, (_, i) => ({ identifier: `pd_${i}`, title: `Classique ${i + 1}`, year: 1925 + i, description: i === 0 ? 'Film muet restauré, domaine public.' : undefined })) } }
    : { files: [{ name: 'film.mp4', format: 'h.264', original: 'film.mpeg', length: '4200' }, { name: 'film.fr.srt' }] };
  return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(json), headers: { 'access-control-allow-origin': '*' } });
});
await page.route('https://site.api.espn.com/**', (r) => r.fulfill({ status: 500, body: 'mock' }));
await page.route('https://**', (r) => r.abort());

const key = async (k, n = 1, wait = 120) => { for (let i = 0; i < n; i++) { await page.keyboard.press(k); await page.waitForTimeout(wait); } };
const T0 = Date.now();
const log = (m) => console.error(`${((Date.now() - T0) / 1000).toFixed(1)}s ${m}`);
const shot = (name) => { log(name); return page.screenshot({ path: resolve(out, `${edition}-${name}.png`) }); };
const focusedText = () => page.evaluate(() => document.querySelector('.focused')?.textContent?.trim().slice(0, 80) ?? null);
/** Opens a section from the rail: Left until the rail has the focus, then Up / Down to the entry. */
const SECTIONS = ['home', 'live', 'movies', 'series', 'sports', 'animation', 'local', 'search', 'playlists', 'settings'];
async function goSection(route) {
  for (let i = 0; i < 8; i++) {
    if (await page.evaluate(() => !!document.querySelector('#rail .focused'))) break;
    await key('ArrowLeft');
  }
  const at = await page.evaluate(() => document.querySelector('#rail .focused')?.getAttribute('data-k'));
  if (at == null) throw new Error('rail not reached');
  for (let i = 0; i < 12; i++) {
    const cur = await page.evaluate(() => document.querySelector('#rail .focused')?.getAttribute('data-k'));
    if (cur === route) break;
    await key(SECTIONS.indexOf(route) > SECTIONS.indexOf(cur) ? 'ArrowDown' : 'ArrowUp', 1, 160);
  }
  const final = await page.evaluate(() => document.querySelector('#rail .focused')?.getAttribute('data-k'));
  if (final !== route) throw new Error(`rail: wanted ${route}, at ${final}`);
  await key('Enter', 1, 700);
}

await page.goto('file://' + resolve(ROOT, 'dist', edition === 'samsung' ? 'samsung' : 'lg', 'index.html'));
await page.waitForTimeout(1500);
await shot('01-home');

// Import the big playlist through the Playlists screen, with the remote only.
await goSection('playlists');
await page.waitForTimeout(400);
for (let i = 0; i < 6 && !(await focusedText())?.includes('URL'); i++) await key('ArrowDown');
await key('Enter', 1, 400);
await key('Enter', 1, 200); // OK on the field: the TV keyboard opens
await page.keyboard.type('http://big.example/list.m3u');
await key('ArrowDown');
await key('Enter', 1, 400);
await key('Enter', 1, 200);
await page.keyboard.type('Grande liste');
await key('ArrowDown');
const t0 = Date.now();
await key('Enter', 1, 100);
await page.waitForFunction(() => /importées|échec/.test(document.querySelector('.screen')?.textContent ?? ''), null, { timeout: 900000, polling: 500 });
metrics.importStatus = await page.evaluate(() => document.querySelector('.screen .status')?.textContent ?? '');
metrics.importMs = Date.now() - t0;
await shot('02-playlists');

// Live: open, list, scroll far down, filter by country.
await goSection('live');
await page.waitForTimeout(1200);
const t1 = Date.now();
await shot('03-live');
await key('ArrowRight', 2);
for (let i = 0; i < 60; i++) await page.keyboard.press('ArrowDown');
await page.waitForTimeout(400);
metrics.scroll60RowsMs = Date.now() - t1;
await shot('04-live-scrolled');
metrics.liveFocused = await focusedText();

// Country / language strip above the list.
await key('ArrowUp', 70, 15);
await page.waitForTimeout(300);
await key('ArrowUp', 2);
const strip = await page.evaluate(() => Array.from(document.querySelectorAll('.rstrip .chip')).map((c) => c.textContent?.trim()));
metrics.regions = strip;
await key('ArrowRight');
await key('Enter', 1, 800);
await shot('05-live-region');
metrics.regionTitle = await page.evaluate(() => document.querySelector('.lhead')?.textContent?.replace(/\s+/g, ' ').trim());

// Films: start page with the user's rows and public-domain rows.
await key('Escape', 1, 400);
await goSection('movies');
await page.waitForTimeout(2500);
await shot('06-movies');

// Series: M3U episodes grouped by show.
await goSection('series');
await page.waitForTimeout(800);
metrics.seriesFocusBefore = await page.evaluate(() => { const f = document.querySelector('.focused'); return f ? `${f.closest('#rail') ? 'rail' : f.closest('.secs') ? 'secs' : f.closest('.content') ? 'content' : 'other'}: ${f.textContent.trim().slice(0, 40)}` : null; });
for (let i = 0; i < 4 && !(await page.evaluate(() => !!document.querySelector('.secs .focused'))); i++) await key('ArrowLeft');
for (let i = 0; i < 20 && !(await focusedText())?.includes('Drame'); i++) await key('ArrowDown');
metrics.seriesSection = await focusedText();
await key('Enter', 1, 2500);
await shot('07-series-shows');

// Search over everything.
await goSection('search');
await key('Enter', 1, 200);
const t2 = Date.now();
await page.keyboard.type(`chaine ${N - 5}`);
await page.waitForFunction(() => document.querySelectorAll('.res .row').length > 0, null, { timeout: 120000 });
metrics.searchMs = Date.now() - t2;
metrics.searchResults = await page.evaluate(() => document.querySelectorAll('.res .row').length);
await shot('08-search');

// Settings.
await key('Escape');
await goSection('settings');
await page.waitForTimeout(600);
await shot('09-settings');

metrics.pageErrors = errors;
metrics.heapMB = await page.evaluate(() => Math.round(((performance).memory?.usedJSHeapSize ?? 0) / 1048576));
writeFileSync(resolve(out, `${edition}-metrics.json`), JSON.stringify(metrics, null, 2));
console.log(JSON.stringify(metrics, null, 2));
await browser.close();
if (errors.length) process.exit(1);
