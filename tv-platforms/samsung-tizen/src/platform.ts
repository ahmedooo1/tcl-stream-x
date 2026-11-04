/**
 * Samsung Tizen edition: remote keys through tizen.tvinputdevice, video through AVPlay (HTML5 video
 * as a fallback, for the browser and very old firmwares), screen saver through webapis.appcommon.
 */
import { Action, commonAction, Platform } from '@shared/platform/platform';
import type { PlayerAdapter } from '@shared/player/types';
import { Html5Player } from '@shared/player/html5';
import { AVPlayPlayer, hasAVPlay } from './avplay';

interface TizenKey { name: string; code: number }
declare global {
  interface Window {
    tizen?: {
      tvinputdevice?: { getSupportedKeys(): TizenKey[]; registerKey(name: string): void; registerKeyBatch?(names: string[], ok?: () => void, err?: (e: unknown) => void): void };
      application?: { getCurrentApplication(): { exit(): void; hide(): void; appInfo?: { version?: string } } };
    };
  }
}

/** Keys the system keeps unless the app asks for them (Back, arrows, OK and Exit are always delivered). */
const WANTED: Record<string, Action> = {
  MediaPlayPause: 'playpause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
  MediaFastForward: 'ff', MediaRewind: 'rw', MediaTrackNext: 'next', MediaTrackPrevious: 'prev',
  ChannelUp: 'chup', ChannelDown: 'chdown', PreviousChannel: 'digit0',
  ColorF0Red: 'red', ColorF1Green: 'green', ColorF2Yellow: 'yellow', ColorF3Blue: 'blue',
  Info: 'info', Guide: 'guide', ChannelList: 'menu', Menu: 'menu', Tools: 'menu',
  '0': 'digit0', '1': 'digit1', '2': 'digit2', '3': 'digit3', '4': 'digit4',
  '5': 'digit5', '6': 'digit6', '7': 'digit7', '8': 'digit8', '9': 'digit9',
};
const RETURN = 10009;

export const VERSION = __APP_VERSION__;

export class TizenPlatform implements Platform {
  readonly id = 'samsung' as const;
  readonly label = 'StreamX Samsung';
  readonly version = VERSION;
  private codes = new Map<number, Action>();

  async init(): Promise<void> {
    const input = window.tizen?.tvinputdevice;
    if (!input) return; // a desktop browser: the common key map is enough
    let supported: TizenKey[] = [];
    try { supported = input.getSupportedKeys(); } catch { /* old firmware */ }
    for (const k of supported) {
      const a = WANTED[k.name];
      if (!a) continue;
      // Registering one key at a time: a key the model lacks must not stop the others.
      try { input.registerKey(k.name); this.codes.set(k.code, a); } catch { /* not available */ }
    }
  }

  action(e: KeyboardEvent): Action | null {
    if (e.keyCode === RETURN) return 'back';
    return this.codes.get(e.keyCode) ?? commonAction(e);
  }

  createPlayer(host: HTMLElement): PlayerAdapter {
    const av = window.webapis?.avplay;
    if (av && hasAVPlay()) {
      const object = document.createElement('object');
      object.setAttribute('type', 'application/avplayer');
      host.append(object);
      return new AVPlayPlayer(av, object);
    }
    const video = document.createElement('video');
    video.setAttribute('playsinline', '');
    host.append(video);
    return new Html5Player(video);
  }

  keepAwake(on: boolean): void {
    const ac = window.webapis?.appcommon;
    if (!ac) return;
    try {
      ac.setScreenSaver(on ? ac.AppCommonScreenSaverState.SCREEN_SAVER_OFF : ac.AppCommonScreenSaverState.SCREEN_SAVER_ON);
    } catch { /* privilege missing or old firmware: the TV keeps its own timer */ }
  }

  exit(): void {
    try { window.tizen?.application?.getCurrentApplication().exit(); } catch { window.close(); }
  }

  deviceInfo(): string {
    const pi = window.webapis?.productinfo;
    if (!pi) return navigator.userAgent;
    try { return `${pi.getModel()} · ${pi.getFirmware()}`; } catch { return 'Samsung Tizen'; }
  }

  onVisibility(cb: (visible: boolean) => void): void {
    document.addEventListener('visibilitychange', () => cb(!document.hidden));
  }
}
