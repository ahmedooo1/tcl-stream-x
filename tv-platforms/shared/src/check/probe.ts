/**
 * Availability checks (StreamProbe.kt, ManifestInspector, AvailabilityChecker.kt): manifest, then
 * for HLS the lightest variant and one media segment. Account (Xtream) channels are never probed in
 * bulk: panels allow one or two connections and probing would use the user's slots.
 *
 * TV limit (docs/PLATFORMS.md): a web app cannot send a custom User-Agent or Referer with fetch,
 * and a server that refuses cross-origin reads looks like a network error. Such a failure is
 * recorded as "unknown", never as "offline", so no working channel is marked dead by mistake.
 */
import { StreamStatus } from '../storage/model';
import type { Channel } from '../storage/model';
import type { Db } from '../storage/db';
import { Store } from '../core/store';
import { isInternal } from '../xtream/urls';

export interface ProbeResult { status: StreamStatus; detail: string }
export interface Variant { uri: string; bandwidth: number; height: number }
export interface ManifestInfo { isHls: boolean; isDash: boolean; variants: Variant[]; segments: string[]; drm: boolean }

const DRM_KEY = /#EXT-X-(?:SESSION-)?KEY:[^\n]*(?:METHOD=SAMPLE-AES|KEYFORMAT="(?!identity")[^"]+")/i;

export function inspectManifest(text: string): ManifestInfo {
  const head = text.substring(0, 4096);
  if (head.includes('<MPD')) {
    const l = text.toLowerCase();
    const drm = l.includes('<contentprotection') && (l.includes('edef8ba9') || l.includes('9a04f079') || l.includes('cenc:default_kid'));
    return { isHls: false, isDash: true, variants: [], segments: [], drm };
  }
  if (!head.replace(/^[﻿ \r\n]+/, '').startsWith('#EXTM3U')) return { isHls: false, isDash: false, variants: [], segments: [], drm: false };
  const variants: Variant[] = [];
  const segments: string[] = [];
  let pending: string | null = null;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXT-X-STREAM-INF')) pending = line;
    else if (line.startsWith('#')) continue;
    else if (pending != null) {
      variants.push({ uri: line, bandwidth: Number(/BANDWIDTH=(\d+)/.exec(pending)?.[1] ?? 0), height: Number(/RESOLUTION=\d+x(\d+)/.exec(pending)?.[1] ?? 0) });
      pending = null;
    } else segments.push(line);
  }
  return { isHls: true, isDash: false, variants, segments, drm: DRM_KEY.test(text) };
}

export function statusForHttp(code: number): StreamStatus {
  if (code === 401 || code === 407) return StreamStatus.AUTH;
  if (code === 403 || code === 451) return StreamStatus.FORBIDDEN;
  return StreamStatus.OFFLINE;
}

const MAX_MANIFEST = 512 * 1024;
const SEGMENT_BYTES = 16 * 1024;
const TIMEOUT_MS = 10_000;

interface Fetched { code: number; finalUrl: string; body: string; bytes: number; isMedia: boolean }
type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

async function get(f: Fetcher, url: string, limit: number, range: boolean): Promise<Fetched> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const res = await f(url, { signal: ctl.signal, credentials: 'omit', headers: range ? { Range: `bytes=0-${limit - 1}` } : undefined });
    const type = (res.headers.get('content-type') ?? '').toLowerCase();
    let path = '';
    try { path = new URL(res.url || url).pathname.toLowerCase(); } catch { /* keep empty */ }
    const manifest = type.includes('mpegurl') || type.includes('dash+xml') || /\.(m3u8?|mpd)$/.test(path);
    const isMedia = !range && !manifest && (type.startsWith('video/') || type.startsWith('audio/') || type === 'application/octet-stream');
    let bytes = 0;
    const chunks: Uint8Array[] = [];
    if (res.ok && res.body) {
      const reader = res.body.getReader();
      while (bytes < limit) {
        const { value, done } = await reader.read();
        if (done || !value) break;
        chunks.push(value);
        bytes += value.byteLength;
        if (isMedia || range) break;
      }
      void reader.cancel().catch(() => {});
    }
    let body = '';
    if (!isMedia && !range && chunks.length) {
      const all = new Uint8Array(Math.min(bytes, limit));
      let o = 0;
      for (const c of chunks) { const n = Math.min(c.byteLength, all.length - o); all.set(c.subarray(0, n), o); o += n; if (o >= all.length) break; }
      body = new TextDecoder('utf-8').decode(all);
    }
    return { code: res.status, finalUrl: res.url || url, body, bytes, isMedia };
  } finally { clearTimeout(timer); }
}

const resolveUri = (base: string, uri: string) => { try { return new URL(uri, base).href; } catch { return null; } };

export async function probe(url: string, f: Fetcher = (u, i) => fetch(u, i)): Promise<ProbeResult> {
  try {
    const m = await get(f, url, MAX_MANIFEST, false);
    if (m.code < 200 || m.code > 299) return { status: statusForHttp(m.code), detail: `HTTP ${m.code}` };
    if (m.isMedia) return { status: StreamStatus.ONLINE, detail: 'direct media' };
    const info = inspectManifest(m.body);
    if (info.isDash) return info.drm ? { status: StreamStatus.DRM, detail: 'DASH protected by DRM' } : { status: StreamStatus.ONLINE, detail: 'DASH manifest' };
    if (!info.isHls) return { status: StreamStatus.INVALID, detail: 'not a stream manifest' };
    if (info.drm) return { status: StreamStatus.DRM, detail: 'HLS protected by DRM' };
    let mediaUrl = m.finalUrl;
    let media = info;
    if (info.variants.length) {
      const lightest = info.variants.reduce((a, b) => ((a.bandwidth || Infinity) <= (b.bandwidth || Infinity) ? a : b));
      const vu = resolveUri(m.finalUrl, lightest.uri);
      if (!vu) return { status: StreamStatus.INVALID, detail: 'bad variant URI' };
      const v = await get(f, vu, MAX_MANIFEST, false);
      if (v.code < 200 || v.code > 299) return { status: statusForHttp(v.code), detail: `variant HTTP ${v.code}` };
      mediaUrl = v.finalUrl;
      media = inspectManifest(v.body);
      if (media.drm) return { status: StreamStatus.DRM, detail: 'HLS protected by DRM' };
    }
    const last = media.segments[media.segments.length - 1];
    if (!last) return { status: StreamStatus.INVALID, detail: 'no segment' };
    const su = resolveUri(mediaUrl, last);
    if (!su) return { status: StreamStatus.INVALID, detail: 'bad segment URI' };
    const seg = await get(f, su, SEGMENT_BYTES, true);
    if (seg.code < 200 || seg.code > 299 || seg.bytes === 0) return { status: statusForHttp(seg.code), detail: `segment HTTP ${seg.code}` };
    return { status: StreamStatus.ONLINE, detail: `segment ${seg.bytes} B` };
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return { status: StreamStatus.TIMEOUT, detail: 'timeout' };
    if (e instanceof TypeError && /invalid url|failed to construct/i.test(e.message)) return { status: StreamStatus.INVALID, detail: 'invalid URL' };
    // Offline server or a refused cross-origin read: the web runtime does not say which.
    return { status: StreamStatus.UNKNOWN, detail: 'network or cross-origin' };
  }
}

export interface CheckProgress { running: boolean; done: number; total: number; online: number; last?: { key: string; status: StreamStatus } }

export class Checker {
  readonly progress = new Store<CheckProgress>({ running: false, done: 0, total: 0, online: 0 });
  private stopped = false;
  constructor(private readonly db: Db, private readonly f: Fetcher = (u, i) => fetch(u, i), private readonly concurrency = 4) {}

  cancel() { this.stopped = true; }

  /** Checks the channels, oldest results first, and writes each result to the database. */
  async check(all: Channel[], limit = 300): Promise<void> {
    if (this.progress.value.running) return;
    this.stopped = false;
    const channels = all.filter((c) => !isInternal(c.url)).sort((a, b) => a.statusCheckedAt - b.statusCheckedAt).slice(0, limit);
    let done = 0, online = 0, next = 0;
    this.progress.set({ running: true, done, total: channels.length, online });
    const worker = async () => {
      while (next < channels.length && !this.stopped) {
        const ch = channels[next++];
        let r: ProbeResult = { status: StreamStatus.UNKNOWN, detail: '' };
        if (ch.drm) r = { status: StreamStatus.DRM, detail: 'DRM declared by playlist' };
        else for (const u of [ch.url, ...ch.backupUrls]) { r = await probe(u, this.f); if (r.status === StreamStatus.ONLINE) break; }
        await this.db.updateStatus(ch.channelKey, r.status, Date.now());
        done++;
        if (r.status === StreamStatus.ONLINE) online++;
        this.progress.set({ running: true, done, total: channels.length, online, last: { key: ch.channelKey, status: r.status } });
      }
    };
    await Promise.all(Array.from({ length: this.concurrency }, worker));
    this.progress.set({ running: false, done, total: channels.length, online });
  }
}
