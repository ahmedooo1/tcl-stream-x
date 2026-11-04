/**
 * Very large playlists: the parser streams 300 000 entries chunk by chunk without keeping the text,
 * and the importer stops at its cap with a usable database. The cap of the app is MAX_ENTRIES
 * (250 000, as on Android); in Node the database is fake-indexeddb (slow), so the cap is lowered
 * here and the 200 000-entry import is measured in real Chrome by tools/e2e.mjs.
 */
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { describe, expect, it } from 'vitest';
import { ContentKind, M3uParser, classify } from '@shared/core/m3u';
import { Db, openDb } from '@shared/storage/db';
import { Vault } from '@shared/security/vault';
import { Importer, MAX_ENTRIES } from '@shared/core/importer';
import { XtreamClient } from '@shared/xtream/client';

const CAP = 3_000;
import { lineup } from '@shared/ui/lineup';

function* chunks(n: number, size = 64 * 1024): Generator<string> {
  let buf = '#EXTM3U\n';
  for (let i = 1; i <= n; i++) {
    const r = i % 10;
    buf += r < 6
      ? `#EXTINF:-1 tvg-id="c${i}" group-title="${['FR | Info', 'AR | News', 'UK | Sport'][i % 3]}",Chaîne ${i}\nhttp://cdn.example/live/${i}.m3u8\n`
      : r < 8 ? `#EXTINF:-1 group-title="VOD | Action",Film ${i}\nhttp://cdn.example/movie/${i}.mp4\n`
        : `#EXTINF:-1 group-title="Séries",Show ${i >> 5} S01E${String(i % 30).padStart(2, '0')}\nhttp://cdn.example/series/${i}.mkv\n`;
    if (buf.length >= size) { yield buf; buf = ''; }
  }
  if (buf) yield buf;
}

describe('very large playlists', () => {
  it('parses 300 000 entries in a stream, split anywhere', () => {
    const counts = [0, 0, 0];
    const t0 = Date.now();
    const p = new M3uParser((e) => { counts[classify(e)]++; });
    // Odd chunk boundaries: lines and even UTF-8 characters split across pushes.
    for (const c of chunks(300_000, 7_777)) p.push(c);
    p.end();
    const ms = Date.now() - t0;
    expect(p.stats.entries).toBe(300_000);
    expect(counts[ContentKind.LIVE]).toBe(180_000);
    expect(counts[ContentKind.MOVIE]).toBe(60_000);
    expect(counts[ContentKind.SERIES]).toBe(60_000);
    console.log(`parse 300k: ${ms} ms`);
    expect(ms).toBeLessThan(20_000);
  }, 60_000);

  it('imports up to the cap and pages through the result', async () => {
    const db = new Db(await openDb(new IDBFactory(), 'large'));
    expect(MAX_ENTRIES).toBe(250_000);
    const importer = new Importer(db, new Vault(db), new XtreamClient(), CAP);
    const t0 = Date.now();
    const p = await importer.importM3u('Big', { chunks: async (push) => { for (const c of chunks(CAP + 5_000)) push(c); } });
    console.log(`import ${p.count} entries into IndexedDB (fake-indexeddb, Node): ${Date.now() - t0} ms`);
    expect(p.count).toBe(CAP);
    expect(p.groups[ContentKind.LIVE].reduce((s, g) => s + g.count, 0)).toBe(1_800);
    const all = await lineup(db, p, ContentKind.LIVE, null, null, new Set());
    expect(all.total).toBe(1_800);
    const deep = await all.load(1_790, 20);
    expect(deep.length).toBe(10);
    expect(deep[deep.length - 1].name).toBe('Chaîne 3000');
    const fr = await lineup(db, p, ContentKind.LIVE, null, 'FR', new Set());
    expect(fr.total).toBe(600);
    const hidden = await lineup(db, p, ContentKind.LIVE, null, null, new Set(['UK | Sport']));
    expect(hidden.total).toBe(1_200);
    expect((await hidden.load(1_195, 10)).every((c) => c.groupTitle !== 'UK | Sport')).toBe(true);
    const found = await db.search('chaine 2995', 10, ContentKind.LIVE);
    expect(found[ContentKind.LIVE].map((c) => c.name)).toContain('Chaîne 2995');
  }, 600_000);
});
