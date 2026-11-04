/**
 * Films and series of the user's sources, port of VodScreen.kt + SeriesPanel.kt: categories on the
 * leading side, a virtualised poster grid, and for account series a panel with summary, seasons and
 * episodes (played in order, next episode at the end of each one).
 */
import type { Ctx, Screen } from '../app';
import { h, img, onActivate } from '../dom';
import { groupLabel } from '../labels';
import { t } from '../../i18n/index';
import { esc, initialsOf } from '../../core/text';
import { ContentKind, ContentKindCode, seriesShow } from '../../core/m3u';
import type { Channel, Playlist } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';
import { VirtualList } from '../virtual';
import { details, episodeItems, isXtreamSeries } from '../details';
import type { SeriesInfo } from '../../xtream/client';

const ALL = '\u0000all';

/** Same words as VodScreen.kt: a group whose title holds one of them belongs to Animation. */
export const ANIMATION_WORDS = ['anim', 'cartoon', 'kids', 'enfant', 'jeunesse', 'dessin', 'كرتون', 'أطفال', 'اطفال', 'انمي', 'أنمي'];
export const isAnimationGroup = (title: string) => { const l = title.toLowerCase(); return ANIMATION_WORDS.some((w) => l.includes(w)); };

export type Shelf = 'movies' | 'series' | 'animation';
interface Group { kind: ContentKindCode; groupTitle: string; count: number }
const gkey = (g: Group) => `${g.kind}|${g.groupTitle}`;

export function vodScreen(shelf: Shelf) {
  const kinds: ContentKindCode[] = shelf === 'movies' ? [ContentKind.MOVIE] : shelf === 'series' ? [ContentKind.SERIES] : [ContentKind.MOVIE, ContentKind.SERIES];
  const keep = (g: { groupTitle: string }) => shelf !== 'animation' || isAnimationGroup(g.groupTitle);
  const title = shelf === 'movies' ? 'nav_movies' : shelf === 'series' ? 'nav_series' : 'nav_animation';
  return (ctx: Ctx, params: Record<string, unknown>): Screen => {
    const el = h(`<div class="screen vod" style="display:flex;padding:24rem 0 0 0">
      <aside style="width:250rem;flex:none;display:flex;flex-direction:column;padding:0 12rem 0 24rem">
        <div class="t-display" style="margin:0 0 12rem 14rem">${esc(t(title))}</div>
        <div class="row fx src" data-k="src" style="height:auto;padding:8rem 14rem;flex-direction:column;align-items:flex-start;gap:0"></div>
        <div class="glist" data-scroll="y" data-restore style="flex:1;overflow:hidden;margin-top:8rem"></div>
      </aside>
      <main style="flex:1;min-width:0;display:flex;flex-direction:column;padding:0 40rem 0 12rem">
        <div class="vhead" style="display:flex;align-items:baseline;gap:12rem;height:44rem"></div>
        <div class="grid" style="flex:1;min-height:0"></div>
      </main>
      <div class="series-panel hidden" data-scope style="position:absolute;inset-block:0;inset-inline-end:0;width:62%;background:rgba(19,21,24,.97);display:flex;flex-direction:column;padding:28rem 36rem"></div>
    </div>`);
    const src = el.querySelector<HTMLElement>('.src')!;
    const glist = el.querySelector<HTMLElement>('.glist')!;
    const head = el.querySelector<HTMLElement>('.vhead')!;
    const grid = el.querySelector<HTMLElement>('.grid')!;
    const sp = el.querySelector<HTMLElement>('.series-panel')!;
    let playlists: Playlist[] = [];
    let p: Playlist | null = null;
    let group = ALL;
    let list: VirtualList<Channel> | null = null;
    let panelOpen = false;
    let returnFocus: HTMLElement | null = null;

    function renderSource() {
      src.classList.toggle('hidden', playlists.length < 2);
      if (p) src.innerHTML = `<span class="t-title auto ellipsis" style="max-width:100%">${esc(p.name)}</span><span class="t-mono">${esc(t('vod_items', countOf(p)))} ⇄</span>`;
    }
    const groupsOf = (pl: Playlist): Group[] => kinds.flatMap((k) => (pl.groups[k] ?? []).filter(keep).map((g) => ({ kind: k, groupTitle: g.groupTitle, count: g.count })));
    const countOf = (pl: Playlist) => groupsOf(pl).reduce((s, g) => s + g.count, 0);

    async function renderGroups() {
      const hidden = await ctx.hidden();
      if (!p) { glist.innerHTML = ''; return; }
      const groups = groupsOf(p).filter((g) => !hidden.has(g.groupTitle));
      const row = (k: string, label: string, n: number) => `<div class="row fx${k === group ? ' selected' : ''}" data-k="${esc(k)}" style="height:44rem"><span class="grow t-body auto ellipsis">${esc(label)}</span><span class="t-mono">${n}</span></div>`;
      const suffix = (g: Group) => (kinds.length > 1 ? ` · ${t(g.kind === ContentKind.MOVIE ? 'nav_movies' : 'nav_series')}` : '');
      glist.innerHTML = row(ALL, t('group_all'), groups.reduce((s, g) => s + g.count, 0)) + groups.map((g) => row(gkey(g), groupLabel(g.groupTitle) + suffix(g), g.count)).join('');
    }

    async function renderGrid() {
      list?.destroy();
      grid.innerHTML = '';
      if (!p) {
        head.innerHTML = '';
        grid.innerHTML = `<div class="empty"><div class="t-headline">${esc(t('home_empty_title'))}</div><div class="t-body dim">${esc(t('vod_no_playlist_hint'))}</div></div>`;
        return;
      }
      const pl = p;
      const hidden = await ctx.hidden();
      const visibleGroups = groupsOf(pl).filter((x) => !hidden.has(x.groupTitle));
      const one = group === ALL ? null : visibleGroups.find((x) => gkey(x) === group) ?? null;
      // A whole shelf of one kind with nothing hidden reads straight from the index; anything else
      // walks the visible groups in order.
      const direct = one == null && kinds.length === 1 && hidden.size === 0 && shelf !== 'animation';
      const total = one ? await ctx.db.countOf(pl, one.kind, one.groupTitle) : direct ? await ctx.db.countOf(pl, kinds[0], null) : visibleGroups.reduce((s, x) => s + x.count, 0);
      head.innerHTML = `<span class="t-headline auto ellipsis">${esc(one ? groupLabel(one.groupTitle) : t('group_all'))}</span><span class="t-mono">${total}</span>`;
      if (total === 0) { grid.innerHTML = `<div class="empty t-body dim">${esc(t('vod_group_empty'))}</div>`; return; }
      list = new VirtualList<Channel>({
        rowHeight: 230, columns: 6, gap: 14, total, pageSize: 120,
        load: async (o, l) => {
          if (one) return ctx.db.channelsOf(pl, one.kind, one.groupTitle, o, l);
          if (direct) return ctx.db.channelsOf(pl, kinds[0], null, o, l);
          const out: Channel[] = [];
          let skip = o;
          for (const vg of visibleGroups) {
            if (skip >= vg.count) { skip -= vg.count; continue; }
            out.push(...await ctx.db.channelsOf(pl, vg.kind, vg.groupTitle, skip, l - out.length));
            skip = 0;
            if (out.length >= l) break;
          }
          return out;
        },
        render: (c) => `<div style="display:flex;flex-direction:column;gap:6rem;height:100%">
          <div class="card fx" style="flex:1;background:var(--raised)"><span class="poster-ini">${esc(initialsOf(c.name))}</span>${img(c.logo)}
            ${c.rating > 0 ? `<span class="t-mono" style="position:absolute;top:6rem;inset-inline-start:6rem;color:var(--text);background:rgba(11,12,14,.7);border-radius:6rem;padding:2rem 6rem">★ ${c.rating.toFixed(1)}</span>` : ''}</div>
          <div class="t-small auto ellipsis" style="color:var(--text)">${esc(c.name)}</div></div>`,
        onActivate: (c) => void open(c),
      });
      grid.append(list.el);
      list.renderWindow();
    }

    async function open(c: Channel) {
      if (isXtreamSeries(c.url)) { await openSeries(c); return; }
      if (c.kind === ContentKind.SERIES) {
        // An M3U episode: the whole show plays in order from it.
        const show = seriesShow(c.name);
        const pl = p!;
        const siblings = (await ctx.db.channelsOf(pl, c.kind, c.groupTitle, 0, 2000)).filter((x) => seriesShow(x.name) === show);
        const i = siblings.findIndex((x) => x.channelKey === c.channelKey);
        const items = siblings.map((x) => itemFromChannel(x, null));
        void ctx.playback.play({ ...items[i], upNext: items.slice(i + 1) });
      } else {
        void ctx.playback.play(itemFromChannel(c, null));
      }
      ctx.push('player');
    }

    async function openSeries(c: Channel) {
      returnFocus = ctx.focus.current;
      panelOpen = true;
      sp.classList.remove('hidden');
      sp.innerHTML = `<div class="t-display auto clamp2">${esc(c.name)}</div><div class="t-body dim">${esc(t('vod_loading'))}</div>`;
      const info = await details(ctx, c) as SeriesInfo | null;
      if (!panelOpen) return;
      if (!info || !('episodes' in info) || info.episodes.length === 0) {
        sp.innerHTML = `<div class="t-display auto clamp2">${esc(c.name)}</div><div class="t-body dim">${esc(t('vod_no_files'))}</div><div class="row fx" data-k="close" style="height:48rem;margin-top:16rem"><span class="t-title">${esc(t('player_back'))}</span></div>`;
        ctx.focus.enter(sp);
        return;
      }
      const items = episodeItems(c, info);
      const seasons = info.seasons.map((s) => s.number);
      let season = seasons[0];
      const draw = () => {
        const eps = info.episodes.map((e, i) => ({ e, i })).filter((x) => x.e.season === season);
        sp.innerHTML = `<div class="t-display auto clamp2">${esc(c.name)}</div>
          ${info.plot ? `<div class="t-small auto clamp3" style="color:var(--text);margin:8rem 0">${esc(info.plot)}</div>` : ''}
          <div class="chips" data-scroll="x" style="margin:8rem 0">${seasons.map((n) => `<div class="chip fx${n === season ? ' selected' : ''}" data-k="season:${n}">${esc(t('series_season', n))}</div>`).join('')}</div>
          <div data-scroll="y" data-restore style="flex:1;overflow:hidden">${eps.map(({ e, i }) => `<div class="row fx" data-k="ep:${i}" style="height:50rem">
            <span class="t-mono tally" style="width:48rem">É${e.episode}</span><span class="grow t-body auto ellipsis">${esc(e.title)}</span>
            ${e.durationSecs ? `<span class="t-mono">${Math.round(e.durationSecs / 60)} min</span>` : ''}</div>`).join('')}</div>`;
      };
      draw();
      sp.onclick = null;
      ctx.focus.enter(sp.querySelector('[data-k^="ep:"]')?.parentElement ?? sp);
      sp.dataset.series = '1';
      seriesHandler = (k: string) => {
        if (k.startsWith('season:')) { season = Number(k.substring(7)); draw(); ctx.focus.focus(sp.querySelector<HTMLElement>(`[data-k="season:${season}"]`)); return; }
        if (k.startsWith('ep:')) {
          const i = Number(k.substring(3));
          void ctx.playback.play({ ...items[i], upNext: items.slice(i + 1) });
          ctx.push('player');
        }
      };
    }
    let seriesHandler: ((k: string) => void) | null = null;
    onActivate(sp, (k) => { if (k === 'close') closeSeries(); else seriesHandler?.(k); });

    function closeSeries() {
      panelOpen = false;
      sp.classList.add('hidden');
      sp.innerHTML = '';
      ctx.focus.current = null;
      if (returnFocus && document.contains(returnFocus)) ctx.focus.focus(returnFocus); else ctx.focus.ensure();
    }

    onActivate(el.querySelector('aside')!, async (k) => {
      if (k === 'src') {
        if (!p || playlists.length < 2) return;
        p = playlists[(playlists.indexOf(p) + 1) % playlists.length];
        group = ALL;
        renderSource(); await renderGroups(); await renderGrid();
        return;
      }
      group = k;
      glist.querySelectorAll('.row').forEach((r) => r.classList.toggle('selected', (r as HTMLElement).dataset.k === k));
      await renderGrid();
      ctx.focus.enter(grid.querySelector('.vlist') ?? grid);
    });

    return {
      el,
      async mount() {
        playlists = (await ctx.db.playlists()).filter((x) => countOf(x) > 0);
        p = playlists[0] ?? null;
        renderSource();
        await renderGroups();
        await renderGrid();
        ctx.focus.enter(grid.querySelector('.vlist') ?? glist);
        const openKey = params['open'] as string | undefined;
        if (openKey) { const c = await ctx.db.byKey(openKey); if (c) void openSeries(c); }
      },
      unmount() { list?.destroy(); },
      onKey(a) {
        if (a === 'back' && panelOpen) { closeSeries(); return true; }
        return false;
      },
    };
  };
}
