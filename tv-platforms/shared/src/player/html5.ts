/**
 * <video> back end, used by the LG edition (webOS plays HLS natively in <video>) and as the
 * fallback when AVPlay is not present. hls.js is loaded only when the engine has MSE but no native
 * HLS (desktop browsers used for development).
 */
import { classifyHttp, PlaybackIssue, PlayerAdapter, PlayerListener, Rect, ResizeMode, Source, Track } from './types';

type HlsLike = {
  loadSource(u: string): void; attachMedia(v: HTMLVideoElement): void; destroy(): void;
  on(ev: string, cb: (e: string, d: Record<string, unknown>) => void): void;
  levels: { height: number; bitrate: number }[]; currentLevel: number;
  audioTracks: { name: string; lang?: string }[]; audioTrack: number;
  subtitleTracks: { name: string; lang?: string }[]; subtitleTrack: number;
};

export class Html5Player implements PlayerAdapter {
  readonly name = 'html5';
  private l: PlayerListener | null = null;
  private hls: HlsLike | null = null;
  private src: Source | null = null;

  constructor(private readonly video: HTMLVideoElement) {
    video.playsInline = true;
    video.preload = 'auto';
    const v = video;
    v.addEventListener('waiting', () => this.l?.state('loading'));
    v.addEventListener('playing', () => this.l?.state('playing'));
    v.addEventListener('pause', () => { if (!v.ended) this.l?.state('paused'); });
    v.addEventListener('ended', () => this.l?.state('ended'));
    v.addEventListener('loadedmetadata', () => {
      this.l?.tracksChanged();
      this.l?.videoInfo(v.videoWidth ? { width: v.videoWidth, height: v.videoHeight, bitrate: 0, codec: null, frameRate: 0 } : null);
    });
    v.addEventListener('error', () => {
      const code = v.error?.code ?? 0;
      // 2 network, 3 decode, 4 source not supported
      const issue: PlaybackIssue = code === 2 ? 'NETWORK' : code === 3 ? 'DECODER' : code === 4 ? 'FORMAT' : 'UNKNOWN';
      this.l?.error(issue, 'media error ' + code);
    });
  }

  setListener(l: PlayerListener): void { this.l = l; }

  load(src: Source): void {
    this.teardown();
    this.src = src;
    this.l?.state('loading');
    const v = this.video;
    const nativeHls = v.canPlayType('application/vnd.apple.mpegurl') !== '';
    const mse = typeof (window as unknown as { MediaSource?: unknown }).MediaSource !== 'undefined';
    if ((src.mime === 'hls' || src.mime == null) && !nativeHls && mse) {
      void import('hls.js').then(({ default: Hls }) => {
        if (this.src !== src) return;
        if (!Hls.isSupported()) { this.direct(src); return; }
        const hls = new Hls({ lowLatencyMode: false, backBufferLength: 30, maxBufferLength: 40, enableWorker: true }) as unknown as HlsLike;
        this.hls = hls;
        hls.on('hlsError', (_e, d) => {
          if (!d['fatal']) return;
          const resp = d['response'] as { code?: number } | undefined;
          const details = String(d['details'] ?? '');
          if (details === 'manifestParsingError' && src.mime == null) { this.direct(src); return; }
          if (details.includes('levelLoad') || details.includes('fragLoad') || details.includes('manifestLoad')) {
            this.l?.error(resp?.code ? classifyHttp(resp.code) : 'NETWORK', details);
          } else if (details.includes('bufferAppend') || details.includes('Incompatible')) {
            this.l?.error('DECODER', details);
          } else {
            this.l?.error('UNKNOWN', details);
          }
        });
        hls.on('hlsManifestParsed', () => this.l?.tracksChanged());
        hls.on('hlsLevelSwitched', () => {
          const lv = hls.levels[hls.currentLevel];
          if (lv) this.l?.videoInfo({ width: 0, height: lv.height, bitrate: lv.bitrate, codec: null, frameRate: 0 });
        });
        hls.loadSource(src.url);
        hls.attachMedia(v);
        this.seekStart(src);
        void v.play().catch(() => {});
      });
      return;
    }
    this.direct(src);
  }

  private direct(src: Source) {
    const v = this.video;
    v.src = src.url;
    this.seekStart(src);
    void v.play().catch(() => {});
  }

  private seekStart(src: Source) {
    if (src.startMs > 0 && !src.isLive) {
      const v = this.video;
      const once = () => { v.currentTime = src.startMs / 1000; v.removeEventListener('loadedmetadata', once); };
      v.addEventListener('loadedmetadata', once);
    }
  }

  private teardown() {
    if (this.hls) { this.hls.destroy(); this.hls = null; }
    this.video.removeAttribute('src');
    this.video.load();
  }

  play(): void { void this.video.play().catch(() => {}); }
  pause(): void { this.video.pause(); }
  stop(): void { this.src = null; this.teardown(); this.l?.state('idle'); }
  seekTo(ms: number): void { this.video.currentTime = Math.max(0, ms / 1000); }
  positionMs(): number { return this.video.currentTime * 1000; }
  durationMs(): number { const d = this.video.duration; return Number.isFinite(d) ? d * 1000 : 0; }

  setRect(r: Rect | null): void {
    const s = this.video.style;
    if (!r) { s.left = '0'; s.top = '0'; s.width = '100%'; s.height = '100%'; return; }
    s.left = r.x + 'px'; s.top = r.y + 'px'; s.width = r.w + 'px'; s.height = r.h + 'px';
  }

  setResizeMode(m: ResizeMode): void {
    this.video.style.objectFit = m === 0 ? 'contain' : m === 1 ? 'cover' : 'fill';
  }

  setSpeed(rate: number): boolean { this.video.playbackRate = rate; return true; }

  audioTracks(): Track[] {
    if (this.hls) return this.hls.audioTracks.map((t, i) => ({ id: String(i), label: t.name || t.lang || `#${i + 1}`, language: t.lang ?? null, selected: i === this.hls!.audioTrack }));
    const list = (this.video as unknown as { audioTracks?: ArrayLike<{ id: string; label: string; language: string; enabled: boolean }> }).audioTracks;
    if (!list) return [];
    return Array.from(list, (t, i) => ({ id: t.id || String(i), label: t.label || t.language || `#${i + 1}`, language: t.language || null, selected: t.enabled }));
  }

  textTracks(): Track[] {
    if (this.hls) return this.hls.subtitleTracks.map((t, i) => ({ id: String(i), label: t.name || t.lang || `#${i + 1}`, language: t.lang ?? null, selected: i === this.hls!.subtitleTrack }));
    return Array.from(this.video.textTracks, (t, i) => ({ id: String(i), label: t.label || t.language || `#${i + 1}`, language: t.language || null, selected: t.mode === 'showing' }));
  }

  qualities(): Track[] {
    if (!this.hls) return [];
    return this.hls.levels.map((lv, i) => ({ id: String(i), label: `${lv.height}p · ${(lv.bitrate / 1e6).toFixed(1)} Mb/s`, language: null, selected: this.hls!.currentLevel === i }));
  }

  selectAudio(id: string): void {
    if (this.hls) { this.hls.audioTrack = Number(id); return; }
    const list = (this.video as unknown as { audioTracks?: ArrayLike<{ id: string; enabled: boolean }> }).audioTracks;
    if (list) Array.from(list).forEach((t, i) => { t.enabled = (t.id || String(i)) === id; });
    this.l?.tracksChanged();
  }

  selectText(id: string | null): void {
    if (this.hls) { this.hls.subtitleTrack = id == null ? -1 : Number(id); this.l?.tracksChanged(); return; }
    Array.from(this.video.textTracks).forEach((t, i) => { t.mode = String(i) === id ? 'showing' : 'disabled'; });
    this.l?.tracksChanged();
  }

  selectQuality(id: string | null): void {
    if (this.hls) { this.hls.currentLevel = id == null ? -1 : Number(id); this.l?.tracksChanged(); }
  }

  destroy(): void { this.stop(); this.l = null; }
}
