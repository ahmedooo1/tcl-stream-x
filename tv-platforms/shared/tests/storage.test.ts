// Database, vault and importer, on fake-indexeddb with a simulated Xtream panel (no real account).
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Db, openDb, S } from '@shared/storage/db';
import { Vault } from '@shared/security/vault';
import { Importer, ImportException } from '@shared/core/importer';
import { ContentKind } from '@shared/core/m3u';
import { normalize } from '@shared/core/text';
import { parseRef } from '@shared/xtream/urls';

let db: Db;
let vault: Vault;
let importer: Importer;

beforeEach(async () => {
  db = new Db(await openDb(new IDBFactory(), 'test'));
  vault = new Vault(db);
  importer = new Importer(db, vault);
});
afterEach(() => vi.unstubAllGlobals());

function m3u(n: number): string {
  let s = '#EXTM3U url-tvg="https://g.example/guide.xml"\n';
  for (let i = 1; i <= n; i++) {
    const group = i % 3 === 0 ? 'Films Action' : `News ${i % 4}`;
    s += `#EXTINF:-1 tvg-id="c${i}" group-title="${group}",Chaîne ${i}\nhttp://h.example/${i % 3 === 0 ? 'm/' + i + '.mp4' : 'live/' + i + '.ts'}\n`;
  }
  return s;
}

describe('Vault', () => {
  it('stores logins encrypted with a non-extractable key and reads them back', async () => {
    const id = await vault.store({ username: 'bob', password: 's3cret' });
    const row = await db.get<{ data: ArrayBuffer }>(S.vault, id);
    const raw = new TextDecoder().decode(new Uint8Array(row!.data));
    expect(raw).not.toContain('s3cret');
    expect(raw).not.toContain('bob');
    const c = await vault.load(id);
    expect(c?.username).toBe('bob');
    expect(c?.password).toBe('s3cret');
    expect(String(c)).toBe('XtreamCredentials(***)');
    expect(JSON.stringify({ c })).not.toContain('s3cret');
    const key = await db.get<{ key: CryptoKey }>(S.vault, 'k1');
    expect(key!.key.extractable).toBe(false);
    await expect(crypto.subtle.exportKey('raw', key!.key)).rejects.toBeTruthy();
  });
});

describe('Importer and database', () => {
  it('imports a playlist in batches, splits sections and counts categories', async () => {
    const text = m3u(2000);
    const p = await importer.importM3u('Test', { chunks: async (push) => { for (let i = 0; i < text.length; i += 1000) push(text.substring(i, i + 1000)); } });
    expect(p.count).toBe(2000);
    expect(p.epgUrls).toEqual(['https://g.example/guide.xml']);
    expect(await db.countOf(p, ContentKind.MOVIE, null)).toBe(666);
    expect(await db.countOf(p, ContentKind.LIVE, null)).toBe(1334);
    expect(p.groups[ContentKind.MOVIE]).toEqual([{ groupTitle: 'Films Action', count: 666 }]);
    const firstPage = await db.channelsOf(p, ContentKind.LIVE, null, 0, 50);
    expect(firstPage).toHaveLength(50);
    expect(firstPage[0].position).toBe(1);
    const groupPage = await db.channelsOf(p, ContentKind.LIVE, 'News 1', 0, 10);
    expect(groupPage.every((c) => c.groupTitle === 'News 1')).toBe(true);
    const groups = await db.groupsOf(p, ContentKind.LIVE);
    expect(groups.reduce((s, g) => s + g.count, 0)).toBe(1334);
  });

  it('keeps the old content when a re-import fails, and replaces it atomically when it works', async () => {
    const p1 = await importer.importM3u('T', { chunks: async (push) => push(m3u(10)) });
    await expect(importer.importM3u('T', { chunks: async () => { throw new ImportException('NETWORK'); } }, p1)).rejects.toBeTruthy();
    expect(await db.countOf(p1, ContentKind.LIVE, null)).toBe(7);
    const p2 = await importer.importM3u('T', { chunks: async (push) => push(m3u(4)) }, p1);
    expect(p2.generation).toBe(p1.generation + 1);
    expect(await db.countOf(p2, ContentKind.LIVE, null)).toBe(3);
    // old generation removed
    expect(await db.count(S.channels, null, null)).toBe(4);
  });

  it('rejects an empty playlist', async () => {
    await expect(importer.importM3u('E', { chunks: async (push) => push('#EXTM3U\n') })).rejects.toMatchObject({ error: 'EMPTY' });
  });

  it('zaps through a lineup, wraps around and finds a channel by number', async () => {
    const p = await importer.importM3u('Z', { chunks: async (push) => push(m3u(6)) });
    const live = await db.channelsOf(p, ContentKind.LIVE, null, 0, 10);
    const last = live[live.length - 1];
    expect((await db.neighbour(live[0], null, 1))?.position).toBe(live[1].position);
    expect((await db.neighbour(last, null, 1))?.position).toBe(live[0].position);
    expect((await db.neighbour(live[0], null, -1))?.position).toBe(last.position);
    expect((await db.byNumber(p, ContentKind.LIVE, 2))?.name).toBe('Chaîne 2');
  });

  it('searches without accents over every playlist', async () => {
    await importer.importM3u('S', { chunks: async (push) => push(m3u(30)) });
    const r = await db.search(normalize('chaine 1'), 20);
    expect(r[ContentKind.LIVE].map((c) => c.name)).toContain('Chaîne 1');
    expect(r[ContentKind.LIVE].every((c) => c.nameNorm.includes('chaine 1'))).toBe(true);
  });

  it('keeps favorites across a re-import (stable keys)', async () => {
    const p = await importer.importM3u('F', { chunks: async (push) => push(m3u(5)) });
    const ch = (await db.channelsOf(p, ContentKind.LIVE, null, 0, 1))[0];
    expect(await db.toggleFavorite(ch.channelKey)).toBe(true);
    await importer.importM3u('F', { chunks: async (push) => push(m3u(5)) }, p);
    const favs = await db.favorites(10);
    expect(favs.map((f) => f.name)).toEqual([ch.name]);
  });
});

describe('Xtream account import (simulated panel)', () => {
  function panel(liveCount: number) {
    const live = Array.from({ length: liveCount }, (_, i) => ({ stream_id: i + 1, name: `Live ${i + 1}`, category_id: '1', epg_channel_id: `l${i}` }));
    const movies = [{ stream_id: 900, name: 'Film', category_id: '2', container_extension: 'mkv', rating: '7.5', added: '1700000000', stream_icon: 'http://i/f.jpg' }];
    const seen: string[] = [];
    const fetchMock = vi.fn(async (url: string) => {
      seen.push(url);
      const u = new URL(url);
      const action = u.searchParams.get('action');
      if (u.searchParams.get('password') !== 'pw') return new Response(JSON.stringify({ user_info: { auth: 0 } }));
      const body = action == null
        ? { user_info: { auth: 1, status: 'Active', exp_date: String(Math.floor(Date.now() / 1000) + 86400), max_connections: '2', allowed_output_formats: ['m3u8', 'ts'] } }
        : action === 'get_live_categories' ? [{ category_id: '1', category_name: 'FR| NEWS' }]
        : action === 'get_vod_categories' ? [{ category_id: '2', category_name: 'Films' }]
        : action === 'get_series_categories' ? []
        : action === 'get_live_streams' ? live
        : action === 'get_vod_streams' ? movies
        : action === 'get_series' ? []
        : null;
      return new Response(JSON.stringify(body));
    });
    vi.stubGlobal('fetch', fetchMock);
    return seen;
  }

  it('imports live and films with login-free references, login in the vault only', async () => {
    panel(1200);
    const p = await importer.importXtream('Panel', 'h.example:8080', { username: 'u', password: 'pw' });
    expect(p.kind).toBe('xtream');
    expect(p.source).toBe('http://h.example:8080');
    expect(await db.countOf(p, ContentKind.LIVE, null)).toBe(1200);
    const film = (await db.channelsOf(p, ContentKind.MOVIE, null, 0, 1))[0];
    expect(film.url).toBe(`xtream://${p.id}/movie/900.mkv`);
    expect(parseRef(film.url)?.ref).toBe('movie');
    expect(film.rating).toBe(7.5);
    const everything = JSON.stringify(await db.all(S.channels)) + JSON.stringify(await db.all(S.playlists));
    expect(everything).not.toContain('pw');
    expect((await vault.load(p.vaultId!))?.password).toBe('pw');
    expect(p.accountSummary?.startsWith('Active|')).toBe(true);
  });

  it('refuses a wrong login without creating anything', async () => {
    panel(1);
    await expect(importer.importXtream('Bad', 'h.example', { username: 'u', password: 'nope' })).rejects.toMatchObject({ error: 'AUTH' });
    expect(await db.playlists()).toHaveLength(0);
  });

  it('recognises a pasted get.php link as an account', async () => {
    panel(3);
    const p = await importer.importM3u('Link', { url: 'http://h.example/get.php?username=u&password=pw&type=m3u_plus' });
    expect(p.kind).toBe('xtream');
    expect(p.count).toBe(4);
  });
});
