/**
 * Application shell: rail, screens, back stack, remote keys, dialogs. Mirrors AppRoot.kt: the rail
 * keeps a fixed slot and opens over the content (which dims), sections replace each other (back
 * returns to Home), Home asks before leaving the app.
 */
import { Db, openDb, S } from '../storage/db';
import { Vault } from '../security/vault';
import { Importer, CatalogFile } from '../core/importer';
import { PlaybackController } from '../player/controller';
import { parseRef, resolveRef } from '../xtream/urls';
import { FocusManager } from './focus';
import { h, installImageFallback, onActivate } from './dom';
import { icon } from './icons';
import { getLang, Key, setLang, t, Lang } from '../i18n/index';
import { esc } from '../core/text';
import type { Action, Platform } from '../platform/platform';
import { Store } from '../core/store';
import { DEVELOPER_NAME } from './about';
import { EpgService } from '../epg/service';
import { Checker } from '../check/probe';

export interface Screen {
  el: HTMLElement;
  /** Called once the element is in the page. */
  mount?(): void;
  unmount?(): void;
  /** Coming back to this screen (after the player, a sub screen). */
  resume?(): void;
  /** Keys the screen handles itself; return true when used. */
  onKey?(a: Action, repeat: boolean): boolean;
  /** Hides the rail (player). */
  fullscreen?: boolean;
}

export type Route = 'home' | 'live' | 'movies' | 'series' | 'sports' | 'animation' | 'local' | 'search' | 'playlists' | 'settings' | 'player' | 'guide';

export interface Section { route: Route; label: Key; icon: string }

export const SECTIONS: Section[] = [
  { route: 'home', label: 'nav_home', icon: 'Home' },
  { route: 'live', label: 'nav_live', icon: 'LiveTv' },
  { route: 'movies', label: 'nav_movies', icon: 'Movie' },
  { route: 'series', label: 'nav_series', icon: 'Series' },
  { route: 'sports', label: 'nav_sports', icon: 'Sports' },
  { route: 'animation', label: 'nav_animation', icon: 'Animation' },
  { route: 'local', label: 'nav_local', icon: 'Folder' },
  { route: 'search', label: 'nav_search', icon: 'Search' },
  { route: 'playlists', label: 'nav_playlists', icon: 'Playlist' },
  { route: 'settings', label: 'nav_settings', icon: 'Settings' },
];

export interface AppSettings {
  lang: Lang;
  previewOnFocus: boolean;
  epgUrl: string;
  autoRefresh: boolean;
  lastEpgRefresh: number;
  subtitleScale: number;
  /** Sports hub: whose rights holders are shown ("FR" or "MENA"). */
  sportsRegion: 'FR' | 'MENA';
}

export type ScreenFactory = (ctx: Ctx, params: Record<string, unknown>) => Screen;

export interface Ctx {
  db: Db;
  vault: Vault;
  importer: Importer;
  playback: PlaybackController;
  platform: Platform;
  focus: FocusManager;
  epg: EpgService;
  checker: Checker;
  settings: Store<AppSettings>;
  saveSettings(patch: Partial<AppSettings>): Promise<void>;
  go(route: Route, params?: Record<string, unknown>): void;
  push(route: Route, params?: Record<string, unknown>): void;
  back(): void;
  toast(text: string): void;
  dialog(opts: DialogOptions): Promise<string | null>;
  current(): Route;
  /** Hidden (parental lock) category names. */
  hidden(): Promise<Set<string>>;
}

export interface DialogOptions {
  title: string;
  body?: string;
  /** Buttons: key → label; the first one has the focus unless `focus` is set. */
  buttons: [string, string][];
  focus?: string;
  /** Free text field shown above the buttons; its value is returned as `key:value`. */
  input?: { placeholder: string; value?: string; type?: 'text' | 'password' | 'number' };
}

const DEFAULTS: AppSettings = { lang: 'fr', previewOnFocus: true, epgUrl: '', autoRefresh: true, lastEpgRefresh: 0, subtitleScale: 1, sportsRegion: 'FR' };

export async function startApp(platform: Platform, screens: Record<Route, ScreenFactory>, catalog: CatalogFile | null): Promise<void> {
  const root = document.getElementById('app')!;
  installImageFallback(root);
  await platform.init();

  const db = new Db(await openDb());
  const vault = new Vault(db);
  const importer = new Importer(db, vault);
  const saved = await db.setting<AppSettings>('app', DEFAULTS);
  const settings = new Store<AppSettings>({ ...DEFAULTS, ...saved });
  setLang(settings.value.lang);

  root.innerHTML = `
    <div id="video"></div>
    <div id="host"></div>
    <div id="dim"></div>
    <nav id="rail" data-restore></nav>
    <div id="overlay"></div>`;
  const host = root.querySelector<HTMLElement>('#host')!;
  const rail = root.querySelector<HTMLElement>('#rail')!;
  const dim = root.querySelector<HTMLElement>('#dim')!;
  const overlay = root.querySelector<HTMLElement>('#overlay')!;
  const focus = new FocusManager(root);

  /** Account references → real address, in memory only, right before playback or a guide download. */
  const resolve = async (ref: string): Promise<string | null> => {
    const parsed = parseRef(ref);
    if (!parsed) return null;
    const p = (await db.playlists()).find((x) => x.id === parsed.playlistId);
    if (!p?.source || !p.vaultId) return null;
    const c = await vault.load(p.vaultId);
    return c ? resolveRef(parsed, p.source, c) : null;
  };
  const player = platform.createPlayer(root.querySelector<HTMLElement>('#video')!);
  const playback = new PlaybackController(player, db, resolve, (on) => platform.keepAwake(on));
  const epg = new EpgService(db, resolve);

  type Entry = { route: Route; params: Record<string, unknown>; screen: Screen; focused: HTMLElement | null };
  const stack: Entry[] = [];

  function renderRail() {
    const cur = [...stack].reverse().find((e) => SECTIONS.some((s) => s.route === e.route))?.route ?? 'home';
    rail.innerHTML = `
      <div class="brand"><img src="${new URL('../../assets/img/logo_mark.png', import.meta.url).href}" alt=""><span class="label t-title">StreamX</span></div>
      ${SECTIONS.map((s) => `<div class="row fx${s.route === cur ? ' selected' : ''}" data-k="${s.route}">${icon(s.icon, 24)}<span class="label t-title">${esc(t(s.label))}</span></div>`).join('')}
      <div class="credit"><div class="t-label faint">${esc(t('about_made_by'))}</div><div class="t-label">${esc(DEVELOPER_NAME)}</div><div class="t-mono faint">ahmadahmad.aaweb.fr</div></div>`;
  }

  rail.addEventListener('fx:focus', () => { rail.classList.add('open'); dim.classList.add('on'); });
  root.addEventListener('fx:focus', (e) => {
    if (!rail.contains(e.target as Node)) { rail.classList.remove('open'); dim.classList.remove('on'); }
  });
  onActivate(rail, (k) => ctx.go(k as Route));

  function show(entry: Entry, restore: HTMLElement | null) {
    host.replaceChildren(entry.screen.el);
    document.body.classList.toggle('no-rail', !!entry.screen.fullscreen);
    // AVPlay draws under the page: the page lets the video through on the screens that show it.
    document.body.classList.toggle('video-on', entry.route === 'player' || entry.route === 'live');
    entry.screen.mount?.();
    renderRail();
    if (restore && host.contains(restore)) focus.focus(restore);
    else { focus.current = null; focus.ensure(); }
  }

  function leave(entry: Entry) {
    entry.focused = focus.current && host.contains(focus.current) ? focus.current : null;
    entry.screen.unmount?.();
  }

  let toastTimer: ReturnType<typeof setTimeout> | null = null;

  const ctx: Ctx = {
    db, vault, importer, playback, platform, focus, epg, settings, checker: new Checker(db),
    async saveSettings(patch) {
      const next = { ...settings.value, ...patch };
      settings.set(next);
      await db.setSetting('app', next);
      if (patch.lang && patch.lang !== getLang()) {
        setLang(patch.lang);
        // Re-create the screens in the new language and direction.
        const top = stack[stack.length - 1];
        if (top) { leave(top); top.screen = screens[top.route](ctx, top.params); show(top, null); }
      }
    },
    go(route, params = {}) {
      const top = stack[stack.length - 1];
      if (top && top.route === route && route !== 'player') { focus.ensure(); return; }
      while (stack.length) { const e = stack.pop()!; e.screen.unmount?.(); }
      if (route !== 'home') {
        const home: Entry = { route: 'home', params: {}, screen: screens.home(ctx, {}), focused: null };
        stack.push(home);
      }
      const entry: Entry = { route, params, screen: screens[route](ctx, params), focused: null };
      stack.push(entry);
      show(entry, null);
    },
    push(route, params = {}) {
      const top = stack[stack.length - 1];
      if (top) leave(top);
      const entry: Entry = { route, params, screen: screens[route](ctx, params), focused: null };
      stack.push(entry);
      show(entry, null);
    },
    back() {
      if (overlay.childElementCount > 0) return;
      if (stack.length <= 1) { void askExit(); return; }
      const e = stack.pop()!;
      e.screen.unmount?.();
      const prev = stack[stack.length - 1];
      show(prev, prev.focused);
      prev.screen.resume?.();
    },
    toast(text) {
      overlay.querySelector('.toast')?.remove();
      const el = h(`<div class="toast t-body">${esc(text)}</div>`);
      overlay.append(el);
      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(() => el.remove(), 3500);
    },
    dialog(opts) {
      return new Promise((resolveDialog) => {
        const prevFocus = focus.current;
        const input = opts.input
          ? `<input class="field fx auto" type="${opts.input.type === 'password' ? 'password' : opts.input.type === 'number' ? 'tel' : 'text'}" placeholder="${esc(opts.input.placeholder)}" value="${esc(opts.input.value ?? '')}" autocomplete="off" spellcheck="false">`
          : '';
        const el = h(`<div class="modal" data-scope>
          <div class="dialog">
            <div class="t-headline auto">${esc(opts.title)}</div>
            ${opts.body ? `<div class="t-body dim auto" style="white-space:pre-line">${esc(opts.body)}</div>` : ''}
            ${input}
            <div class="buttons">${opts.buttons.map(([k, label]) => `<div class="row fx" data-k="${esc(k)}"><span class="t-title">${esc(label)}</span></div>`).join('')}</div>
          </div></div>`);
        overlay.append(el);
        const finish = (v: string | null) => {
          el.remove();
          dialogClose = null;
          focus.current = null;
          if (prevFocus && document.contains(prevFocus)) focus.focus(prevFocus); else focus.ensure();
          resolveDialog(v);
        };
        dialogClose = () => finish(null);
        onActivate(el, (k) => {
          const field = el.querySelector<HTMLInputElement>('input');
          finish(field ? `${k}:${field.value}` : k);
        });
        const target = opts.focus ? el.querySelector<HTMLElement>(`[data-k="${CSS.escape(opts.focus)}"]`) : el.querySelector<HTMLElement>('.fx');
        focus.focus(target);
      });
    },
    current: () => stack[stack.length - 1]?.route ?? 'home',
    hidden: () => db.hiddenGroups(),
  };

  let dialogClose: (() => void) | null = null;

  async function askExit() {
    // Focus starts on "Stay": a stray Back never closes the app (ExitDialog.kt).
    const r = await ctx.dialog({ title: t('exit_title'), buttons: [['stay', t('exit_stay')], ['quit', t('exit_quit')]], focus: 'stay' });
    if (r === 'quit') { await playback.stop(); platform.exit(); }
  }

  // ---- remote keys ----
  let okDown = 0;
  let longFired = false;
  document.addEventListener('keydown', (e) => {
    const a = platform.action(e);
    if (!a) return;
    const typing = document.activeElement instanceof HTMLInputElement;
    if (typing && a !== 'back' && a !== 'up' && a !== 'down') return; // the TV keyboard handles the field
    e.preventDefault();
    if (a === 'back') {
      if (typing) { (document.activeElement as HTMLInputElement).blur(); return; }
      if (dialogClose) { dialogClose(); return; }
      const top = stack[stack.length - 1];
      if (top?.screen.onKey?.('back', e.repeat)) return;
      ctx.back();
      return;
    }
    if (a === 'ok') {
      if (!e.repeat) { okDown = Date.now(); longFired = false; return; }
      if (!longFired && Date.now() - okDown > 600) {
        longFired = true;
        focus.current?.dispatchEvent(new CustomEvent('fx:long', { bubbles: true }));
      }
      return;
    }
    const top = stack[stack.length - 1];
    if (!overlay.querySelector('[data-scope]') && top?.screen.onKey?.(a, e.repeat)) return;
    if (a === 'up' || a === 'down' || a === 'left' || a === 'right') focus.move(a);
  });
  document.addEventListener('keyup', (e) => {
    const a = platform.action(e);
    if (a !== 'ok') return;
    e.preventDefault();
    if (longFired) { longFired = false; return; }
    const el = focus.current;
    if (!el) return;
    if (el instanceof HTMLInputElement) { el.focus(); return; }
    const top = stack[stack.length - 1];
    if (!overlay.querySelector('[data-scope]') && top?.screen.onKey?.('ok', false)) return;
    el.dispatchEvent(new CustomEvent('fx:click', { bubbles: true }));
  });

  platform.onVisibility((visible) => {
    if (!visible) void playback.onBackground();
    else { playback.ensurePrepared(); void refreshStale(); }
  });

  // ---- first start: built-in catalog, then stale playlists and the guide ----
  if (catalog) { try { await importer.installCatalog(catalog, 'StreamX'); } catch (err) { console.warn('catalog', err); } }
  ctx.go('home');

  async function refreshStale() {
    const s = settings.value;
    if (s.autoRefresh) {
      for (const p of await db.playlists()) {
        if ((p.kind === 'url' || p.kind === 'xtream') && Date.now() - p.updatedAt > 24 * 3600_000) {
          try { await importer.refresh(p); } catch { /* shown in Playlists */ }
        }
      }
    }
    if (Date.now() - s.lastEpgRefresh > 12 * 3600_000) {
      await epg.refresh(s.epgUrl);
      await ctx.saveSettings({ lastEpgRefresh: Date.now() });
    }
  }
  void refreshStale();
  void db.count(S.channels, null, null);
}
