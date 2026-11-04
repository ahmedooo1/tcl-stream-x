/**
 * The single player of the app and its logic, port of PlaybackController.kt: account references
 * resolved in memory right before playback, backup addresses tried in order, HLS retry for
 * addresses without extension, automatic retries on network errors, a watchdog for live streams that
 * load forever, live pause / back to live, previous channel, history, film progress and resume,
 * sleep timer, speed and picture format. The preview of the channel list and the full screen share
 * it, so going full screen never rebuffers.
 */
import { Store, sleep } from '../core/store';
import { ContentKind } from '../core/m3u';
import type { Channel } from '../storage/model';
import type { Db } from '../storage/db';
import { inferMime, PlaybackIssue, PlayerAdapter, PlayerState, RETRYABLE, ResizeMode, Track, VideoInfo } from './types';
import { isInternal } from '../xtream/urls';
import { redact } from '../xtream/urls';

export interface PlayItem {
  key: string;
  title: string;
  urls: string[];
  isLive: boolean;
  logo: string | null;
  poster: string | null;
  userAgent: string | null;
  referrer: string | null;
  drm: boolean;
  tvgId: string | null;
  channel: Channel | null;
  /** Group the channel was opened from: channel up/down stays inside it. */
  group: string | null;
  /** Next episodes of a series, played in order. */
  upNext: PlayItem[];
}

export function itemFromChannel(ch: Channel, group: string | null): PlayItem {
  return {
    key: ch.channelKey,
    title: ch.name,
    urls: [ch.url, ...ch.backupUrls],
    isLive: ch.kind === ContentKind.LIVE,
    logo: ch.logo,
    poster: ch.kind !== ContentKind.LIVE ? ch.logo : null,
    userAgent: ch.userAgent,
    referrer: ch.referrer,
    drm: ch.drm,
    tvgId: ch.tvgId,
    channel: ch,
    group,
    upNext: [],
  };
}

export interface PlayerUi {
  item: PlayItem | null;
  state: PlayerState;
  buffering: boolean;
  playing: boolean;
  issue: PlaybackIssue | null;
  attempt: string | null;
  video: VideoInfo | null;
  isFavorite: boolean;
  resizeMode: ResizeMode;
  speed: number;
  sleepMinutes: number | null;
  livePausedAt: number;
  liveBehindMs: number;
  previousTitle: string | null;
  tracksVersion: number;
}

const INITIAL: PlayerUi = {
  item: null, state: 'idle', buffering: false, playing: false, issue: null, attempt: null, video: null, isFavorite: false,
  resizeMode: 0, speed: 1, sleepMinutes: null, livePausedAt: 0, liveBehindMs: 0, previousTitle: null, tracksVersion: 0,
};

const MAX_AUTO_RETRIES = 2;
const RETRY_DELAY_MS = 2_500;
const STALL_MS = 25_000;
const PROGRESS_EVERY_MS = 10_000;

export class PlaybackController {
  readonly ui = new Store<PlayerUi>(INITIAL);
  private urlIndex = 0;
  private forceHls = false;
  private autoRetries = 0;
  private loadToken = 0;
  private stallTimer: ReturnType<typeof setTimeout> | null = null;
  private progressTimer: ReturnType<typeof setInterval> | null = null;
  private sleepTimer: ReturnType<typeof setInterval> | null = null;
  private previousLive: PlayItem | null = null;

  constructor(
    private readonly player: PlayerAdapter,
    private readonly db: Db,
    /** Turns an `xtream://` reference into a real address, in memory only. */
    private readonly resolve: (ref: string) => Promise<string | null>,
    private readonly keepAwake: (on: boolean) => void = () => {},
  ) {
    player.setListener({
      state: (s) => this.onState(s),
      error: (issue, detail) => void this.onError(issue, detail),
      tracksChanged: () => this.ui.update((u) => ({ ...u, tracksVersion: u.tracksVersion + 1 })),
      videoInfo: (v) => this.ui.update((u) => ({ ...u, video: v })),
      behindLiveWindow: () => { this.ui.update((u) => ({ ...u, liveBehindMs: 0, livePausedAt: 0 })); void this.load(false); },
    });
  }

  get adapter(): PlayerAdapter { return this.player; }

  private onState(s: PlayerState) {
    const u = this.ui.value;
    const buffering = s === 'loading';
    const playing = s === 'playing';
    this.ui.set({ ...u, state: s, buffering, playing, issue: playing ? null : u.issue, attempt: playing ? null : u.attempt });
    this.keepAwake(playing || buffering);
    this.watchStall(buffering);
    if (playing) {
      this.autoRetries = 0;
    }
    if (s === 'ended') {
      void this.saveProgress(true);
      this.playNext();
    }
  }

  private async onError(issue: PlaybackIssue, detail: string) {
    const item = this.ui.value.item;
    if (!item) return;
    console.warn('StreamX playback:', issue, redact(detail));
    const url = item.urls[this.urlIndex] ?? '';
    // Address without extension: try once as HLS before giving up on it (shouldRetryAsHls).
    if (!this.forceHls && (issue === 'FORMAT' || issue === 'UNKNOWN') && inferMime(url) == null && !isInternal(url)) {
      this.forceHls = true;
      await this.load(false);
      return;
    }
    if (this.urlIndex + 1 < item.urls.length) {
      this.urlIndex++;
      this.forceHls = false;
      await this.load(false);
      return;
    }
    if (RETRYABLE[issue] && this.autoRetries < MAX_AUTO_RETRIES) {
      this.autoRetries++;
      this.ui.update((u) => ({ ...u, buffering: true }));
      const token = this.loadToken;
      await sleep(RETRY_DELAY_MS * this.autoRetries);
      if (token !== this.loadToken) return;
      this.urlIndex = 0;
      this.forceHls = false;
      await this.load(false);
      return;
    }
    this.ui.update((u) => ({ ...u, issue, buffering: false, playing: false }));
    this.keepAwake(false);
  }

  /** A live stream stuck in "loading" with no error: retried, then reported as a timeout. */
  private watchStall(buffering: boolean) {
    if (this.stallTimer) { clearTimeout(this.stallTimer); this.stallTimer = null; }
    if (!buffering || !this.ui.value.item?.isLive) return;
    const token = this.loadToken;
    this.stallTimer = setTimeout(() => {
      if (token !== this.loadToken || !this.ui.value.buffering || this.ui.value.issue) return;
      if (this.autoRetries < MAX_AUTO_RETRIES) {
        this.autoRetries++;
        this.urlIndex = 0;
        this.forceHls = false;
        void this.load(false);
      } else {
        this.player.stop();
        this.ui.update((u) => ({ ...u, issue: 'TIMEOUT', buffering: false }));
      }
    }, STALL_MS);
  }

  async play(item: PlayItem): Promise<void> {
    await this.saveProgress(false);
    this.urlIndex = 0;
    this.forceHls = false;
    this.autoRetries = 0;
    const old = this.ui.value.item;
    if (old && old.isLive && item.isLive && old.key !== item.key) this.previousLive = old;
    const keep = this.ui.value;
    this.ui.set({ ...INITIAL, item, resizeMode: keep.resizeMode, sleepMinutes: keep.sleepMinutes, previousTitle: this.previousLive?.title ?? null });
    this.player.setSpeed(1);
    if (item.drm) {
      this.player.stop();
      this.ui.update((u) => ({ ...u, issue: 'DRM' }));
      return;
    }
    await this.load(!item.isLive);
    if (item.isLive) {
      await this.db.touchHistory(item.key);
      await this.db.setSetting('lastChannel', item.key);
    }
    const fav = await this.db.isFavorite(item.key);
    this.ui.update((u) => ({ ...u, isFavorite: fav }));
    this.startProgress();
  }

  private async load(resumeFromSaved: boolean): Promise<void> {
    const item = this.ui.value.item;
    if (!item) return;
    const token = ++this.loadToken;
    const ref = item.urls[this.urlIndex];
    if (!ref) return;
    let url: string | null = ref;
    if (isInternal(ref)) {
      url = await this.resolve(ref);
      if (token !== this.loadToken) return;
      if (!url) {
        this.player.stop();
        this.ui.update((u) => ({ ...u, issue: 'AUTH', buffering: false }));
        return;
      }
    }
    let startMs = 0;
    if (resumeFromSaved) {
      const saved = await this.db.progress(item.key);
      if (saved && saved.positionMs > 10_000 && saved.positionMs < saved.durationMs * 0.95) startMs = saved.positionMs;
    }
    this.ui.update((u) => ({
      ...u,
      attempt: item.urls.length > 1 ? `${this.urlIndex + 1}/${item.urls.length}` : null,
      livePausedAt: 0,
      liveBehindMs: item.isLive ? 0 : u.liveBehindMs,
    }));
    this.player.load({
      url,
      isLive: item.isLive,
      mime: this.forceHls ? 'hls' : inferMime(ref.startsWith('xtream://') ? ref : url),
      userAgent: item.userAgent,
      referrer: item.referrer,
      startMs,
    });
  }

  ensurePrepared(): void {
    const u = this.ui.value;
    if (u.item && u.state === 'idle' && !u.item.drm) void this.load(!u.item.isLive);
  }

  retry(): void {
    this.urlIndex = 0;
    this.forceHls = false;
    this.autoRetries = 0;
    this.ui.update((u) => ({ ...u, issue: null }));
    void this.load(false);
  }

  // ---- pause, live pause, previous channel --------------------------------------------------

  togglePause(): void {
    if (this.ui.value.playing || this.ui.value.buffering) this.pause(); else this.resume();
  }

  pause(): void {
    this.player.pause();
    const u = this.ui.value;
    if (u.item?.isLive && u.livePausedAt === 0) this.ui.update((x) => ({ ...x, livePausedAt: Date.now() }));
  }

  resume(): void {
    const at = this.ui.value.livePausedAt;
    if (at > 0) this.ui.update((u) => ({ ...u, livePausedAt: 0, liveBehindMs: u.liveBehindMs + (Date.now() - at) }));
    this.player.play();
  }

  /** Back to the live edge after a pause: the stream is reopened at its live position. */
  goLive(): void {
    this.ui.update((u) => ({ ...u, livePausedAt: 0, liveBehindMs: 0 }));
    void this.load(false);
  }

  recall(): boolean {
    const prev = this.previousLive;
    if (!prev) return false;
    void this.play(prev);
    return true;
  }

  // ---- zapping and numbers ------------------------------------------------------------------

  async zap(dir: 1 | -1): Promise<void> {
    const item = this.ui.value.item;
    const ch = item?.channel;
    if (!item || !ch) return;
    const next = await this.db.neighbour(ch, item.group, dir);
    if (next && next.channelKey !== ch.channelKey) await this.play(itemFromChannel(next, item.group));
  }

  async playNumber(n: number): Promise<void> {
    if (n === 0) { this.recall(); return; }
    const ch = this.ui.value.item?.channel;
    if (!ch) return;
    const p = (await this.db.playlists()).find((x) => x.id === ch.playlistId);
    if (!p) return;
    const target = await this.db.byNumber(p, ch.kind, n);
    if (target) await this.play(itemFromChannel(target, null));
  }

  async toggleFavorite(): Promise<void> {
    const item = this.ui.value.item;
    if (!item) return;
    const now = await this.db.toggleFavorite(item.key);
    this.ui.update((u) => ({ ...u, isFavorite: now }));
  }

  playNext(): boolean {
    const item = this.ui.value.item;
    const next = item?.upNext[0];
    if (!item || !next) return false;
    void this.play({ ...next, upNext: item.upNext.slice(1) });
    return true;
  }

  // ---- seeking, speed, sleep, format, tracks ------------------------------------------------

  seekBy(deltaMs: number): void {
    const d = this.player.durationMs();
    let t = Math.max(0, this.player.positionMs() + deltaMs);
    if (d > 0) t = Math.min(t, d - 1000);
    this.player.seekTo(t);
  }

  seekTo(ms: number): void { this.player.seekTo(ms); }

  cycleSpeed(): void {
    const speeds = [1, 1.25, 1.5, 2, 0.75];
    const next = speeds[(speeds.indexOf(this.ui.value.speed) + 1) % speeds.length];
    if (this.player.setSpeed(next)) this.ui.update((u) => ({ ...u, speed: next }));
  }

  cycleSleep(): void {
    const steps: (number | null)[] = [null, 15, 30, 60, 90];
    const next = steps[(steps.indexOf(this.ui.value.sleepMinutes) + 1) % steps.length];
    if (this.sleepTimer) { clearInterval(this.sleepTimer); this.sleepTimer = null; }
    this.ui.update((u) => ({ ...u, sleepMinutes: next }));
    if (next == null) return;
    this.sleepTimer = setInterval(() => {
      const left = (this.ui.value.sleepMinutes ?? 1) - 1;
      if (left <= 0) {
        if (this.sleepTimer) clearInterval(this.sleepTimer);
        this.sleepTimer = null;
        this.player.pause();
        this.keepAwake(false);
        this.ui.update((u) => ({ ...u, sleepMinutes: null }));
      } else {
        this.ui.update((u) => ({ ...u, sleepMinutes: left }));
      }
    }, 60_000);
  }

  cycleResize(): void {
    const m = ((this.ui.value.resizeMode + 1) % 3) as ResizeMode;
    this.player.setResizeMode(m);
    this.ui.update((u) => ({ ...u, resizeMode: m }));
  }

  audio(): Track[] { return this.player.audioTracks(); }
  text(): Track[] { return this.player.textTracks(); }
  qualities(): Track[] { return this.player.qualities(); }

  // ---- progress and lifecycle ---------------------------------------------------------------

  private startProgress() {
    if (this.progressTimer) clearInterval(this.progressTimer);
    this.progressTimer = setInterval(() => void this.saveProgress(false), PROGRESS_EVERY_MS);
  }

  private async saveProgress(completed: boolean): Promise<void> {
    const item = this.ui.value.item;
    if (!item || item.isLive) return;
    const duration = this.player.durationMs();
    if (!(duration > 0)) return;
    const position = completed ? duration : this.player.positionMs();
    await this.db.saveProgress({ key: item.key, title: item.title, url: item.urls[0], poster: item.poster, positionMs: position, durationMs: duration, updatedAt: Date.now() });
  }

  async stop(): Promise<void> {
    await this.saveProgress(false);
    this.loadToken++;
    if (this.progressTimer) { clearInterval(this.progressTimer); this.progressTimer = null; }
    if (this.stallTimer) { clearTimeout(this.stallTimer); this.stallTimer = null; }
    if (this.sleepTimer) { clearInterval(this.sleepTimer); this.sleepTimer = null; }
    this.player.stop();
    this.keepAwake(false);
    this.ui.set({ ...INITIAL, resizeMode: this.ui.value.resizeMode });
  }

  /** The app goes to the background (TV standby, Home key): free the decoder. */
  async onBackground(): Promise<void> {
    await this.saveProgress(false);
    this.loadToken++;
    this.player.stop();
    this.keepAwake(false);
  }
}
