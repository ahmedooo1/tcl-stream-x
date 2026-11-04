/** What differs between TV systems, behind one interface. Each edition (Samsung, LG) implements it. */
import type { PlayerAdapter } from '../player/types';

export type Action =
  | 'up' | 'down' | 'left' | 'right' | 'ok' | 'back'
  | 'playpause' | 'play' | 'pause' | 'stop' | 'ff' | 'rw' | 'next' | 'prev'
  | 'chup' | 'chdown' | 'info' | 'menu' | 'guide'
  | 'red' | 'green' | 'yellow' | 'blue'
  | `digit${0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9}`;

export interface Platform {
  id: 'samsung' | 'lg' | 'web';
  /** "StreamX Samsung", "StreamX LG". */
  label: string;
  version: string;
  /** Registers remote keys the system keeps by default (media, colour, digits). */
  init(): Promise<void>;
  action(e: KeyboardEvent): Action | null;
  /** Creates the video back end; `host` is where its element goes (under the page). */
  createPlayer(host: HTMLElement): PlayerAdapter;
  /** Screen saver off while a channel plays. */
  keepAwake(on: boolean): void;
  exit(): void;
  /** Model and firmware, for the about page and diagnostics. */
  deviceInfo(): string;
  /** Called when the app goes to the background / comes back (visibilitychange on both systems). */
  onVisibility(cb: (visible: boolean) => void): void;
  /**
   * Videos of the TV's USB storage ("Mes fichiers"), when the system lets a web app read them;
   * undefined when it does not (the screen then says so).
   */
  localVideos?(onProgress?: (found: number) => void): Promise<LocalVideo[]>;
}

export interface LocalVideo { title: string; uri: string; folder: string; storage: string }

/** Keys shared by browsers and both TV systems (W3C key names / DOM keyCodes). */
export function commonAction(e: KeyboardEvent): Action | null {
  switch (e.keyCode) {
    case 37: return 'left';
    case 38: return 'up';
    case 39: return 'right';
    case 40: return 'down';
    case 13: return 'ok';
    case 8: case 27: return 'back';
    case 415: return 'play';
    case 19: return 'pause';
    case 413: return 'stop';
    case 417: return 'ff';
    case 412: return 'rw';
    case 33: return 'chup';
    case 34: return 'chdown';
    case 403: return 'red';
    case 404: return 'green';
    case 405: return 'yellow';
    case 406: return 'blue';
    case 457: return 'info';
    case 458: return 'guide';
  }
  if (e.keyCode >= 48 && e.keyCode <= 57) return `digit${(e.keyCode - 48) as 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9}`;
  if (e.keyCode >= 96 && e.keyCode <= 105) return `digit${(e.keyCode - 96) as 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9}`;
  switch (e.key) {
    case 'MediaPlayPause': return 'playpause';
    case 'MediaTrackNext': return 'next';
    case 'MediaTrackPrevious': return 'prev';
    case 'ContextMenu': return 'menu';
  }
  return null;
}

export const digitOf = (a: Action): number | null => (a.startsWith('digit') ? Number(a.substring(5)) : null);
