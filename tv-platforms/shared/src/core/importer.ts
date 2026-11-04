/**
 * Imports playlists, accounts and the built-in catalog into the database. Port of
 * PlaylistImporter.kt and BuiltInCatalog.kt: rows are written in batches of 500 into a new
 * generation, the playlist switches to it at the end, then the old rows are removed. An import that
 * fails half way leaves the previous content in place.
 */
import { channelKey, classify, ContentKind, M3uEntry, M3uParser } from './m3u';
import { normalize } from './text';
import { tagRegion } from './region';
import { readTextStream } from './jsonstream';
import { Db, S } from '../storage/db';
import type { Channel, Playlist } from '../storage/model';
import { StreamStatus } from '../storage/model';
import { Vault } from '../security/vault';
import { accountSummary, mapEntry, XtreamAccount, XtreamClient, XtreamSection } from '../xtream/client';
import { fromPlaylistLink, normalizeServer, XtreamCredentials } from '../xtream/urls';

export const BATCH = 500;
export const MAX_ENTRIES = 250_000;

export type ImportPhase = 'download' | 'parse' | 'live' | 'movies' | 'series' | 'switch';
export interface ImportProgress { phase: ImportPhase; count: number }
export type ImportError = 'NETWORK' | 'HTTP' | 'EMPTY' | 'TOO_LARGE' | 'FORMAT' | 'AUTH' | 'EXPIRED' | 'INACTIVE' | 'TIMEOUT' | 'INVALID_URL';

export class ImportException extends Error {
  constructor(readonly error: ImportError) { super(error); }
}

class GroupCounter {
  private readonly order: Record<number, Map<string, number>> = { 0: new Map(), 1: new Map(), 2: new Map() };
  add(kind: number, g: string) { const m = this.order[kind]; m.set(g, (m.get(g) ?? 0) + 1); }
  result(): Playlist['groups'] {
    const out: Playlist['groups'] = {};
    for (const k of [0, 1, 2]) out[k] = Array.from(this.order[k], ([groupTitle, count]) => ({ groupTitle, count }));
    return out;
  }
}

/** Decompresses gzip bodies (guides, some playlists) when the platform can, else returns them as they are. */
export async function maybeGunzip(res: Response): Promise<Response> {
  const type = res.headers.get('content-type') ?? '';
  const url = res.url.toLowerCase();
  const gz = type.includes('gzip') || url.split('?')[0].endsWith('.gz');
  if (!gz || !res.body) return res;
  const DS = (globalThis as unknown as { DecompressionStream?: new (f: string) => TransformStream }).DecompressionStream;
  if (DS) return new Response(res.body.pipeThrough(new DS('gzip')));
  // Older engines: pako on the whole body (guides are a few MB compressed).
  const { ungzip } = await import('pako');
  const buf = new Uint8Array(await res.arrayBuffer());
  return new Response(ungzip(buf));
}

export class Importer {
  constructor(private readonly db: Db, private readonly vault: Vault, private readonly xtream = new XtreamClient()) {}

  private async beginPlaylist(existing: Playlist | undefined, base: Omit<Playlist, 'id' | 'generation' | 'updatedAt' | 'count' | 'groups'>): Promise<Playlist> {
    if (existing) return existing;
    const p: Playlist = { ...base, id: await this.db.nextPlaylistId(), generation: 0, updatedAt: 0, count: 0, groups: {} };
    await this.db.put(S.playlists, p);
    return p;
  }

  private async switchTo(p: Playlist, generation: number, count: number, groups: Playlist['groups'], extra: Partial<Playlist> = {}): Promise<Playlist> {
    const next: Playlist = { ...p, ...extra, generation, count, groups, updatedAt: Date.now() };
    await this.db.put(S.playlists, next);
    await this.db.deleteGenerations(p.id, generation);
    return next;
  }

  /** M3U/M3U8 text (file, phone upload) or a stream of chunks (download). */
  async importM3u(
    name: string,
    source: { url: string } | { chunks: (push: (s: string) => void) => Promise<void> },
    existing?: Playlist,
    onProgress: (p: ImportProgress) => void = () => {},
  ): Promise<Playlist> {
    if ('url' in source) {
      // A pasted Xtream link is an account, not a playlist.
      const link = fromPlaylistLink(source.url);
      if (link) return this.importXtream(name, link.server, link.credentials, existing, onProgress);
      if (!/^https?:\/\//i.test(source.url)) throw new ImportException('INVALID_URL');
    }
    const p = await this.beginPlaylist(existing, {
      name, kind: 'url' in source ? 'url' : 'file', source: 'url' in source ? source.url : null, epgUrls: [], accountSummary: null, vaultId: null,
    });
    const generation = p.generation + 1;
    const groups = new GroupCounter();
    let batch: Channel[] = [];
    let position = 0;
    let epg: string[] = [];
    const writes: Promise<void>[] = [];
    const flush = () => { if (batch.length) { writes.push(this.db.putChannels(batch)); batch = []; } };
    const parser = new M3uParser((e: M3uEntry) => {
      if (position >= MAX_ENTRIES) return;
      position++;
      const kind = classify(e);
      const region = tagRegion(e.group, e.country, e.language);
      groups.add(kind, e.group);
      batch.push({
        id: `${p.id}:${generation}:${position}`,
        playlistId: p.id, generation, position,
        name: e.name, nameNorm: normalize(e.name), url: e.url, backupUrls: [], logo: e.logo,
        groupTitle: e.group, category: null, tvgId: e.tvgId, language: region.language, country: region.country,
        userAgent: e.userAgent, referrer: e.referrer, drm: e.drm, kind,
        channelKey: channelKey(e.tvgId, e.name, e.group),
        status: e.drm ? StreamStatus.DRM : StreamStatus.UNKNOWN, statusCheckedAt: 0, archiveDays: 0, addedAt: 0, rating: 0,
      });
      if (batch.length >= BATCH) { flush(); onProgress({ phase: 'parse', count: position }); }
    }, (u) => { epg = u; });

    try {
      if ('url' in source) {
        onProgress({ phase: 'download', count: 0 });
        let res: Response;
        try { res = await fetch(source.url, { credentials: 'omit', cache: 'no-store' }); } catch { throw new ImportException('NETWORK'); }
        if (!res.ok) throw new ImportException('HTTP');
        res = await maybeGunzip(res);
        try {
          await readTextStream(res, (t) => parser.push(t));
        } catch (e) {
          throw new ImportException((e as Error).message === 'too large' ? 'TOO_LARGE' : 'NETWORK');
        }
      } else {
        await source.chunks((t) => parser.push(t));
      }
      parser.end();
      flush();
      await Promise.all(writes);
    } catch (e) {
      await this.db.deleteGenerations(p.id, p.generation);
      throw e;
    }
    if (position === 0) {
      await this.db.deleteGenerations(p.id, p.generation);
      throw new ImportException('EMPTY');
    }
    onProgress({ phase: 'switch', count: position });
    return this.switchTo(p, generation, position, groups.result(), { epgUrls: epg });
  }

  /** Xtream Codes account: login checked first, then live, films and series streamed in. */
  async importXtream(
    name: string,
    serverInput: string,
    credentials: XtreamCredentials,
    existing?: Playlist,
    onProgress: (p: ImportProgress) => void = () => {},
  ): Promise<Playlist> {
    const server = normalizeServer(serverInput);
    if (!server) throw new ImportException('INVALID_URL');
    let account: XtreamAccount;
    try {
      account = await this.xtream.authenticate(server, credentials);
    } catch (e) {
      const err = (e as { error?: string }).error;
      throw new ImportException(err === 'AUTH' || err === 'EXPIRED' || err === 'INACTIVE' || err === 'TIMEOUT' || err === 'FORMAT' ? err : 'NETWORK');
    }
    const vaultId = await this.vault.store(credentials, existing?.vaultId ?? undefined);
    const p = await this.beginPlaylist(existing, { name, kind: 'xtream', source: server, epgUrls: [], accountSummary: accountSummary(account), vaultId });
    const generation = p.generation + 1;
    const host = new URL(server).host;
    const groups = new GroupCounter();
    let position = 0;
    try {
      for (const [section, phase] of [['LIVE', 'live'], ['MOVIES', 'movies'], ['SERIES', 'series']] as [XtreamSection, ImportPhase][]) {
        let cats: Map<string, string>;
        try { cats = await this.xtream.categories(server, credentials, section); } catch { cats = new Map(); }
        let batch: Channel[] = [];
        const writes: Promise<void>[] = [];
        try {
          await this.xtream.list(server, credentials, section, (e) => {
            if (position >= MAX_ENTRIES) return;
            const row = mapEntry(section, e, cats, { playlistId: p.id, generation, serverHost: host, account, position: position + 1 });
            if (!row) return;
            position++;
            groups.add(row.kind, row.groupTitle);
            batch.push(row);
            if (batch.length >= BATCH) { writes.push(this.db.putChannels(batch)); batch = []; onProgress({ phase, count: position }); }
          });
        } catch (e) {
          // A section a panel does not offer (some have no series) does not fail the whole account.
          if (section === 'LIVE') throw e;
        }
        if (batch.length) writes.push(this.db.putChannels(batch));
        await Promise.all(writes);
      }
    } catch (e) {
      await this.db.deleteGenerations(p.id, p.generation);
      const err = (e as { error?: string }).error;
      throw new ImportException(err === 'TIMEOUT' ? 'TIMEOUT' : err === 'AUTH' ? 'AUTH' : 'NETWORK');
    }
    if (position === 0) {
      await this.db.deleteGenerations(p.id, p.generation);
      throw new ImportException('EMPTY');
    }
    onProgress({ phase: 'switch', count: position });
    return this.switchTo(p, generation, position, groups.result(), {
      accountSummary: accountSummary(account), vaultId, source: server,
      epgUrls: [`xtream://${p.id}/epg/guide`],
    });
  }

  /** Refreshes a playlist from its source (URL or account), keeping favorites (stable keys). */
  async refresh(p: Playlist, onProgress?: (p: ImportProgress) => void): Promise<Playlist> {
    if (p.kind === 'url' && p.source) return this.importM3u(p.name, { url: p.source }, p, onProgress);
    if (p.kind === 'xtream' && p.source && p.vaultId) {
      const c = await this.vault.load(p.vaultId);
      if (!c) throw new ImportException('AUTH');
      return this.importXtream(p.name, p.source, c, p, onProgress);
    }
    return p;
  }

  async remove(p: Playlist): Promise<void> {
    await this.db.deleteGenerations(p.id, null);
    if (p.vaultId) await this.vault.remove(p.vaultId);
    await this.db.delete(S.playlists, p.id);
  }

  /** Official free channels shipped with the app (same catalog.json as Android). */
  async installCatalog(file: CatalogFile, label: string): Promise<Playlist> {
    const all = await this.db.playlists();
    const existing = all.find((x) => x.kind === 'builtin');
    if (existing && existing.sourceVersion === file.generatedAt) return existing;
    const p = await this.beginPlaylist(existing, { name: label, kind: 'builtin', source: null, epgUrls: [], accountSummary: null, vaultId: null });
    const generation = p.generation + 1;
    const order = ['news', 'entertainment', 'movies', 'series', 'sports', 'kids', 'documentary', 'music', 'religious'];
    const rank = (l: string | null | undefined) => ['ar', 'ku', 'fr', 'en'].indexOf(l ?? '') < 0 ? 9 : ['ar', 'ku', 'fr', 'en'].indexOf(l ?? '');
    const sorted = [...file.channels].sort((a, b) => order.indexOf(a.category) - order.indexOf(b.category) || rank(a.language) - rank(b.language));
    const groups = new GroupCounter();
    const rows: Channel[] = sorted.filter((c) => c.streams.length > 0).map((ch, i) => {
      const streams = [...ch.streams].sort((a, b) => (a.status === 'ok' ? 0 : 1) - (b.status === 'ok' ? 0 : 1));
      const best = streams[0];
      const group = `${ch.category}:${ch.language ?? ''}`;
      groups.add(ContentKind.LIVE, group);
      return {
        id: `${p.id}:${generation}:${i + 1}`, playlistId: p.id, generation, position: i + 1,
        name: ch.name, nameNorm: normalize(ch.name), url: best.url, backupUrls: streams.slice(1).map((s) => s.url),
        logo: ch.logo ?? null, groupTitle: group, category: ch.category, tvgId: ch.id.split('@')[0],
        language: ch.language ?? null, country: ch.country ?? null, userAgent: best.userAgent ?? null, referrer: best.referrer ?? null,
        drm: false, kind: ContentKind.LIVE, channelKey: 'b:' + ch.id,
        status: catalogStatus(best.status), statusCheckedAt: (best.checkedAt ?? 0) * 1000,
        archiveDays: 0, addedAt: 0, rating: 0, geo: ch.geo ?? null,
      };
    });
    for (let i = 0; i < rows.length; i += BATCH) await this.db.putChannels(rows.slice(i, i + BATCH));
    return this.switchTo(p, generation, rows.length, groups.result(), { sourceVersion: file.generatedAt });
  }
}

export interface CatalogStream { url: string; userAgent?: string | null; referrer?: string | null; quality?: number; status?: string; checkedAt?: number }
export interface CatalogChannel { id: string; name: string; logo?: string | null; category: string; language?: string | null; country?: string | null; geo?: string | null; streams: CatalogStream[] }

/** BuiltInCatalog.statusOf: the status the catalog check wrote for a stream. */
export function catalogStatus(s: string | undefined): StreamStatus {
  switch (s) {
    case 'ok': return StreamStatus.ONLINE;
    case 'forbidden_or_geo': return StreamStatus.FORBIDDEN;
    case 'auth_required': return StreamStatus.AUTH;
    case 'drm': return StreamStatus.DRM;
    case 'timeout': return StreamStatus.TIMEOUT;
    case 'invalid_manifest': case 'empty_segment': return StreamStatus.INVALID;
    case 'unchecked': case undefined: return StreamStatus.UNKNOWN;
    default: return StreamStatus.OFFLINE;
  }
}
export interface CatalogFile { generatedAt: string; channels: CatalogChannel[] }
