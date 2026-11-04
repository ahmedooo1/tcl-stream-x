/**
 * Records stored on the TV. Same meaning as the Room entities of the Android app (Entities.kt), so
 * the screens and the behaviour map one to one.
 */
import type { ContentKindCode } from '../core/m3u';

export const StreamStatus = { UNKNOWN: 0, ONLINE: 1, OFFLINE: 2, FORBIDDEN: 3, GEO: 4, DRM: 5, TIMEOUT: 6, INVALID: 7, AUTH: 8 } as const;
export type StreamStatus = (typeof StreamStatus)[keyof typeof StreamStatus];

export interface Channel {
  /** IndexedDB key: `${playlistId}:${generation}:${position}`. */
  id: string;
  playlistId: number;
  generation: number;
  /** 1-based order inside the playlist, also the channel number. */
  position: number;
  name: string;
  nameNorm: string;
  /** Real URL for M3U entries; `xtream://...` reference (no login) for account entries. */
  url: string;
  backupUrls: string[];
  logo: string | null;
  groupTitle: string;
  category: string | null;
  tvgId: string | null;
  language: string | null;
  country: string | null;
  userAgent: string | null;
  referrer: string | null;
  drm: boolean;
  kind: ContentKindCode;
  /** Stable identity across re-imports: favorites and history. */
  channelKey: string;
  status: StreamStatus;
  statusCheckedAt: number;
  archiveDays: number;
  /** Seconds since epoch, 0 when unknown. */
  addedAt: number;
  rating: number;
}

export type PlaylistKind = 'builtin' | 'url' | 'file' | 'xtream';

export interface Playlist {
  id: number;
  name: string;
  kind: PlaylistKind;
  /** Source address for 'url'; server base for 'xtream' (never the login). */
  source: string | null;
  /** Generation currently shown; an import writes generation+1 then switches atomically. */
  generation: number;
  updatedAt: number;
  count: number;
  epgUrls: string[];
  /** Xtream: secret-free summary status|expiry|max|trial. */
  accountSummary: string | null;
  /** Xtream: id of the encrypted login in the vault. */
  vaultId: string | null;
  /** Categories per section (kind) with their sizes, in the order of the source, computed at import. */
  groups: Record<number, { groupTitle: string; count: number }[]>;
  /** Built-in catalog: version of the catalog file installed. */
  sourceVersion?: string | null;
}

export interface Favorite { channelKey: string; addedAt: number }
export interface HistoryItem { channelKey: string; watchedAt: number }

export interface VodProgress {
  key: string;
  title: string;
  url: string;
  poster: string | null;
  positionMs: number;
  durationMs: number;
  updatedAt: number;
}

export interface Programme {
  /** `${channelId}|${start}` */
  id: string;
  channelId: string;
  start: number;
  stop: number;
  title: string;
  description: string | null;
}

export interface LockedGroup { groupTitle: string; hidden: number }
