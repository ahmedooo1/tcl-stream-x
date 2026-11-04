/**
 * One search over everything (SearchScreen.kt): live channels, films and series of every source,
 * accent-insensitive, results grouped by section, filter by section. Locked categories never appear.
 */
import type { Ctx, Screen } from '../app';
import { h, logoHtml, onActivate } from '../dom';
import { groupLabel } from '../labels';
import { t } from '../../i18n/index';
import { esc, normalize } from '../../core/text';
import { ContentKind, ContentKindCode } from '../../core/m3u';
import type { Channel } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';
import { isXtreamSeries } from '../details';

const PER_SECTION = 30;

export function searchScreen(ctx: Ctx, params: Record<string, unknown>): Screen {
  const el = h(`<div class="screen pad" style="display:flex;flex-direction:column">
    <div class="t-display" style="margin-bottom:12rem">${esc(t('nav_search'))}</div>
    <input class="field fx auto" data-autofocus type="text" placeholder="${esc(t('search_hint'))}" autocomplete="off" spellcheck="false">
    <div class="chips filters" style="margin:10rem 0"></div>
    <div class="res" data-scroll="y" data-restore style="flex:1;overflow:hidden"></div>
  </div>`);
  const input = el.querySelector<HTMLInputElement>('input')!;
  const filters = el.querySelector<HTMLElement>('.filters')!;
  const res = el.querySelector<HTMLElement>('.res')!;
  let kind: ContentKindCode | -1 = -1;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let token = 0;
  const found = new Map<string, Channel>();

  function renderFilters() {
    const f = (k: number, label: string) => `<div class="chip fx${kind === k ? ' selected' : ''}" data-k="f:${k}">${esc(label)}</div>`;
    filters.innerHTML = f(-1, t('search_filter_all')) + f(ContentKind.LIVE, t('search_live')) + f(ContentKind.MOVIE, t('search_movies')) + f(ContentKind.SERIES, t('search_series'));
  }

  async function run() {
    const q = normalize(input.value);
    const my = ++token;
    if (q.length < 2) { res.innerHTML = ''; return; }
    const hidden = await ctx.hidden();
    const r = await ctx.db.search(q, kind === -1 ? PER_SECTION : PER_SECTION * 4, kind, hidden);
    if (my !== token) return;
    found.clear();
    const section = (k: ContentKindCode, title: string) => {
      const list = r[k];
      if (!list.length) return '';
      return `<div class="t-headline" style="margin:14rem 0 6rem">${esc(title)}  <span class="t-numeral-s">${list.length}</span></div>`
        + list.map((c) => {
          found.set(c.channelKey, c);
          return `<div class="row fx" data-k="c:${esc(c.channelKey)}" style="height:56rem">
            <div style="width:${k === ContentKind.LIVE ? 72 : 30}rem;height:${k === ContentKind.LIVE ? 40 : 44}rem;flex:none">${logoHtml(c.logo, c.name)}</div>
            <div class="grow" style="display:flex;flex-direction:column"><span class="t-body auto ellipsis">${esc(c.name)}</span><span class="t-mono auto ellipsis">${esc(groupLabel(c.groupTitle))}</span></div>
            ${k === ContentKind.LIVE ? `<span class="dot s${c.status}"></span>` : ''}</div>`;
        }).join('');
    };
    const html = section(ContentKind.LIVE, t('search_live')) + section(ContentKind.MOVIE, t('search_movies')) + section(ContentKind.SERIES, t('search_series'));
    res.innerHTML = html || `<div class="empty t-body dim">${esc(t('search_empty'))}</div>`;
  }

  input.addEventListener('input', () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void run(), 250);
  });
  input.addEventListener('keydown', (e) => {
    if (e.keyCode === 13 || e.keyCode === 65376 /* Tizen IME done */) { input.blur(); void run(); }
  });

  onActivate(el, async (k) => {
    if (k.startsWith('f:')) { kind = Number(k.substring(2)) as ContentKindCode | -1; renderFilters(); await run(); ctx.focus.focus(filters.querySelector<HTMLElement>(`[data-k="${k}"]`)); return; }
    if (k.startsWith('c:')) {
      const c = found.get(k.substring(2));
      if (!c) return;
      if (isXtreamSeries(c.url)) { ctx.push('series', { open: c.channelKey }); return; }
      void ctx.playback.play(itemFromChannel(c, null));
      ctx.push('player');
    }
  });

  return {
    el,
    mount() {
      renderFilters();
      const q = params['q'] as string | undefined;
      if (q) { input.value = q; void run(); }
      ctx.focus.focus(input);
    },
    unmount() { if (timer) clearTimeout(timer); },
  };
}
