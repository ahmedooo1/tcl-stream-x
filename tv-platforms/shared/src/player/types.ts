/**
 * What every video back end offers (Samsung AVPlay, LG/HTML5 <video>). The playback logic of the
 * app (PlaybackController.kt on Android) sits above this interface and is the same on every TV.
 */
export type PlaybackIssue = 'NETWORK' | 'TIMEOUT' | 'FORBIDDEN' | 'NOT_FOUND' | 'AUTH' | 'GEO' | 'DRM' | 'FORMAT' | 'DECODER' | 'UNKNOWN';

/** Issues worth an automatic retry (PlaybackErrors.kt: retryable). */
export const RETRYABLE: Record<PlaybackIssue, boolean> = {
  NETWORK: true, TIMEOUT: true, UNKNOWN: true, FORBIDDEN: false, NOT_FOUND: false, AUTH: false, GEO: false, DRM: false, FORMAT: false, DECODER: false,
};

export type PlayerState = 'idle' | 'loading' | 'playing' | 'paused' | 'ended' | 'error';

export interface Track {
  id: string;
  label: string;
  language: string | null;
  selected: boolean;
}

export interface VideoInfo { width: number; height: number; bitrate: number; codec: string | null; frameRate: number }

export interface Source {
  url: string;
  isLive: boolean;
  /** Known container (from the URL or a forced retry as HLS). */
  mime: 'hls' | 'dash' | 'progressive' | null;
  userAgent: string | null;
  referrer: string | null;
  startMs: number;
}

export interface PlayerListener {
  state(s: PlayerState): void;
  error(issue: PlaybackIssue, detail: string): void;
  tracksChanged(): void;
  videoInfo(v: VideoInfo | null): void;
  /** The stream fell behind its live window (resumed after a long pause). */
  behindLiveWindow(): void;
}

/** 0 fit (letterbox), 1 zoom (crop), 2 stretch: the three modes of the Android player. */
export type ResizeMode = 0 | 1 | 2;

export interface Rect { x: number; y: number; w: number; h: number }

export interface PlayerAdapter {
  readonly name: string;
  setListener(l: PlayerListener): void;
  load(src: Source): void;
  play(): void;
  pause(): void;
  stop(): void;
  seekTo(ms: number): void;
  positionMs(): number;
  durationMs(): number;
  /** Video area in CSS pixels of the page; null = full screen. */
  setRect(r: Rect | null): void;
  setResizeMode(m: ResizeMode): void;
  setSpeed(rate: number): boolean;
  audioTracks(): Track[];
  textTracks(): Track[];
  qualities(): Track[];
  /** null = automatic / off. */
  selectAudio(id: string): void;
  selectText(id: string | null): void;
  selectQuality(id: string | null): void;
  destroy(): void;
}

export function inferMime(url: string): Source['mime'] {
  let path = url;
  try { path = new URL(url).pathname; } catch { /* keep the raw string */ }
  path = path.toLowerCase();
  if (path.endsWith('.m3u8') || path.endsWith('.m3u')) return 'hls';
  if (path.endsWith('.mpd')) return 'dash';
  if (/\.(mp4|m4v|mkv|webm|mov|ts|avi)$/.test(path)) return 'progressive';
  return null;
}

/** HTTP status → issue, same table as PlaybackErrors.kt. */
export function classifyHttp(status: number): PlaybackIssue {
  if (status === 401 || status === 407) return 'AUTH';
  if (status === 403 || status === 451) return 'FORBIDDEN';
  if (status === 404 || status === 410) return 'NOT_FOUND';
  if (status >= 500) return 'NETWORK';
  return 'UNKNOWN';
}
