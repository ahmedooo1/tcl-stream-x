/**
 * One search over everything (SearchScreen.kt): live channels, films and series of every source,
 * accent-insensitive, with the Android filters (section, source, online at last check, country /
 * language) and the matching categories. Locked categories never appear.
 */
import type { Ctx, Screen } from '../app';
import { groupLabel } from '../labels';
import { h, logoHtml, onActivate } from '../dom';
import { t } from '../../i18n/index';
import { esc, normalize } from '../../core/text';
import { ContentKind, ContentKindCode } from '../../core/m3u';
import type { Channel, Playlist } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';
import { isXtreamSeries } from '../details';
import { regionLabel, regionStrip } from '../region';

const PER_SECTION = 30;
const MAX_GROUPS = 40;

export function searchScreen(ctx: Ctx, params: Record<string, unknown>): Screen {
  const el = h(`<div class="screen pad" style="display:flex;flex-direction:column">
    <div class="t-display" style="margin-bottom:12rem">${esc(t('nav_search'))}</div>
    <input class="field fx auto" data-autofocus type="text" placeholder="${esc(t('search_hint'))}" autocomplete="off" spellcheck="false">
    <div class="chips filters" data-scroll="x" style="margin:10rem 0 4rem"></div>
    <div class="chips rstrip" data-scroll="x" data-restore style="margin-bottom:4rem"></div>
    <div class="res" data-scroll="y" data-restore style="flex:1;overflow:hidden"></div>
  </div>`);
  const input = el.querySelector<HTMLInputElement>('input')!;
  const filters = el.querySelector<HTMLElement>('.filters')!;
  const rstrip = el.querySelector<HTMLElement>('.rstrip')!;
  const res = el.querySelector<HTMLElement>('.res')!;
  let kind: ContentKindCode | -1 = -1;
  let source = 0;
  let online = false;
  let region: string | null = null;
  let playlists: Playlist[] = [];
  let timer: ReturnType<typeof setTimeout> | null = null;
  let token = 0;
  const found = new Map<string, Channel>();
  const groupHits = new Map<string, { playlistId: number; kind: ContentKindCode; groupTitle: string }>();

  function renderFilters() {
    const f = (k: string, label: string, on: boolean) => `<div class="chip fx${on ? ' selected' : ''}" data-k="${esc(k)}">${esc(label)}</div>`;
    const src = playlists.find((p) => p.id === source);
    filters.innerHTML = f('f:-1', t('search_filter_all'), kind === -1) + f(`f:${ContentKind.LIVE}`, t('search_live'), kind === ContentKind.LIVE)
      + f(`f:${ContentKind.MOVIE}`, t('search_movies'), kind === ContentKind.MOVIE) + f(`f:${ContentKind.SERIES}`, t('search_series'), kind === ContentKind.SERIES)
      + f('online', '● ' + t('search_filter_online'), online)
      + (playlists.length > 1 ? f('source', `${t('search_filter_sources')} : ${src ? src.name : t('search_filter_all')}  ⇄`, source !== 0) : '');
    // Countries and languages of the chosen source(s) and section(s), most represented first.
    const m = new Map<string, number>();
    for (const p of playlists) {
      if (source && p.id !== source) continue;
      for (const k of kind === -1 ? [0, 1, 2] : [kind]) for (const r of p.regions?.[k] ?? []) m.set(r.code, (m.get(r.code) ?? 0) + r.count);
    }
    rstrip.innerHTML = regionStrip([...m].map(([code, count]) => ({ code, count })).sort((a, b) => b.count - a.count).slice(0, 80), region);
  }

  async function run() {
    const q = normalize(input.value);
    const my = ++token;
    if (q.length < 2) { res.innerHTML = ''; return; }
    const hidden = await ctx.hidden();
    const r = await ctx.db.search(q, kind === -1 ? PER_SECTION : PER_SECTION * 4, kind, hidden, { playlistId: source || undefined, region, online });
    if (my !== token) return;
    found.clear();
    groupHits.clear();
    // Categories whose name matches, in the chosen source and section.
    const groups: { playlistId: number; kind: ContentKindCode; groupTitle: string; count: number }[] = [];
    for (const p of playlists) {
      if (source && p.id !== source) continue;
      for (const k of (kind === -1 ? [0, 1, 2] : [kind]) as ContentKindCode[]) {
        for (const g of p.groups[k] ?? []) {
          if (groups.length >= MAX_GROUPS) break;
          if (!hidden.has(g.groupTitle) && normalize(groupLabel(g.groupTitle)).includes(q)) groups.push({ playlistId: p.id, kind: k, groupTitle: g.groupTitle, count: g.count });
        }
      }
    }
    const sectionName = (k: number) => t(k === ContentKind.MOVIE ? 'search_movies' : k === ContentKind.SERIES ? 'search_series' : 'search_live');
    let html = '';
    if (groups.length) {
      html += `<div class="t-headline" style="margin:14rem 0 6rem">${esc(t('search_categories'))}  <span class="t-numeral-s">${groups.length}</span></div>
        <div class="chips" data-scroll="x" data-restore>${groups.map((g, i) => { groupHits.set(`g:${i}`, g); return `<div class="chip fx" data-k="g:${i}">${esc(`${groupLabel(g.groupTitle)} · ${sectionName(g.kind)} · ${g.count}`)}</div>`; }).join('')}</div>`;
    }
    const section = (k: ContentKindCode) => {
      const list = r[k];
      if (!list.length) return '';
      return `<div class="t-headline" style="margin:14rem 0 6rem">${esc(sectionName(k))}  <span class="t-numeral-s">${list.length}</span></div>`
        + list.map((c) => {
          found.set(c.channelKey, c);
          const meta = [groupLabel(c.groupTitle), c.country ? regionLabel(c.country) : null].filter(Boolean).join(' · ');
          return `<div class="row fx" data-k="c:${esc(c.channelKey)}" style="height:56rem">
            <div style="width:${k === ContentKind.LIVE ? 72 : 30}rem;height:${k === ContentKind.LIVE ? 40 : 44}rem;flex:none">${logoHtml(c.logo, c.name)}</div>
            <div class="grow" style="display:flex;flex-direction:column;min-width:0"><span class="t-body auto ellipsis">${esc(c.name)}</span><span class="t-mono auto ellipsis">${esc(meta)}</span></div>
            ${k === ContentKind.LIVE ? `<span class="dot s${c.status}"></span>` : ''}</div>`;
        }).join('');
    };
    html += section(ContentKind.LIVE) + section(ContentKind.MOVIE) + section(ContentKind.SERIES);
    res.innerHTML = html || `<div class="empty t-body dim">${esc(t('search_empty'))}</div>`;
  }

  input.addEventListener('input', () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void run(), 250);
  });
  input.addEventListener('keydown', (e) => {
    if (e.keyCode === 13 || e.keyCode === 65376 /* Tizen IME done */) { input.blur(); void run(); }
  });

  const refocus = (k: string, box: HTMLElement) => ctx.focus.focus(box.querySelector<HTMLElement>(`[data-k="${CSS.escape(k)}"]`));
  onActivate(filters, async (k) => {
    if (k.startsWith('f:')) { kind = Number(k.substring(2)) as ContentKindCode | -1; region = null; }
    else if (k === 'online') online = !online;
    else if (k === 'source') {
      const ids = [0, ...playlists.map((p) => p.id)];
      source = ids[(ids.indexOf(source) + 1) % ids.length];
      region = null;
    }
    renderFilters(); await run(); refocus(k, filters);
  });
  onActivate(rstrip, async (k) => {
    if (!k.startsWith('region:')) return;
    region = k.length > 7 ? k.substring(7) : null;
    renderFilters(); await run(); refocus(k, rstrip);
  });
  onActivate(res, (k) => {
    if (k.startsWith('g:')) {
      const g = groupHits.get(k);
      if (!g) return;
      if (g.kind === ContentKind.LIVE) ctx.go('live', { playlistId: g.playlistId, group: g.groupTitle });
      else ctx.go(g.kind === ContentKind.MOVIE ? 'movies' : 'series', { playlistId: g.playlistId, group: g.groupTitle });
      return;
    }
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
    async mount() {
      playlists = await ctx.db.playlists();
      renderFilters();
      const q = params['q'] as string | undefined;
      if (q) { input.value = q; void run(); }
      ctx.focus.focus(input);
    },
    unmount() { if (timer) clearTimeout(timer); },
  };
}
