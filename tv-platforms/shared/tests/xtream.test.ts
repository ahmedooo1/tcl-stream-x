// Port of XtreamTest.kt cases plus the streaming JSON reader.
import { describe, expect, it } from 'vitest';
import { fromPlaylistLink, makeRef, normalizeServer, parseRef, redact, resolveRef } from '@shared/xtream/urls';
import { JsonArrayStream } from '@shared/core/jsonstream';
import { mapEntry, parseSeriesInfo, xtreamKey } from '@shared/xtream/client';
import { cleanPlot, initialsOf } from '@shared/core/text';

const login = { username: 'user name', password: 'p@ss/word' };

describe('Xtream URLs', () => {
  it('normalizes what people paste', () => {
    expect(normalizeServer('host.example:8080')).toBe('http://host.example:8080');
    expect(normalizeServer('https://host.example/player_api.php')).toBe('https://host.example');
    expect(normalizeServer('http://host.example:80/sub/get.php?username=a&password=b')).toBe('http://host.example/sub');
    expect(normalizeServer('ftp://x')).toBeNull();
    expect(normalizeServer('a b')).toBeNull();
  });

  it('reads a pasted get.php link', () => {
    const r = fromPlaylistLink('http://h.example:8080/get.php?username=u1&password=p%402&type=m3u_plus');
    expect(r?.server).toBe('http://h.example:8080');
    expect(r?.credentials).toEqual({ username: 'u1', password: 'p@2' });
  });

  it('keeps references free of the login and resolves them in memory', () => {
    const ref = makeRef(7, 'live', '123', 'm3u8');
    expect(ref).toBe('xtream://7/live/123.m3u8');
    expect(ref).not.toContain('user');
    const parsed = parseRef(ref)!;
    expect(resolveRef(parsed, 'http://h', login)).toBe('http://h/live/user%20name/p%40ss%2Fword/123.m3u8');
    expect(parseRef('xtream://7/live/../x')).toBeNull();
    expect(resolveRef(parseRef('xtream://1/series/9')!, 'http://h', login)).toBeNull();
    expect(resolveRef(parseRef('xtream://1/timeshift/55_202611041930_60')!, 'http://h', login))
      .toBe('http://h/timeshift/user%20name/p%40ss%2Fword/60/2026-11-04:19-30/55.ts');
  });

  it('redacts logins from any text', () => {
    expect(redact('GET http://h/player_api.php?username=bob&password=secret&action=x')).toBe('GET http://h/player_api.php?username=***&password=***&action=x');
    expect(redact('http://h/live/bob/secret/1.ts')).toBe('http://h/live/***/***/1.ts');
  });
});

describe('Streaming JSON array', () => {
  it('reads elements split across any chunk boundary', () => {
    const items = Array.from({ length: 500 }, (_, i) => ({ stream_id: i, name: `Ch "${i}" \\ {x}`, tags: [1, { a: '[' }] }));
    const json = ' ﻿' + JSON.stringify(items);
    for (const size of [1, 7, 64, 4096]) {
      const out: unknown[] = [];
      const s = new JsonArrayStream((e) => out.push(e));
      for (let i = 0; i < json.length; i += size) s.push(json.substring(i, i + size));
      expect(out).toEqual(items);
    }
  });

  it('returns an object root (error answers) instead of failing', () => {
    const s = new JsonArrayStream(() => {});
    s.push('{"user_info":{"auth":0}}');
    expect(s.end()).toEqual({ user_info: { auth: 0 } });
  });

  it('skips a malformed element and keeps the rest', () => {
    const out: unknown[] = [];
    const s = new JsonArrayStream((e) => out.push(e));
    s.push('[{"a":1},{"b":tru},{"c":3}]');
    expect(out).toEqual([{ a: 1 }, { c: 3 }]);
  });
});

describe('Xtream mapping', () => {
  const account = { status: 'Active', expiresAt: null, maxConnections: 1, activeConnections: 0, isTrial: false, outputFormats: ['m3u8', 'ts'] };
  it('maps a live entry with a login-free reference and a fallback', () => {
    const c = mapEntry('LIVE', { stream_id: 42, name: 'FR| TF1', category_id: '3', stream_icon: 'http://l/1.png', epg_channel_id: 'TF1.fr', tv_archive: 1, tv_archive_duration: 7 },
      new Map([['3', 'FR| GENERALISTE']]), { playlistId: 5, generation: 2, serverHost: 'h.example', account, position: 1 })!;
    expect(c.url).toBe('xtream://5/live/42.m3u8');
    expect(c.backupUrls).toEqual(['xtream://5/live/42.ts']);
    expect(c.country).toBe('FR');
    expect(c.archiveDays).toBe(7);
    expect(c.channelKey).toBe(xtreamKey('h.example', 'LIVE', '42'));
    expect(c.channelKey).toMatch(/^x[0-9a-f]{20}$/);
  });

  it('rejects ids that could escape the reference', () => {
    expect(mapEntry('MOVIES', { stream_id: '../1', name: 'x' }, new Map(), { playlistId: 1, generation: 1, serverHost: 'h', account, position: 1 })).toBeNull();
  });

  it('reads seasons and episodes in both panel layouts', () => {
    const info = parseSeriesInfo({
      info: { plot: 'A \\\\\\"show\\\\\\".', cover: 'http://c/1.jpg' },
      episodes: { '2': [{ id: '20', episode_num: 1, title: 'S2E1', container_extension: 'mkv' }], '1': [{ id: '10', episode_num: 2, title: 'S1E2' }, { id: '9', episode_num: 1, title: 'S1E1' }] },
    });
    expect(info.episodes.map((e) => e.id)).toEqual(['9', '10', '20']);
    expect(info.seasons.map((s) => s.number)).toEqual([1, 2]);
    expect(info.plot).toBe('A "show".');
  });
});

describe('Text cleaning (parity with TextCleanTest.kt)', () => {
  it('cleans summaries escaped several times', () => {
    expect(cleanPlot('Whoever claims the \\\\\\"One Piece\\\\\\" will be King.\\\\r\\\\n\\\\r\\\\nMonkey D. Luffy'))
      .toBe('Whoever claims the "One Piece" will be King.\n\nMonkey D. Luffy');
    expect(cleanPlot('   ')).toBeNull();
  });
  it('initials skip the source tag', () => {
    expect(initialsOf('AR: One Piece')).toBe('OP');
    expect(initialsOf('DE-CAM: Clayface')).toBe('CL');
    expect(initialsOf('|FR| TF1')).toBe('TF');
    expect(initialsOf('Batman')).toBe('BA');
  });
});
