/**
 * Client of the Xtream Codes `player_api.php` interface, port of XtreamClient.kt + XtreamMapper.kt.
 * Lists are streamed element by element; no request URL (which carries the login) is ever put in an
 * error message or a log.
 */
import { cleanPlot, isHttpUrl, normalize } from '../core/text';
import { JsonArrayStream, readTextStream } from '../core/jsonstream';
import { tagRegion } from '../core/region';
import { ContentKind, ContentKindCode } from '../core/m3u';
import { sha1Hex } from '../core/sha1';
import { apiUrl, makeRef, XtreamCredentials } from './urls';
import type { Channel } from '../storage/model';
import { StreamStatus } from '../storage/model';

export type XtreamError = 'NETWORK' | 'AUTH' | 'EXPIRED' | 'INACTIVE' | 'FORMAT' | 'HTTP' | 'TIMEOUT';

export class XtreamException extends Error {
  constructor(readonly error: XtreamError, readonly httpStatus?: number) {
    super(error);
  }
}

export interface XtreamAccount {
  status: string;
  expiresAt: number | null;
  maxConnections: number | null;
  activeConnections: number | null;
  isTrial: boolean;
  outputFormats: string[];
}

export const liveExtension = (a: XtreamAccount) => (a.outputFormats.length === 0 || a.outputFormats.includes('m3u8') ? 'm3u8' : 'ts');
export const liveFallbackExtension = (a: XtreamAccount) =>
  liveExtension(a) === 'm3u8' && (a.outputFormats.length === 0 || a.outputFormats.includes('ts')) ? 'ts' : null;
export const accountSummary = (a: XtreamAccount) =>
  [a.status, a.expiresAt?.toString() ?? '', a.maxConnections?.toString() ?? '', a.isTrial ? '1' : '0'].join('|');

export function parseSummary(s: string | null | undefined): Pick<XtreamAccount, 'status' | 'expiresAt' | 'maxConnections' | 'isTrial'> | null {
  if (!s) return null;
  const p = s.split('|');
  if (p.length < 4) return null;
  return { status: p[0], expiresAt: p[1] ? Number(p[1]) : null, maxConnections: p[2] ? Number(p[2]) : null, isTrial: p[3] === '1' };
}

export type XtreamSection = 'LIVE' | 'MOVIES' | 'SERIES';
const ACTIONS: Record<XtreamSection, [string, string]> = {
  LIVE: ['get_live_categories', 'get_live_streams'],
  MOVIES: ['get_vod_categories', 'get_vod_streams'],
  SERIES: ['get_series_categories', 'get_series'],
};

export interface Episode { id: string; season: number; episode: number; title: string; extension: string; plot: string | null; durationSecs: number | null; image: string | null }
export interface SeriesInfo { plot: string | null; backdrop: string | null; cover: string | null; seasons: { number: number; name: string }[]; episodes: Episode[] }
export interface MovieInfo { plot: string | null; genre: string | null; releaseDate: string | null; durationSecs: number | null; rating: string | null; cast: string | null; director: string | null; backdrop: string | null }

type Obj = Record<string, unknown>;
const text = (o: Obj | undefined | null, k: string): string | null => {
  const v = o?.[k];
  if (v == null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};
const num = (o: Obj | undefined | null, k: string): number | null => {
  const t = text(o, k);
  if (t == null || t.trim() === '') return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
};
const asObj = (v: unknown): Obj | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null);

/** Some panels put several images in one field, or a JSON-encoded list. */
export function firstImage(raw: unknown): string | null {
  if (Array.isArray(raw)) {
    for (const x of raw) if (typeof x === 'string' && isHttpUrl(x)) return x;
    return null;
  }
  if (typeof raw === 'string') {
    const t = raw.trim();
    if (isHttpUrl(t)) return t;
    if (t.startsWith('[')) {
      try { return firstImage(JSON.parse(t)); } catch { return null; }
    }
  }
  return null;
}

export const DEFAULT_UA = 'StreamX/1.0 (Smart TV)';

export class XtreamClient {
  constructor(private readonly timeoutMs = 30_000, private readonly listTimeoutMs = 180_000) {}

  private async fetchUrl(url: string, timeout: number): Promise<Response> {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    try {
      const res = await fetch(url, { signal: ctrl.signal, credentials: 'omit', cache: 'no-store' });
      if (!res.ok) throw new XtreamException(res.status === 401 || res.status === 403 ? 'AUTH' : 'HTTP', res.status);
      return res;
    } catch (e) {
      if (e instanceof XtreamException) throw e;
      if ((e as Error)?.name === 'AbortError') throw new XtreamException('TIMEOUT');
      throw new XtreamException('NETWORK');
    } finally {
      clearTimeout(timer);
    }
  }

  private async json(url: string): Promise<unknown> {
    const res = await this.fetchUrl(url, this.timeoutMs);
    try { return await res.json(); } catch { throw new XtreamException('FORMAT'); }
  }

  async authenticate(server: string, c: XtreamCredentials, nowSeconds = Math.floor(Date.now() / 1000)): Promise<XtreamAccount> {
    const root = asObj(await this.json(apiUrl(server, c)));
    if (!root) throw new XtreamException('FORMAT');
    const user = asObj(root['user_info']);
    if (!user) throw new XtreamException('AUTH');
    if (text(user, 'auth') === '0') throw new XtreamException('AUTH');
    const status = text(user, 'status') ?? 'Active';
    const exp = num(user, 'exp_date');
    const formats = Array.isArray(user['allowed_output_formats'])
      ? (user['allowed_output_formats'] as unknown[]).filter((x): x is string => typeof x === 'string').map((x) => x.toLowerCase())
      : [];
    const account: XtreamAccount = {
      status,
      expiresAt: exp && exp > 0 ? exp : null,
      maxConnections: num(user, 'max_connections'),
      activeConnections: num(user, 'active_cons'),
      isTrial: text(user, 'is_trial') === '1',
      outputFormats: formats,
    };
    if (status.toLowerCase() !== 'active') throw new XtreamException(status.toLowerCase() === 'expired' ? 'EXPIRED' : 'INACTIVE');
    if (account.expiresAt != null && account.expiresAt < nowSeconds) throw new XtreamException('EXPIRED');
    return account;
  }

  /** category_id → category name. */
  async categories(server: string, c: XtreamCredentials, section: XtreamSection): Promise<Map<string, string>> {
    const out = new Map<string, string>();
    const res = await this.fetchUrl(apiUrl(server, c, ACTIONS[section][0]), this.listTimeoutMs);
    const s = new JsonArrayStream((e) => {
      const o = asObj(e);
      const id = text(o, 'category_id');
      if (id != null) out.set(id, text(o, 'category_name') ?? '');
    });
    await readTextStream(res, (t) => s.push(t));
    return out;
  }

  /** Streams every entry of a section, one parsed object at a time. */
  async list(server: string, c: XtreamCredentials, section: XtreamSection, onEntry: (e: Obj) => void): Promise<number> {
    const res = await this.fetchUrl(apiUrl(server, c, ACTIONS[section][1]), this.listTimeoutMs);
    const s = new JsonArrayStream((e) => { const o = asObj(e); if (o) onEntry(o); });
    await readTextStream(res, (t) => s.push(t));
    return s.count;
  }

  async seriesInfo(server: string, c: XtreamCredentials, seriesId: string): Promise<SeriesInfo> {
    const root = asObj(await this.json(apiUrl(server, c, 'get_series_info', { series_id: seriesId })));
    if (!root) throw new XtreamException('FORMAT');
    return parseSeriesInfo(root);
  }

  async movieInfo(server: string, c: XtreamCredentials, vodId: string): Promise<MovieInfo | null> {
    const root = asObj(await this.json(apiUrl(server, c, 'get_vod_info', { vod_id: vodId })));
    const info = asObj(root?.['info']);
    if (!info) return null;
    return {
      plot: cleanPlot(text(info, 'plot') ?? text(info, 'description')),
      genre: text(info, 'genre'),
      releaseDate: text(info, 'releasedate') ?? text(info, 'release_date'),
      durationSecs: num(info, 'duration_secs'),
      rating: text(info, 'rating'),
      cast: text(info, 'cast') ?? text(info, 'actors'),
      director: text(info, 'director'),
      backdrop: firstImage(info['backdrop_path']),
    };
  }
}

export function parseSeriesInfo(obj: Obj): SeriesInfo {
  const info = asObj(obj['info']);
  const seasons: { number: number; name: string }[] = [];
  const rawSeasons = Array.isArray(obj['seasons']) ? (obj['seasons'] as unknown[]) : [];
  for (const s of rawSeasons) {
    const o = asObj(s);
    const n = num(o, 'season_number');
    if (n != null) seasons.push({ number: n, name: text(o, 'name') ?? '' });
  }
  const episodes: Episode[] = [];
  const add = (seasonKey: number | null, raw: unknown) => {
    const o = asObj(raw);
    const id = text(o, 'id');
    if (!o || !id || !/^[\p{L}\p{N}]+$/u.test(id)) return;
    const ei = asObj(o['info']);
    const ext = (text(o, 'container_extension') ?? 'mp4').toLowerCase();
    episodes.push({
      id,
      season: num(o, 'season') ?? seasonKey ?? 1,
      episode: num(o, 'episode_num') ?? episodes.length + 1,
      title: text(o, 'title') ?? `E${episodes.length + 1}`,
      extension: /^[a-z0-9]{1,5}$/.test(ext) ? ext : 'mp4',
      plot: cleanPlot(text(ei, 'plot')),
      durationSecs: num(ei, 'duration_secs'),
      image: firstImage(ei?.['movie_image']),
    });
  };
  const eps = obj['episodes'];
  if (Array.isArray(eps)) {
    for (const group of eps) {
      if (Array.isArray(group)) for (const e of group) add(null, e);
      else add(null, group);
    }
  } else if (asObj(eps)) {
    for (const [k, v] of Object.entries(eps as Obj)) {
      const season = Number(k);
      if (Array.isArray(v)) for (const e of v) add(Number.isFinite(season) ? season : null, e);
    }
  }
  episodes.sort((a, b) => a.season - b.season || a.episode - b.episode);
  for (const e of episodes) if (!seasons.some((s) => s.number === e.season)) seasons.push({ number: e.season, name: '' });
  seasons.sort((a, b) => a.number - b.number);
  return {
    plot: cleanPlot(text(info, 'plot')),
    backdrop: firstImage(info?.['backdrop_path']),
    cover: firstImage(info?.['cover']),
    seasons,
    episodes,
  };
}

/** Same stable key as the Android app (XtreamMapper.stableKey). */
export const xtreamKey = (host: string, section: XtreamSection, id: string) => 'x' + sha1Hex(`xt|${host}|${section}|${id}`, 10);

const safeExtension = (raw: string | null, fallback: string) => {
  const l = raw?.toLowerCase();
  return l && l.length <= 5 && /^[\p{L}\p{N}]+$/u.test(l) ? l : fallback;
};

/** Panel entry → stored record. Names and categories are kept as sent; only the address becomes a login-free reference. */
export function mapEntry(
  section: XtreamSection,
  e: Obj,
  categories: Map<string, string>,
  ctx: { playlistId: number; generation: number; serverHost: string; account: XtreamAccount; position: number },
): Channel | null {
  const id = text(e, section === 'SERIES' ? 'series_id' : 'stream_id');
  if (!id || !/^[\p{L}\p{N}]+$/u.test(id)) return null;
  const rawName = text(e, 'name') ?? text(e, 'title');
  if (!rawName) return null;
  const name = rawName.substring(0, 300);
  const catIds = e['category_ids'];
  const categoryId = text(e, 'category_id') ?? (Array.isArray(catIds) && catIds.length > 0 ? String(catIds[0]) : null);
  const group = (categoryId != null ? categories.get(categoryId) ?? '' : '').substring(0, 300);
  const region = tagRegion(group);
  let kind: ContentKindCode;
  let url: string;
  let backup: string | null = null;
  let logo: string | null;
  if (section === 'LIVE') {
    kind = ContentKind.LIVE;
    url = makeRef(ctx.playlistId, 'live', id, liveExtension(ctx.account));
    const fb = liveFallbackExtension(ctx.account);
    backup = fb ? makeRef(ctx.playlistId, 'live', id, fb) : null;
    logo = text(e, 'stream_icon');
  } else if (section === 'MOVIES') {
    kind = ContentKind.MOVIE;
    url = makeRef(ctx.playlistId, 'movie', id, safeExtension(text(e, 'container_extension'), 'mp4'));
    logo = text(e, 'stream_icon') ?? text(e, 'cover');
  } else {
    kind = ContentKind.SERIES;
    url = makeRef(ctx.playlistId, 'series', id);
    logo = text(e, 'cover') ?? text(e, 'stream_icon');
  }
  const rating = num(e, 'rating') ?? ((num(e, 'rating_5based') ?? 0) * 2);
  const archive = section === 'LIVE' && text(e, 'tv_archive') === '1' ? Math.min(30, Math.max(0, num(e, 'tv_archive_duration') ?? 0)) : 0;
  return {
    id: `${ctx.playlistId}:${ctx.generation}:${ctx.position}`,
    playlistId: ctx.playlistId,
    generation: ctx.generation,
    position: ctx.position,
    name,
    nameNorm: normalize(name),
    url,
    backupUrls: backup ? [backup] : [],
    logo: isHttpUrl(logo) ? logo.substring(0, 2048) : null,
    groupTitle: group,
    category: null,
    tvgId: section === 'LIVE' ? text(e, 'epg_channel_id')?.substring(0, 300) ?? null : null,
    language: region.language,
    country: region.country,
    userAgent: null,
    referrer: null,
    drm: false,
    kind,
    channelKey: xtreamKey(ctx.serverHost, section, id),
    status: StreamStatus.UNKNOWN,
    statusCheckedAt: 0,
    archiveDays: archive,
    addedAt: Math.max(0, num(e, 'added') ?? num(e, 'last_modified') ?? 0),
    rating: Math.min(10, Math.max(0, Number.isFinite(rating) ? rating : 0)),
  };
}
