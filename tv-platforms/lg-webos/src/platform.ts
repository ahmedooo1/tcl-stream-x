/**
 * LG webOS edition: HTML5 video (the webOS media pipeline plays HLS, DASH, MP4 and MPEG-TS through
 * <video>; hls.js is used only when the engine lacks native HLS), keys as DOM key codes (Back is 461).
 * Screen saver: webOS keeps the screen on while a <video> plays, there is no app API for it.
 */
import { Action, commonAction, Platform } from '@shared/platform/platform';
import type { PlayerAdapter } from '@shared/player/types';
import { Html5Player } from '@shared/player/html5';

declare global {
  interface Window {
    webOS?: { platformBack?(): void };
    PalmSystem?: { platformBack?(): void; deviceInfo?: string };
  }
}

const LG_KEYS: Record<number, Action> = {
  461: 'back',
  // Media keys of the Magic Remote and standard remotes.
  415: 'play', 19: 'pause', 413: 'stop', 417: 'ff', 412: 'rw',
  33: 'chup', 34: 'chdown',
  403: 'red', 404: 'green', 405: 'yellow', 406: 'blue',
  457: 'info', 458: 'guide',
};

export const VERSION = __APP_VERSION__;

export class WebOsPlatform implements Platform {
  readonly id = 'lg' as const;
  readonly label = 'StreamX LG';
  readonly version = VERSION;
  private info = 'LG webOS';

  async init(): Promise<void> {
    // PalmSystem is injected by the webOS web runtime itself (no webOSTV.js needed for this).
    try {
      const raw = window.PalmSystem?.deviceInfo;
      if (raw) {
        const d = JSON.parse(raw) as { modelName?: string; platformVersion?: string };
        this.info = [d.modelName, d.platformVersion ? `webOS ${d.platformVersion}` : null].filter(Boolean).join(' · ') || this.info;
      }
    } catch { /* unexpected format: keep the generic label */ }
  }

  action(e: KeyboardEvent): Action | null {
    return LG_KEYS[e.keyCode] ?? commonAction(e);
  }

  createPlayer(host: HTMLElement): PlayerAdapter {
    const video = document.createElement('video');
    video.setAttribute('playsinline', '');
    host.append(video);
    return new Html5Player(video);
  }

  keepAwake(_on: boolean): void { /* see the header comment */ }

  exit(): void {
    // webOS has no "close" for web apps; platformBack() returns to the launcher (LG guideline).
    try {
      if (window.webOS?.platformBack) window.webOS.platformBack();
      else if (window.PalmSystem?.platformBack) window.PalmSystem.platformBack();
      else window.close();
    } catch { window.close(); }
  }

  deviceInfo(): string { return this.info; }

  onVisibility(cb: (visible: boolean) => void): void {
    document.addEventListener('visibilitychange', () => cb(!document.hidden));
    // Older webOS versions send webOSRelaunch instead when the app is brought back.
    document.addEventListener('webOSRelaunch', () => cb(true));
  }
}
