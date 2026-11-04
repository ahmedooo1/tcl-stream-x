/**
 * Samsung AVPlay back end (webapis.avplay): hardware decoding of HLS, DASH, MP4 and MPEG-TS on
 * Tizen TVs. The video is drawn on a plane under the page, through <object type="application/avplayer">;
 * the page stays transparent where the video shows.
 *
 * Known limits (docs/PLATFORMS.md): AVPlay accepts a User-Agent through setStreamingProperty but no
 * Referer header; DRM is never set up (StreamX does not play protected streams).
 */
import { PlaybackIssue, PlayerAdapter, PlayerListener, Rect, ResizeMode, Source, Track } from '@shared/player/types';

interface AVPlayTrack { index: number; type: 'VIDEO' | 'AUDIO' | 'TEXT' | 'SUBTITLE'; extra_info: string }
interface AVPlay {
  open(url: string): void;
  close(): void;
  prepareAsync(ok: () => void, err: (e: unknown) => void): void;
  play(): void;
  pause(): void;
  stop(): void;
  seekTo(ms: number, ok?: () => void, err?: (e: unknown) => void): void;
  getCurrentTime(): number;
  getDuration(): number;
  getState(): string;
  setDisplayRect(x: number, y: number, w: number, h: number): void;
  setDisplayMethod(m: string): void;
  setListener(l: Record<string, (...a: unknown[]) => void>): void;
  setStreamingProperty(k: string, v: string): void;
  getTotalTrackInfo(): AVPlayTrack[];
  getCurrentStreamInfo(): AVPlayTrack[];
  setSelectTrack(type: string, index: number): void;
  setSilentSubtitle(silent: boolean): void;
  setSpeed(rate: number): void;
  setTimeoutForBuffering?(s: number): void;
}

declare global {
  interface Window { webapis?: { avplay?: AVPlay; appcommon?: { setScreenSaver(state: number, ok?: () => void, err?: () => void): void; AppCommonScreenSaverState: { SCREEN_SAVER_OFF: number; SCREEN_SAVER_ON: number } }; productinfo?: { getModel(): string; getFirmware(): string; getDuid?(): string }; tvinfo?: unknown } }
}

export const hasAVPlay = (): boolean => !!window.webapis?.avplay;

const ERRORS: Record<string, PlaybackIssue> = {
  PLAYER_ERROR_CONNECTION_FAILED: 'NETWORK',
  PLAYER_ERROR_NETWORK_UNREACHABLE: 'NETWORK',
  PLAYER_ERROR_TIMED_OUT: 'TIMEOUT',
  PLAYER_ERROR_INVALID_URI: 'NOT_FOUND',
  PLAYER_ERROR_NOT_SUPPORTED_FILE: 'FORMAT',
  PLAYER_ERROR_NOT_SUPPORTED_FORMAT: 'FORMAT',
  PLAYER_ERROR_INVALID_OPERATION: 'UNKNOWN',
  PLAYER_ERROR_GENEREIC: 'UNKNOWN',
  PLAYER_ERROR_GENERIC: 'UNKNOWN',
  PLAYER_ERROR_NO_SUCH_FILE: 'NOT_FOUND',
  PLAYER_ERROR_SEEK_FAILED: 'UNKNOWN',
  PLAYER_ERROR_INVALID_STATE: 'UNKNOWN',
};

function info(t: AVPlayTrack): Record<string, string> {
  try { return JSON.parse(t.extra_info) as Record<string, string>; } catch { return {}; }
}

export class AVPlayPlayer implements PlayerAdapter {
  readonly name = 'avplay';
  private l: PlayerListener | null = null;
  private src: Source | null = null;
  private rect: Rect | null = null;
  private mode: ResizeMode = 0;
  private textOn = false;
  private textIndex = -1;
  private generation = 0;
  private cueTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly av: AVPlay, private readonly object: HTMLElement) {}

  setListener(l: PlayerListener): void { this.l = l; }

  private applyRect() {
    // AVPlay coordinates are those of the 1920x1080 TV plane, whatever the page resolution.
    const k = 1920 / (window.innerWidth || 1920);
    const r = this.rect;
    const o = this.object.style;
    if (!r) {
      o.left = '0'; o.top = '0'; o.width = '100%'; o.height = '100%';
      this.call(() => this.av.setDisplayRect(0, 0, 1920, 1080));
    } else {
      o.left = r.x + 'px'; o.top = r.y + 'px'; o.width = r.w + 'px'; o.height = r.h + 'px';
      this.call(() => this.av.setDisplayRect(Math.round(r.x * k), Math.round(r.y * k), Math.round(r.w * k), Math.round(r.h * k)));
    }
  }

  private applyMode() {
    const method = this.mode === 0 ? 'PLAYER_DISPLAY_MODE_LETTER_BOX' : this.mode === 1 ? 'PLAYER_DISPLAY_MODE_ZOOM_16_9' : 'PLAYER_DISPLAY_MODE_FULL_SCREEN';
    if (!this.call(() => this.av.setDisplayMethod(method)) && this.mode === 1) this.call(() => this.av.setDisplayMethod('PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO'));
  }

  private call(f: () => void): boolean {
    try { f(); return true; } catch { return false; }
  }

  load(src: Source): void {
    const gen = ++this.generation;
    this.closeQuietly();
    this.src = src;
    this.l?.state('loading');
    try {
      this.av.open(src.url);
    } catch {
      this.l?.error('NOT_FOUND', 'open failed');
      return;
    }
    if (src.userAgent) this.call(() => this.av.setStreamingProperty('USER_AGENT', src.userAgent!));
    this.call(() => this.av.setTimeoutForBuffering?.(15));
    this.av.setListener({
      onbufferingstart: () => { if (gen === this.generation) this.l?.state('loading'); },
      onbufferingcomplete: () => { if (gen === this.generation && this.av.getState() === 'PLAYING') this.l?.state('playing'); },
      onstreamcompleted: () => { if (gen === this.generation) this.l?.state('ended'); },
      onerror: (e: unknown) => {
        if (gen !== this.generation) return;
        const key = String(e);
        this.l?.error(ERRORS[key] ?? 'UNKNOWN', key);
      },
      onevent: () => {},
      oncurrentplaytime: () => {},
      // AVPlay hands over the text and its duration; the app draws it (subtitles.ts overlay).
      onsubtitlechange: (duration: unknown, text: unknown) => {
        if (gen !== this.generation || !this.textOn) return;
        const d = Number(duration);
        this.l?.cue?.(typeof text === 'string' && text.trim() ? text.replace(/<[^>]+>/g, '') : null);
        if (this.cueTimer) clearTimeout(this.cueTimer);
        if (d > 0) this.cueTimer = setTimeout(() => this.l?.cue?.(null), d);
      },
    });
    this.applyRect();
    this.applyMode();
    this.av.prepareAsync(() => {
      if (gen !== this.generation) return;
      if (src.startMs > 0 && !src.isLive) this.call(() => this.av.seekTo(src.startMs));
      if (this.textIndex >= 0) this.call(() => this.av.setSelectTrack('TEXT', this.textIndex));
      this.call(() => this.av.setSilentSubtitle(!this.textOn));
      this.av.play();
      this.l?.state('playing');
      this.l?.tracksChanged();
      this.l?.videoInfo(this.currentVideo());
    }, (e: unknown) => {
      if (gen !== this.generation) return;
      const key = String((e as { name?: string })?.name ?? e);
      this.l?.error(ERRORS[key] ?? 'FORMAT', key);
    });
  }

  private currentVideo() {
    try {
      const v = this.av.getCurrentStreamInfo().find((t) => t.type === 'VIDEO');
      if (!v) return null;
      const i = info(v);
      return { width: Number(i['Width'] ?? 0), height: Number(i['Height'] ?? 0), bitrate: Number(i['Bit_rate'] ?? 0), codec: i['fourCC'] ?? null, frameRate: 0 };
    } catch { return null; }
  }

  private closeQuietly() {
    this.call(() => this.av.stop());
    this.call(() => this.av.close());
  }

  play(): void { if (this.call(() => this.av.play())) this.l?.state('playing'); }
  pause(): void { if (this.call(() => this.av.pause())) this.l?.state('paused'); }
  stop(): void { this.generation++; this.src = null; this.closeQuietly(); this.l?.state('idle'); }
  seekTo(ms: number): void { this.call(() => this.av.seekTo(Math.max(0, Math.round(ms)))); }
  positionMs(): number { try { return this.av.getCurrentTime(); } catch { return 0; } }
  durationMs(): number { try { return this.src?.isLive ? 0 : this.av.getDuration(); } catch { return 0; } }

  setRect(r: Rect | null): void { this.rect = r; this.applyRect(); }
  setResizeMode(m: ResizeMode): void { this.mode = m; this.applyMode(); }
  setSpeed(rate: number): boolean { return this.call(() => this.av.setSpeed(rate)); }

  private tracks(type: 'AUDIO' | 'TEXT' | 'VIDEO'): AVPlayTrack[] {
    try { return this.av.getTotalTrackInfo().filter((t) => t.type === type || (type === 'TEXT' && t.type === 'SUBTITLE')); } catch { return []; }
  }
  private currentIndex(type: string): number {
    try { return this.av.getCurrentStreamInfo().find((t) => t.type === type)?.index ?? -1; } catch { return -1; }
  }

  audioTracks(): Track[] {
    const cur = this.currentIndex('AUDIO');
    return this.tracks('AUDIO').map((t, i) => {
      const x = info(t);
      const lang = x['language'] ?? null;
      const ch = Number(x['channels'] ?? 0);
      return { id: String(t.index), label: [lang, ch > 2 ? `${ch}ch` : null].filter(Boolean).join(' · ') || `#${i + 1}`, language: lang, selected: t.index === cur };
    });
  }

  textTracks(): Track[] {
    return this.tracks('TEXT').map((t, i) => {
      const lang = info(t)['track_lang'] ?? info(t)['language'] ?? null;
      return { id: String(t.index), label: lang || `#${i + 1}`, language: lang, selected: this.textOn && t.index === this.textIndex };
    });
  }

  qualities(): Track[] {
    // AVPlay chooses the variant itself; levels are listed for information, the choice stays automatic.
    return this.tracks('VIDEO').map((t) => {
      const x = info(t);
      return { id: String(t.index), label: `${x['Height'] ?? '?'}p`, language: null, selected: false };
    });
  }

  selectAudio(id: string): void { this.call(() => this.av.setSelectTrack('AUDIO', Number(id))); this.l?.tracksChanged(); }

  selectText(id: string | null): void {
    if (id == null) { this.textOn = false; this.call(() => this.av.setSilentSubtitle(true)); }
    else { this.textOn = true; this.textIndex = Number(id); this.call(() => this.av.setSelectTrack('TEXT', this.textIndex)); this.call(() => this.av.setSilentSubtitle(false)); }
    this.l?.tracksChanged();
  }

  selectQuality(id: string | null): void {
    if (id == null) this.call(() => this.av.setStreamingProperty('ADAPTIVE_INFO', ''));
    this.l?.tracksChanged();
  }

  destroy(): void { this.stop(); this.l = null; }
}
