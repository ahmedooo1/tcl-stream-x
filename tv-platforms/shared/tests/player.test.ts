// PlaybackController with a scripted adapter: the same behaviour as PlaybackController.kt.
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Db, openDb } from '@shared/storage/db';
import { itemFromChannel, PlaybackController, PlayItem } from '@shared/player/controller';
import type { PlaybackIssue, PlayerAdapter, PlayerListener, Source } from '@shared/player/types';

class FakePlayer implements PlayerAdapter {
  readonly name = 'fake';
  l!: PlayerListener;
  loads: Source[] = [];
  paused = false;
  setListener(l: PlayerListener) { this.l = l; }
  load(s: Source) { this.loads.push(s); this.l.state('loading'); }
  play() { this.paused = false; this.l.state('playing'); }
  pause() { this.paused = true; this.l.state('paused'); }
  stop() { this.l.state('idle'); }
  seekTo() {}
  positionMs() { return 0; }
  durationMs() { return 0; }
  setRect() {}
  setResizeMode() {}
  setSpeed() { return true; }
  audioTracks() { return []; }
  textTracks() { return []; }
  qualities() { return []; }
  selectAudio() {}
  selectText() {}
  selectQuality() {}
  destroy() {}
  fail(i: PlaybackIssue) { this.l.error(i, 'x'); }
  ok() { this.l.state('playing'); }
}

const live = (key: string, urls: string[], extra: Partial<PlayItem> = {}): PlayItem => ({
  key, title: key, urls, isLive: true, logo: null, poster: null, userAgent: null, referrer: null, drm: false, tvgId: null, channel: null, group: null, upNext: [], ...extra,
});

let db: Db;
let fake: FakePlayer;
let pc: PlaybackController;
const flush = () => new Promise((r) => setTimeout(r, 0));

beforeEach(async () => {
  db = new Db(await openDb(new IDBFactory(), 'p'));
  fake = new FakePlayer();
  pc = new PlaybackController(fake, db, async (ref) => (ref.includes('/live/') ? 'http://real/' + ref.split('/').pop() : null));
});

describe('PlaybackController', () => {
  it('tries backup addresses in order, then reports the failure', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await pc.play(live('a', ['http://h/1.m3u8', 'http://h/2.m3u8']));
    fake.fail('FORBIDDEN');
    await flush();
    expect(fake.loads.map((s) => s.url)).toEqual(['http://h/1.m3u8', 'http://h/2.m3u8']);
    fake.fail('FORBIDDEN');
    await flush();
    expect(pc.ui.value.issue).toBe('FORBIDDEN');
    vi.useRealTimers();
  });

  it('retries an address without extension as HLS once', async () => {
    await pc.play(live('b', ['http://h/stream']));
    expect(fake.loads[0].mime).toBeNull();
    fake.fail('FORMAT');
    await flush();
    expect(fake.loads[1].mime).toBe('hls');
  });

  it('resolves account references in memory and never stores the real address', async () => {
    await pc.play(live('x', ['xtream://1/live/42.m3u8']));
    expect(fake.loads[0].url).toBe('http://real/42.m3u8');
    expect(fake.loads[0].mime).toBe('hls');
    expect(JSON.stringify(pc.ui.value.item)).not.toContain('real');
  });

  it('refuses DRM streams without loading them', async () => {
    await pc.play(live('d', ['http://h/x.mpd'], { drm: true }));
    expect(fake.loads).toHaveLength(0);
    expect(pc.ui.value.issue).toBe('DRM');
  });

  it('pauses a live channel, counts the delay, goes back to live', async () => {
    await pc.play(live('l', ['http://h/l.m3u8']));
    fake.ok();
    pc.pause();
    expect(pc.ui.value.livePausedAt).toBeGreaterThan(0);
    await new Promise((r) => setTimeout(r, 30));
    pc.resume();
    expect(pc.ui.value.livePausedAt).toBe(0);
    expect(pc.ui.value.liveBehindMs).toBeGreaterThanOrEqual(25);
    pc.goLive();
    await flush();
    expect(pc.ui.value.liveBehindMs).toBe(0);
    expect(fake.loads).toHaveLength(2);
  });

  it('remembers the previous channel (key 0)', async () => {
    await pc.play(live('one', ['http://h/1.m3u8']));
    await pc.play(live('two', ['http://h/2.m3u8']));
    expect(pc.ui.value.previousTitle).toBe('one');
    await pc.playNumber(0);
    await flush();
    expect(pc.ui.value.item?.key).toBe('one');
    expect(pc.ui.value.previousTitle).toBe('two');
  });

  it('keeps live history and the last channel', async () => {
    await pc.play(live('h1', ['http://h/1.m3u8']));
    expect(await db.setting('lastChannel', '')).toBe('h1');
  });

  it('builds items from stored channels with their backups', () => {
    const it = itemFromChannel({
      id: '1:1:1', playlistId: 1, generation: 1, position: 1, name: 'N', nameNorm: 'n', url: 'xtream://1/live/1.m3u8', backupUrls: ['xtream://1/live/1.ts'],
      logo: null, groupTitle: 'G', category: null, tvgId: 't', language: null, country: null, userAgent: null, referrer: null, drm: false, kind: 0,
      channelKey: 'k', status: 0, statusCheckedAt: 0, archiveDays: 0, addedAt: 0, rating: 0,
    }, 'G');
    expect(it.urls).toEqual(['xtream://1/live/1.m3u8', 'xtream://1/live/1.ts']);
    expect(it.isLive).toBe(true);
    expect(it.group).toBe('G');
  });
});
