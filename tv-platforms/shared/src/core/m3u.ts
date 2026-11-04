/**
 * Streaming M3U/M3U8 parser, port of M3uParser.kt. Text arrives in chunks (fetch stream, file
 * upload), entries are handed out one at a time, so a 100 MB playlist is never held in memory.
 * Every value is treated as untrusted and length-capped.
 */
import { cap, normalize } from './text';
import { sha1Hex } from './sha1';

export interface M3uEntry {
  name: string;
  url: string;
  logo: string | null;
  group: string;
  tvgId: string | null;
  language: string | null;
  country: string | null;
  userAgent: string | null;
  referrer: string | null;
  drm: boolean;
}

export interface M3uStats {
  entries: number;
  skippedUnsupported: number;
  skippedMalformed: number;
}

export const ContentKind = { LIVE: 0, MOVIE: 1, SERIES: 2 } as const;
export type ContentKindCode = (typeof ContentKind)[keyof typeof ContentKind];

export const MAX_NAME = 200;
export const MAX_GROUP = 120;
export const MAX_URL = 4096;
export const MAX_HEADER = 512;
export const MAX_ID = 120;

const HTTP_JSON_UA = /"user-agent"\s*:\s*"([^"]*)"/i;
const HTTP_JSON_REF = /"refere?r"\s*:\s*"([^"]*)"/i;

interface Pending {
  name: string;
  attrs: Record<string, string>;
  group: string;
  userAgent: string | null;
  referrer: string | null;
  drm: boolean;
}

export function isSupportedScheme(url: string): boolean {
  const l = url.toLowerCase();
  return l.startsWith('http://') || l.startsWith('https://');
}

const isWs = (c: string) => c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\v';

/** EXTINF line: `#EXTINF:-1 key="value" key2="a,b",Display name`. Commas inside quotes are not separators. */
export function parseExtinf(line: string): Pending {
  const attrs: Record<string, string> = {};
  let count = 0;
  let i = line.indexOf(':');
  i = i < 0 ? line.length : i + 1;
  while (i < line.length && !isWs(line[i]) && line[i] !== ',') i++;
  let titleStart = -1;
  while (i < line.length) {
    const c = line[i];
    if (c === ',') { titleStart = i + 1; break; }
    if (isWs(c)) { i++; continue; }
    const keyStart = i;
    while (i < line.length && line[i] !== '=' && line[i] !== ',' && !isWs(line[i])) i++;
    const key = line.substring(keyStart, i).toLowerCase();
    if (i < line.length && line[i] === '=') {
      i++;
      let value: string;
      if (i < line.length && (line[i] === '"' || line[i] === "'")) {
        const quote = line[i];
        let end = line.indexOf(quote, i + 1);
        if (end < 0) end = line.length;
        value = line.substring(i + 1, end);
        i = Math.min(end + 1, line.length);
      } else {
        const vs = i;
        while (i < line.length && !isWs(line[i]) && line[i] !== ',') i++;
        value = line.substring(vs, i);
      }
      if (key !== '' && count < 32) { attrs[key] = cap(value, MAX_URL); count++; }
    }
  }
  const title = titleStart >= 0 && titleStart <= line.length ? line.substring(titleStart) : '';
  return { name: cap(title.trim(), MAX_NAME), attrs, group: '', userAgent: null, referrer: null, drm: false };
}

export function headerEpg(line: string): string[] {
  const rest = line.replace(/^#EXTM3U/i, '').trim();
  const a = parseExtinf('#EXTINF:0 ' + rest + ',').attrs;
  const raw = [a['url-tvg'], a['x-tvg-url'], a['tvg-url']].filter((x): x is string => !!x);
  const out: string[] = [];
  for (const r of raw) for (const u of r.split(',')) {
    const t = u.trim();
    if ((t.startsWith('http://') || t.startsWith('https://')) && !out.includes(t)) out.push(t);
  }
  return out;
}

function toEntry(p: Pending, rawUrl: string): M3uEntry | null {
  let url = rawUrl;
  let ua = p.userAgent;
  let ref = p.referrer;
  const pipe = url.indexOf('|');
  if (pipe > 0) {
    for (const kv of url.substring(pipe + 1).split('&')) {
      const eq = kv.indexOf('=');
      const k = (eq < 0 ? kv : kv.substring(0, eq)).trim().toLowerCase();
      const v = eq < 0 ? '' : kv.substring(eq + 1).trim();
      if (k === 'user-agent') ua = cap(v, MAX_HEADER);
      else if (k === 'referer' || k === 'referrer') ref = cap(v, MAX_HEADER);
    }
    url = url.substring(0, pipe);
  }
  if (url.length > MAX_URL || /\s/.test(url)) return null;
  let title = p.name.trim() !== '' ? p.name : (p.attrs['tvg-name'] ?? '');
  if (title.trim() === '') title = url.substring(url.lastIndexOf('/') + 1).split('?')[0];
  const logo = p.attrs['tvg-logo'];
  const tvgId = p.attrs['tvg-id']?.trim();
  return {
    name: cap(title.trim(), MAX_NAME),
    url,
    logo: logo && logo.startsWith('http') ? cap(logo, MAX_URL) : null,
    group: p.group !== '' ? p.group : cap((p.attrs['group-title'] ?? '').trim(), MAX_GROUP),
    tvgId: tvgId ? cap(tvgId, MAX_ID) : null,
    language: p.attrs['tvg-language'] != null ? cap(p.attrs['tvg-language'], MAX_ID) : null,
    country: p.attrs['tvg-country'] != null ? cap(p.attrs['tvg-country'], MAX_ID) : null,
    userAgent: ua ?? (p.attrs['user-agent'] != null ? cap(p.attrs['user-agent'], MAX_HEADER) : null),
    referrer: ref ?? (p.attrs['referrer'] != null ? cap(p.attrs['referrer'], MAX_HEADER) : null),
    drm: p.drm,
  };
}

/**
 * Incremental parser: feed text chunks with [push], call [end] once. Lines split across chunks are
 * joined, so any chunk size works.
 */
export class M3uParser {
  readonly stats: M3uStats = { entries: 0, skippedUnsupported: 0, skippedMalformed: 0 };
  private carry = '';
  private first = true;
  private pending: Pending | null = null;

  constructor(
    private readonly onEntry: (e: M3uEntry) => void,
    private readonly onHeader: (epgUrls: string[]) => void = () => {},
  ) {}

  push(chunk: string): void {
    const text = this.carry + chunk;
    let start = 0;
    for (;;) {
      const nl = text.indexOf('\n', start);
      if (nl < 0) break;
      this.line(text.substring(start, nl));
      start = nl + 1;
    }
    this.carry = text.substring(start);
    // A pathological file without line breaks must not grow memory without bound.
    if (this.carry.length > 1_000_000) {
      this.carry = '';
      this.stats.skippedMalformed++;
    }
  }

  end(): M3uStats {
    if (this.carry !== '') this.line(this.carry);
    this.carry = '';
    if (this.pending) { this.stats.skippedMalformed++; this.pending = null; }
    return this.stats;
  }

  private line(raw: string): void {
    let line = raw;
    if (this.first) { line = line.replace(/^﻿/, ''); this.first = false; }
    line = line.trim();
    if (line === '') return;
    const upper = line.length >= 11 ? line.substring(0, 11).toUpperCase() : line.toUpperCase();
    if (upper.startsWith('#EXTM3U')) {
      this.onHeader(headerEpg(line));
    } else if (upper.startsWith('#EXTINF')) {
      if (this.pending) this.stats.skippedMalformed++;
      this.pending = parseExtinf(line);
    } else if (upper.startsWith('#EXTGRP:')) {
      if (this.pending && this.pending.group === '') this.pending.group = cap(line.substring(8).trim(), MAX_GROUP);
    } else if (upper.startsWith('#EXTVLCOPT:')) {
      const p = this.pending;
      if (p) {
        const opt = line.substring(11);
        const eq = opt.indexOf('=');
        if (eq > 0) {
          const k = opt.substring(0, eq).trim().toLowerCase();
          const v = opt.substring(eq + 1).trim();
          if (k === 'http-user-agent') p.userAgent = cap(v, MAX_HEADER);
          else if (k === 'http-referrer' || k === 'http-referer') p.referrer = cap(v, MAX_HEADER);
        }
      }
    } else if (upper.startsWith('#KODIPROP:')) {
      const p = this.pending;
      if (p) {
        const key = line.substring(10).split('=')[0].trim().toLowerCase();
        if (key.endsWith('license_type') || key.endsWith('license_key') || key.endsWith('drm_legacy') || key.endsWith('drm')) p.drm = true;
      }
    } else if (upper.startsWith('#EXTHTTP:')) {
      const p = this.pending;
      if (p) {
        const json = line.substring(9);
        const ua = HTTP_JSON_UA.exec(json);
        if (ua) p.userAgent = cap(ua[1], MAX_HEADER);
        const ref = HTTP_JSON_REF.exec(json);
        if (ref) p.referrer = cap(ref[1], MAX_HEADER);
      }
    } else if (line.startsWith('#')) {
      // other directives are not needed
    } else {
      const p = this.pending ?? { name: '', attrs: {}, group: '', userAgent: null, referrer: null, drm: false };
      this.pending = null;
      const entry = toEntry(p, line);
      if (entry == null) this.stats.skippedMalformed++;
      else if (!isSupportedScheme(entry.url)) this.stats.skippedUnsupported++;
      else { this.stats.entries++; this.onEntry(entry); }
    }
  }
}

/** Parses a whole text at once (small playlists, tests). */
export function parseM3u(text: string): { entries: M3uEntry[]; stats: M3uStats; epg: string[] } {
  const entries: M3uEntry[] = [];
  let epg: string[] = [];
  const p = new M3uParser((e) => entries.push(e), (u) => { epg = u; });
  p.push(text);
  const stats = p.end();
  return { entries, stats, epg };
}

const EPISODE = /\bS\d{1,2}\s*[.\-_ ]?\s*E\d{1,4}\b|\b\d{1,2}x\d{2,3}\b|(?:الحلقة|حلقة)\s*\d+|\bEp(?:isode)?\.?\s*\d+\b/i;
const VIDEO_FILE = /\.(mp4|mkv|avi|mov|m4v|webm|wmv|flv)$/i;
// Plural forms only: "Serie A" is a football channel group, not a series section.
const SERIES_WORDS = ['series', 'séries', 'tv show', 'مسلسل', 'مسلسلات', 'حلقات'];
const MOVIE_WORDS = ['vod', 'film', 'movie', 'cinema', 'cinéma', 'افلام', 'أفلام', 'فيلم', 'سينما'];

/**
 * Splits mixed playlists into sections: Xtream URL paths, episode markers, group names, then a
 * video-file extension. Anything else is a live channel.
 */
export function classify(e: Pick<M3uEntry, 'url' | 'group' | 'name'>): ContentKindCode {
  const path = e.url.split('?')[0].toLowerCase();
  const group = e.group.toLowerCase();
  if (path.includes('/series/')) return ContentKind.SERIES;
  if (path.includes('/movie/') || path.includes('/movies/')) return ContentKind.MOVIE;
  if (EPISODE.test(e.name)) return ContentKind.SERIES;
  if (SERIES_WORDS.some((w) => group.includes(w))) return ContentKind.SERIES;
  if (MOVIE_WORDS.some((w) => group.includes(w))) return ContentKind.MOVIE;
  if (VIDEO_FILE.test(path)) return ContentKind.MOVIE;
  return ContentKind.LIVE;
}

/** "Breaking Bad S02 E05 - Title" -> "Breaking Bad". */
export function seriesShow(name: string): string {
  const m = EPISODE.exec(name);
  if (!m) return name.trim();
  const head = name.substring(0, m.index).trim().replace(/[-|:.– ]+$/, '');
  return head.trim() === '' ? name.trim() : head;
}

/** Same key as the Android app for the same entry. */
export function channelKey(tvgId: string | null, name: string, group: string): string {
  const base = tvgId && tvgId.trim() !== ''
    ? `t:${tvgId.toLowerCase()}|${normalize(name)}`
    : `n:${normalize(name)}|${normalize(group)}`;
  return sha1Hex(base, 10);
}
