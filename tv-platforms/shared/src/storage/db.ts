/**
 * Local database on IndexedDB, the web counterpart of the Room database of the Android app.
 *
 * Large libraries: imports are written in batches of 500 into a new "generation" and the playlist
 * switches to it at the end (same atomic switch as PlaylistImporter.kt); lists are read through
 * cursors with a limit, never loaded whole; indexes cover the queries the screens make.
 */
import type { Channel, Favorite, HistoryItem, LockedGroup, Playlist, Programme, StreamStatus, VodProgress } from './model';
import { regionCodes } from './model';
import type { ContentKindCode } from '../core/m3u';

const NAME = 'streamx';
const VERSION = 2;
const SEP = '\u0001';
const pos7 = (n: number) => String(n).padStart(7, '0');
const rPrefix = (p: { id: number; generation: number }, kind: number, code: string) => ['r', p.id, p.generation, kind, code, ''].join(SEP);
const gPrefix = (p: { id: number; generation: number }, kind: number, code: string, group: string) => ['g', p.id, p.generation, kind, code, group, ''].join(SEP);

/** Keys of the country / language index: whole section, and section + group, in channel order. */
export function regionKeys(c: Channel): string[] {
  return regionCodes(c).flatMap((code) => [rPrefix({ id: c.playlistId, generation: c.generation }, c.kind, code) + pos7(c.position), gPrefix({ id: c.playlistId, generation: c.generation }, c.kind, code, c.groupTitle) + pos7(c.position)]);
}

export const S = {
  playlists: 'playlists',
  channels: 'channels',
  favorites: 'favorites',
  history: 'history',
  progress: 'progress',
  programmes: 'programmes',
  settings: 'settings',
  vault: 'vault',
  locks: 'locks',
} as const;

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('aborted'));
  });
}

export function openDb(factory: IDBFactory = indexedDB, name = NAME): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = factory.open(name, VERSION);
    r.onupgradeneeded = (ev) => {
      const db = r.result;
      if (ev.oldVersion >= 1) {
        // Version 2: country / language index. Rows written before it get keys at their next refresh.
        const ch = r.transaction!.objectStore(S.channels);
        if (!ch.indexNames.contains('rk')) ch.createIndex('rk', 'rk', { multiEntry: true });
        return;
      }
      db.createObjectStore(S.playlists, { keyPath: 'id' });
      const ch = db.createObjectStore(S.channels, { keyPath: 'id' });
      // playlist, generation, kind, then the order the screens use
      ch.createIndex('pgkp', ['playlistId', 'generation', 'kind', 'position']);
      ch.createIndex('pgkgp', ['playlistId', 'generation', 'kind', 'groupTitle', 'position']);
      ch.createIndex('pg', ['playlistId', 'generation']);
      ch.createIndex('kindAdded', ['kind', 'addedAt']);
      ch.createIndex('kindRating', ['kind', 'rating']);
      ch.createIndex('key', 'channelKey');
      ch.createIndex('tvg', 'tvgId');
      ch.createIndex('category', 'category');
      ch.createIndex('rk', 'rk', { multiEntry: true });
      db.createObjectStore(S.favorites, { keyPath: 'channelKey' }).createIndex('added', 'addedAt');
      db.createObjectStore(S.history, { keyPath: 'channelKey' }).createIndex('watched', 'watchedAt');
      db.createObjectStore(S.progress, { keyPath: 'key' }).createIndex('updated', 'updatedAt');
      db.createObjectStore(S.programmes, { keyPath: 'id' }).createIndex('chStart', ['channelId', 'start']);
      db.createObjectStore(S.settings, { keyPath: 'k' });
      db.createObjectStore(S.vault, { keyPath: 'id' });
      db.createObjectStore(S.locks, { keyPath: 'groupTitle' });
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

export interface GroupCount { groupTitle: string; count: number }

export class Db {
  constructor(readonly idb: IDBDatabase) {}

  private store(name: string, mode: IDBTransactionMode = 'readonly') {
    return this.idb.transaction(name, mode).objectStore(name);
  }

  // ---- generic -------------------------------------------------------------------------------

  get<T>(store: string, key: IDBValidKey): Promise<T | undefined> {
    return req(this.store(store).get(key)) as Promise<T | undefined>;
  }

  async put<T>(store: string, value: T): Promise<void> {
    const tx = this.idb.transaction(store, 'readwrite');
    tx.objectStore(store).put(value);
    await done(tx);
  }

  async delete(store: string, key: IDBValidKey): Promise<void> {
    const tx = this.idb.transaction(store, 'readwrite');
    tx.objectStore(store).delete(key);
    await done(tx);
  }

  all<T>(store: string): Promise<T[]> {
    return req(this.store(store).getAll()) as Promise<T[]>;
  }

  /** Reads an index range in order with a limit (and an optional filter), without loading the rest. */
  scan<T>(store: string, index: string | null, range: IDBKeyRange | null, opts: { limit?: number; offset?: number; reverse?: boolean; filter?: (v: T) => boolean } = {}): Promise<T[]> {
    const { limit = Infinity, reverse = false, filter } = opts;
    let skip = opts.offset ?? 0;
    return new Promise((resolve, reject) => {
      const s = this.store(store);
      const src = index ? s.index(index) : s;
      const r = src.openCursor(range, reverse ? 'prev' : 'next');
      const out: T[] = [];
      r.onerror = () => reject(r.error);
      r.onsuccess = () => {
        const c = r.result;
        if (!c || out.length >= limit) { resolve(out); return; }
        const v = c.value as T;
        if (!filter || filter(v)) {
          if (skip > 0) skip--;
          else out.push(v);
        }
        if (out.length >= limit) { resolve(out); return; }
        c.continue();
      };
    });
  }

  count(store: string, index: string | null, range: IDBKeyRange | null): Promise<number> {
    const s = this.store(store);
    return req((index ? s.index(index) : s).count(range ?? undefined));
  }

  // ---- settings ------------------------------------------------------------------------------

  async setting<T>(k: string, fallback: T): Promise<T> {
    const row = await this.get<{ k: string; v: T }>(S.settings, k);
    return row ? row.v : fallback;
  }

  setSetting<T>(k: string, v: T): Promise<void> {
    return this.put(S.settings, { k, v });
  }

  // ---- playlists and channels ----------------------------------------------------------------

  playlists(): Promise<Playlist[]> {
    return this.all<Playlist>(S.playlists).then((p) => p.sort((a, b) => a.id - b.id));
  }

  async nextPlaylistId(): Promise<number> {
    const p = await this.playlists();
    return p.reduce((m, x) => Math.max(m, x.id), 0) + 1;
  }

  /** Writes channels in one transaction per batch (the importer calls it with batches of 500). */
  async putChannels(rows: Channel[]): Promise<void> {
    if (rows.length === 0) return;
    const tx = this.idb.transaction(S.channels, 'readwrite');
    const s = tx.objectStore(S.channels);
    for (const r of rows) { const rk = regionKeys(r); s.put(rk.length ? { ...r, rk } : r); }
    await done(tx);
  }

  /** Channels of one country or language (and group), in channel order. */
  regionChannels(p: Playlist, kind: ContentKindCode, group: string | null, code: string, offset: number, limit: number): Promise<Channel[]> {
    const pre = group == null ? rPrefix(p, kind, code) : gPrefix(p, kind, code, group);
    return this.scan<Channel>(S.channels, 'rk', IDBKeyRange.bound(pre, pre + '\uffff'), { offset, limit });
  }

  regionCount(p: Playlist, kind: ContentKindCode, group: string | null, code: string): Promise<number> {
    const pre = group == null ? rPrefix(p, kind, code) : gPrefix(p, kind, code, group);
    return this.count(S.channels, 'rk', IDBKeyRange.bound(pre, pre + '\uffff'));
  }

  /** Result of an availability check, on every row of that channel (all generations, all playlists). */
  async updateStatus(channelKey: string, status: StreamStatus, checkedAt: number): Promise<void> {
    const tx = this.idb.transaction(S.channels, 'readwrite');
    const r = tx.objectStore(S.channels).index('key').openCursor(IDBKeyRange.only(channelKey));
    r.onsuccess = () => {
      const c = r.result;
      if (!c) return;
      c.update({ ...(c.value as Channel), status, statusCheckedAt: checkedAt });
      c.continue();
    };
    await done(tx);
  }

  /** Deletes every row of other generations of a playlist (after the switch) or of the whole playlist. */
  async deleteGenerations(playlistId: number, keep: number | null): Promise<number> {
    let removed = 0;
    await new Promise<void>((resolve, reject) => {
      const tx = this.idb.transaction(S.channels, 'readwrite');
      const idx = tx.objectStore(S.channels).index('pg');
      const r = idx.openCursor(IDBKeyRange.bound([playlistId, -Infinity], [playlistId, Infinity]));
      r.onsuccess = () => {
        const c = r.result;
        if (!c) return;
        if (keep == null || (c.value as Channel).generation !== keep) { c.delete(); removed++; }
        c.continue();
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    return removed;
  }

  channelsOf(p: Playlist, kind: ContentKindCode, group: string | null, offset: number, limit: number): Promise<Channel[]> {
    if (group == null) {
      return this.scan<Channel>(S.channels, 'pgkp', IDBKeyRange.bound([p.id, p.generation, kind, -Infinity], [p.id, p.generation, kind, Infinity]), { offset, limit });
    }
    return this.scan<Channel>(S.channels, 'pgkgp', IDBKeyRange.bound([p.id, p.generation, kind, group, -Infinity], [p.id, p.generation, kind, group, Infinity]), { offset, limit });
  }

  countOf(p: Playlist, kind: ContentKindCode, group: string | null): Promise<number> {
    if (group == null) return this.count(S.channels, 'pgkp', IDBKeyRange.bound([p.id, p.generation, kind, -Infinity], [p.id, p.generation, kind, Infinity]));
    return this.count(S.channels, 'pgkgp', IDBKeyRange.bound([p.id, p.generation, kind, group, -Infinity], [p.id, p.generation, kind, group, Infinity]));
  }

  /**
   * Categories of a section with their sizes, in playlist order. Walks the group index with key
   * cursors only (no record is loaded), jumping from one group to the next.
   */
  groupsOf(p: Playlist, kind: ContentKindCode): Promise<GroupCount[]> {
    return new Promise((resolve, reject) => {
      const idx = this.store(S.channels).index('pgkgp');
      const lower: IDBValidKey = [p.id, p.generation, kind, '', -Infinity];
      const upper: IDBValidKey = [p.id, p.generation, kind, '￿', Infinity];
      const out: GroupCount[] = [];
      const r = idx.openKeyCursor(IDBKeyRange.bound(lower, upper));
      let current: string | null = null;
      r.onerror = () => reject(r.error);
      r.onsuccess = () => {
        const c = r.result;
        if (!c) { resolve(out); return; }
        const key = c.key as [number, number, number, string, number];
        const g = key[3];
        if (g !== current) { current = g; out.push({ groupTitle: g, count: 0 }); }
        out[out.length - 1].count++;
        c.continue();
      };
    });
  }

  byKey(channelKey: string): Promise<Channel | undefined> {
    return this.scan<Channel>(S.channels, 'key', IDBKeyRange.only(channelKey), { limit: 1 }).then((r) => r[0]);
  }

  byNumber(p: Playlist, kind: ContentKindCode, position: number): Promise<Channel | undefined> {
    return this.scan<Channel>(S.channels, 'pgkp', IDBKeyRange.only([p.id, p.generation, kind, position]), { limit: 1 }).then((r) => r[0]);
  }

  /** Neighbour in the same lineup, for channel up/down. */
  async neighbour(ch: Channel, group: string | null, dir: 1 | -1): Promise<Channel | undefined> {
    const k = ch.kind;
    const range = group == null
      ? (dir > 0
        ? IDBKeyRange.bound([ch.playlistId, ch.generation, k, ch.position], [ch.playlistId, ch.generation, k, Infinity], true)
        : IDBKeyRange.bound([ch.playlistId, ch.generation, k, -Infinity], [ch.playlistId, ch.generation, k, ch.position], false, true))
      : (dir > 0
        ? IDBKeyRange.bound([ch.playlistId, ch.generation, k, group, ch.position], [ch.playlistId, ch.generation, k, group, Infinity], true)
        : IDBKeyRange.bound([ch.playlistId, ch.generation, k, group, -Infinity], [ch.playlistId, ch.generation, k, group, ch.position], false, true));
    const r = await this.scan<Channel>(S.channels, group == null ? 'pgkp' : 'pgkgp', range, { limit: 1, reverse: dir < 0 });
    if (r[0]) return r[0];
    // wrap around
    const wrap = group == null
      ? IDBKeyRange.bound([ch.playlistId, ch.generation, k, -Infinity], [ch.playlistId, ch.generation, k, Infinity])
      : IDBKeyRange.bound([ch.playlistId, ch.generation, k, group, -Infinity], [ch.playlistId, ch.generation, k, group, Infinity]);
    return (await this.scan<Channel>(S.channels, group == null ? 'pgkp' : 'pgkgp', wrap, { limit: 1, reverse: dir < 0 }))[0];
  }

  /** Current generations, to ignore rows of an import still being written or of an old one. */
  private async liveGenerations(): Promise<Map<number, number>> {
    const m = new Map<number, number>();
    for (const p of await this.playlists()) m.set(p.id, p.generation);
    return m;
  }

  async newest(kind: ContentKindCode, limit: number): Promise<Channel[]> {
    const gens = await this.liveGenerations();
    return this.scan<Channel>(S.channels, 'kindAdded', IDBKeyRange.bound([kind, 1], [kind, Infinity]), {
      reverse: true, limit, filter: (c) => gens.get(c.playlistId) === c.generation && c.logo != null,
    });
  }

  async topRated(kind: ContentKindCode, limit: number): Promise<Channel[]> {
    const gens = await this.liveGenerations();
    return this.scan<Channel>(S.channels, 'kindRating', IDBKeyRange.bound([kind, 0.1], [kind, 10]), {
      reverse: true, limit, filter: (c) => gens.get(c.playlistId) === c.generation && c.logo != null,
    });
  }

  async inCategory(category: string, limit: number): Promise<Channel[]> {
    const gens = await this.liveGenerations();
    return this.scan<Channel>(S.channels, 'category', IDBKeyRange.only(category), { limit, filter: (c) => gens.get(c.playlistId) === c.generation });
  }

  async countKind(kind: ContentKindCode): Promise<number> {
    let n = 0;
    for (const p of await this.playlists()) n += await this.countOf(p, kind, null);
    return n;
  }

  /**
   * Search over every playlist: accent-insensitive substring match on the normalized name.
   * A cursor walks the rows once and stops as soon as each section has enough results.
   */
  async search(qNorm: string, perKind: number, kind: ContentKindCode | -1 = -1, hidden: Set<string> = new Set()): Promise<Record<number, Channel[]>> {
    const gens = await this.liveGenerations();
    const out: Record<number, Channel[]> = { 0: [], 1: [], 2: [] };
    const words = qNorm.split(' ').filter((w) => w !== '');
    if (words.length === 0) return out;
    await new Promise<void>((resolve, reject) => {
      const r = this.store(S.channels).openCursor();
      r.onerror = () => reject(r.error);
      r.onsuccess = () => {
        const c = r.result;
        if (!c) { resolve(); return; }
        const ch = c.value as Channel;
        if (gens.get(ch.playlistId) === ch.generation && (kind === -1 || ch.kind === kind) && !hidden.has(ch.groupTitle)) {
          const list = out[ch.kind];
          if (list.length < perKind && words.every((w) => ch.nameNorm.includes(w))) list.push(ch);
        }
        const full = kind === -1 ? out[0].length >= perKind && out[1].length >= perKind && out[2].length >= perKind : out[kind].length >= perKind;
        if (full) { resolve(); return; }
        c.continue();
      };
    });
    return out;
  }

  // ---- favorites, history, progress ----------------------------------------------------------

  async isFavorite(key: string): Promise<boolean> {
    return (await this.get<Favorite>(S.favorites, key)) != null;
  }

  async toggleFavorite(key: string): Promise<boolean> {
    if (await this.isFavorite(key)) { await this.delete(S.favorites, key); return false; }
    await this.put<Favorite>(S.favorites, { channelKey: key, addedAt: Date.now() });
    return true;
  }

  private async resolveKeys(keys: string[]): Promise<Channel[]> {
    const gens = await this.liveGenerations();
    const out: Channel[] = [];
    for (const k of keys) {
      const rows = await this.scan<Channel>(S.channels, 'key', IDBKeyRange.only(k), { limit: 3, filter: (c) => gens.get(c.playlistId) === c.generation });
      if (rows[0]) out.push(rows[0]);
    }
    return out;
  }

  async favorites(limit: number): Promise<Channel[]> {
    const favs = await this.scan<Favorite>(S.favorites, 'added', null, { reverse: true, limit });
    return this.resolveKeys(favs.map((f) => f.channelKey));
  }

  async touchHistory(key: string): Promise<void> {
    await this.put<HistoryItem>(S.history, { channelKey: key, watchedAt: Date.now() });
    const all = await this.scan<HistoryItem>(S.history, 'watched', null, { reverse: true, offset: 50 });
    for (const h of all) await this.delete(S.history, h.channelKey);
  }

  async recent(limit: number): Promise<Channel[]> {
    const h = await this.scan<HistoryItem>(S.history, 'watched', null, { reverse: true, limit });
    return this.resolveKeys(h.map((x) => x.channelKey));
  }

  saveProgress(p: VodProgress): Promise<void> {
    return this.put(S.progress, p);
  }

  progress(key: string): Promise<VodProgress | undefined> {
    return this.get<VodProgress>(S.progress, key);
  }

  continueWatching(limit: number): Promise<VodProgress[]> {
    return this.scan<VodProgress>(S.progress, 'updated', null, {
      reverse: true, limit, filter: (p) => p.positionMs > 10_000 && p.positionMs < p.durationMs * 0.95,
    });
  }

  // ---- guide ---------------------------------------------------------------------------------

  async putProgrammes(rows: Programme[]): Promise<void> {
    const tx = this.idb.transaction(S.programmes, 'readwrite');
    const s = tx.objectStore(S.programmes);
    for (const r of rows) s.put(r);
    await done(tx);
  }

  async clearProgrammes(): Promise<void> {
    const tx = this.idb.transaction(S.programmes, 'readwrite');
    tx.objectStore(S.programmes).clear();
    await done(tx);
  }

  /** Programmes of a channel overlapping [from, to), in order. */
  programmes(channelId: string, from: number, to: number, limit = 50): Promise<Programme[]> {
    const id = channelId.toLowerCase();
    return this.scan<Programme>(S.programmes, 'chStart', IDBKeyRange.bound([id, from - 12 * 3600_000], [id, to]), {
      limit, filter: (p) => p.stop > from,
    });
  }

  // ---- parental lock -------------------------------------------------------------------------

  locks(): Promise<LockedGroup[]> {
    return this.all<LockedGroup>(S.locks);
  }

  async hiddenGroups(): Promise<Set<string>> {
    return new Set((await this.locks()).filter((l) => l.hidden === 1).map((l) => l.groupTitle));
  }

  async setLocksHidden(hidden: 0 | 1): Promise<void> {
    for (const l of await this.locks()) await this.put<LockedGroup>(S.locks, { ...l, hidden });
  }
}
