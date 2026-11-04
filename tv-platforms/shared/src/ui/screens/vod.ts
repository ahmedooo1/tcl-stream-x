/**
 * Films, Séries and Animation (port of VodScreen.kt + SeriesPanel.kt). One layout for the three:
 * a column of subsections (start page, the groups of the user's playlist, the free film / series /
 * kids channels of the catalog, the public-domain genres of the Internet Archive, the openly licensed
 * films) and the content of the selected one. Side panels for account series, M3U shows and
 * public-domain items.
 */
import { groupsFor, lineup } from '../lineup';
import { regionStrip } from '../region';
import type { Ctx, Screen } from '../app';
import { groupLabel } from '../labels';
import { h, img, logoHtml, onActivate } from '../dom';
import { t, Key } from '../../i18n/index';
import { esc, initialsOf, normalize } from '../../core/text';
import { ContentKind, ContentKindCode, seriesShow } from '../../core/m3u';
import type { Channel, Playlist } from '../../storage/model';
import { itemFromChannel, PlayItem } from '../../player/controller';
import { VirtualList } from '../virtual';
import { details, episodeItems, isXtreamSeries } from '../details';
import type { SeriesInfo } from '../../xtream/client';
import { Archive, Genre, genresOf, OpenMedia, openFilms, PAGE_SIZE, VodFile, VodItem } from '../../vod/archive';
import openMediaJson from '../../../assets/open_media.json';

/** Same words as VodScreen.kt: a group whose title holds one of them belongs to Animation. */
export const ANIMATION_WORDS = ['anim', 'cartoon', 'kids', 'enfant', 'jeunesse', 'dessin', 'كرتون', 'أطفال', 'اطفال', 'انمي', 'أنمي'];
export const isAnimationGroup = (title: string) => { const l = title.toLowerCase(); return ANIMATION_WORDS.some((w) => l.includes(w)); };

export type Shelf = 'movies' | 'series' | 'animation';

type Section =
  | { t: 'featured' }
  | { t: 'group'; kind: ContentKindCode; group: string; count: number }
  | { t: 'channels'; category: string; count: number }
  | { t: 'archive'; genre: Genre }
  | { t: 'open' };

type Pick = { t: 'ch'; ch: Channel } | { t: 'ia'; item: VodItem };
interface Show { name: string; logo: string | null; eps: Channel[] }

const archive = new Archive();
const media = openMediaJson as unknown as OpenMedia;
const ROW_ITEMS = 24;
const MAX_SOURCE_ROWS = 40;
const SORT_MAX = 5000;
const MAX_EPISODES = 20000;

export function vodScreen(shelf: Shelf) {
  const kinds: ContentKindCode[] = shelf === 'movies' ? [ContentKind.MOVIE] : shelf === 'series' ? [ContentKind.SERIES] : [ContentKind.MOVIE, ContentKind.SERIES];
  const keep = (g: { groupTitle: string }) => shelf !== 'animation' || isAnimationGroup(g.groupTitle);
  const title = shelf === 'movies' ? 'nav_movies' : shelf === 'series' ? 'nav_series' : 'nav_animation';
  const category = shelf === 'movies' ? 'movies' : shelf === 'series' ? 'series' : 'kids';
  const favKind = shelf === 'series' ? ContentKind.SERIES : ContentKind.MOVIE;

  return (ctx: Ctx, params: Record<string, unknown>): Screen => {
    const el = h(`<div class="screen vod" style="display:flex;padding:28rem 0 0 0">
      <aside style="width:260rem;flex:none;display:flex;flex-direction:column;padding:0 10rem 0 24rem">
        <div class="t-display" style="margin:0 0 10rem 14rem">${esc(t(title))}</div>
        <div class="row fx src hidden" data-k="src" style="height:auto;padding:8rem 14rem;flex-direction:column;align-items:flex-start;gap:0;margin-bottom:6rem"></div>
        <div class="secs" data-scroll="y" data-restore style="flex:1;overflow:hidden"></div>
      </aside>
      <main style="flex:1;min-width:0;display:flex;flex-direction:column;padding:0 32rem 0 14rem">
        <div class="rstrip chips" data-scroll="x" data-restore style="flex:none"></div>
        <div class="content" data-left=".secs" style="flex:1;min-height:0;position:relative;margin-top:8rem"></div>
      </main>
      <div class="side hidden" data-scope style="position:absolute;inset-block:0;inset-inline-end:0;width:460rem;background:var(--graphite);display:flex;flex-direction:column;padding:24rem;gap:8rem;z-index:5"></div>
    </div>`);
    const src = el.querySelector<HTMLElement>('.src')!;
    const secs = el.querySelector<HTMLElement>('.secs')!;
    const content = el.querySelector<HTMLElement>('.content')!;
    const side = el.querySelector<HTMLElement>('.side')!;
    const rstrip = el.querySelector<HTMLElement>('.rstrip')!;
    let region: string | null = null;

    let playlists: Playlist[] = [];
    let p: Playlist | null = null;
    let sections: Section[] = [{ t: 'featured' }];
    let selected = 0;
    let list: VirtualList<Channel> | null = null;
    let alive = true;
    let token = 0;
    const picks = new Map<string, Pick>();
    const showMap = new Map<string, Show>();
    let sideHandler: ((k: string) => void) | null = null;
    let sideReturn: HTMLElement | null = null;

    // ---- sections ----------------------------------------------------------------------------

    const groupsOf = (pl: Playlist, r: string | null = null) => kinds.flatMap((k) => groupsFor(pl, k, r, new Set()).filter(keep).map((g) => ({ kind: k, groupTitle: g.groupTitle, count: g.count })));
    /** Countries and languages of the shelf (both sections for Animation), most represented first. */
    const regionsOf = (pl: Playlist) => {
      const m = new Map<string, number>();
      for (const k of kinds) for (const r of pl.regions?.[k] ?? []) m.set(r.code, (m.get(r.code) ?? 0) + r.count);
      return [...m].map(([code, count]) => ({ code, count })).sort((a, b) => b.count - a.count);
    };
    const countOf = (pl: Playlist) => groupsOf(pl).reduce((s, g) => s + g.count, 0);

    function sectionLabel(s: Section): string {
      switch (s.t) {
        case 'featured': return t('vod_featured');
        case 'group': return groupLabel(s.group) + (kinds.length > 1 ? ` · ${t(s.kind === ContentKind.MOVIE ? 'nav_movies' : 'nav_series')}` : '');
        case 'channels': return groupLabel(s.category);
        case 'archive': return t(`genre_${s.genre.id}` as Key);
        case 'open': return t('section_open_films');
      }
    }

    async function buildSections() {
      const hidden = await ctx.hidden();
      const groups = p ? groupsOf(p, region).filter((g) => !hidden.has(g.groupTitle)) : [];
      rstrip.innerHTML = p ? regionStrip(regionsOf(p), region) : '';
      const free = (await ctx.db.inCategory(category, 500)).filter((c) => c.kind === ContentKind.LIVE);
      sections = [{ t: 'featured' }];
      const header = (label: string) => `<div class="t-label faint" style="padding:16rem 14rem 4rem">${esc(label)}</div>`;
      const row = (i: number, count: number | null) => `<div class="row fx${i === selected ? ' selected' : ''}" data-k="sec:${i}" style="height:44rem">
        <span class="grow t-body auto ellipsis">${esc(sectionLabel(sections[i]))}</span>${count != null ? `<span class="t-mono">${count}</span>` : ''}</div>`;
      let html = row(0, null);
      if (groups.length) {
        html += header(t('section_my_playlists'));
        for (const g of groups) { sections.push({ t: 'group', kind: g.kind, group: g.groupTitle, count: g.count }); html += row(sections.length - 1, g.count); }
      }
      if (free.length) {
        html += header(t('section_free_channels'));
        sections.push({ t: 'channels', category, count: free.length }); html += row(sections.length - 1, free.length);
      }
      html += header(t('section_public_domain'));
      for (const g of genresOf(shelf)) { sections.push({ t: 'archive', genre: g }); html += row(sections.length - 1, null); }
      if (shelf !== 'series') {
        html += header(' ');
        sections.push({ t: 'open' }); html += row(sections.length - 1, null);
      }
      if (selected >= sections.length) selected = 0;
      secs.innerHTML = html;
    }

    function renderSource() {
      src.classList.toggle('hidden', playlists.length < 2);
      if (p) src.innerHTML = `<span class="t-title auto ellipsis" style="max-width:100%">${esc(p.name)}</span><span class="t-mono">${esc(t('vod_items', countOf(p)))} ⇄</span>`;
    }

    async function select(i: number) {
      selected = i;
      secs.querySelectorAll<HTMLElement>('[data-k^="sec:"]').forEach((r) => r.classList.toggle('selected', r.dataset.k === `sec:${i}`));
      await renderContent();
      ctx.focus.enter(content);
    }

    // ---- tiles -------------------------------------------------------------------------------

    const poster = (key: string, name: string, image: string | null, sub: string | null, attr = 'data-k') =>
      `<div class="card fx" ${attr}="${esc(key)}" style="background:var(--graphite);display:flex;flex-direction:column">
        <div style="position:relative;aspect-ratio:16/10;background:var(--raised);flex:none"><span class="poster-ini">${esc(initialsOf(name))}</span>${img(image)}</div>
        <div style="padding:6rem 10rem 8rem;min-height:0"><div class="t-small auto clamp2" style="color:var(--text)">${esc(name)}</div>${sub ? `<div class="t-mono">${esc(sub)}</div>` : ''}</div></div>`;

    const chPoster = (ch: Channel, attr = 'data-k') => {
      picks.set('p:' + ch.channelKey, { t: 'ch', ch });
      return poster('p:' + ch.channelKey, ch.name, ch.logo, ch.rating > 0 ? `★ ${ch.rating.toFixed(1)}` : null, attr);
    };
    const iaPoster = (v: VodItem) => {
      picks.set('p:' + v.key, { t: 'ia', item: v });
      return poster('p:' + v.key, v.title, v.poster, v.year);
    };
    const chTile = (ch: Channel) => {
      picks.set('p:' + ch.channelKey, { t: 'ch', ch });
      return `<div class="card fx" data-k="p:${esc(ch.channelKey)}" style="background:var(--graphite)">
        <div style="height:84rem;position:relative">${logoHtml(ch.logo, ch.name, '')}<span class="dot s${ch.status}" style="position:absolute;top:8rem;inset-inline-end:8rem"></span></div>
        <div class="t-small auto ellipsis" style="padding:0 12rem 10rem;color:var(--text)">${esc(ch.name)}</div></div>`;
    };

    const gridHead = (head: string, sub: string, extra = '') =>
      `<div style="padding:0 0 10rem"><div class="t-headline auto clamp2">${esc(head)}</div><div class="t-small dim auto">${esc(sub)}</div>${extra}</div>`;
    const grid = (min: number) => `display:grid;grid-template-columns:repeat(auto-fill,minmax(${min}rem,1fr));gap:14rem;padding-bottom:40rem`;

    // ---- content -----------------------------------------------------------------------------

    async function renderContent() {
      list?.destroy(); list = null;
      const my = ++token;
      const s = sections[selected];
      content.innerHTML = '';
      if (s.t === 'featured') await renderFeatured(my);
      else if (s.t === 'group') await renderGroup(s, my);
      else if (s.t === 'channels') {
        const rows = (await ctx.db.inCategory(s.category, 500)).filter((c) => c.kind === ContentKind.LIVE);
        if (my !== token) return;
        content.innerHTML = `<div data-scroll="y" data-restore style="height:100%;overflow:hidden">${gridHead(groupLabel(s.category), t('section_free_channels_note'))}
          <div style="${grid(180)}">${rows.map(chTile).join('')}</div></div>`;
      } else if (s.t === 'archive') renderArchive(s.genre, my);
      else {
        const films = openFilms(media, shelf);
        content.innerHTML = `<div data-scroll="y" data-restore style="height:100%;overflow:hidden">${gridHead(t('section_open_films'), t('vod_open_films'))}
          <div style="${grid(150)}">${films.map(iaPoster).join('')}</div></div>`;
      }
    }

    // Start page: a featured title, continue watching, favourites, one row per category.
    async function renderFeatured(my: number) {
      const box = h(`<div data-scroll="y" data-restore style="height:100%;overflow:hidden"><div class="hero"></div><div class="rows"></div><div style="height:300rem"></div></div>`);
      content.append(box);
      const heroEl = box.querySelector<HTMLElement>('.hero')!;
      const rowsEl = box.querySelector<HTMLElement>('.rows')!;
      let hero: Pick | null = null;
      const offerHero = (pk: Pick | null) => {
        if (hero || !pk || my !== token) return;
        hero = pk;
        const [name, text, image] = pk.t === 'ch'
          ? [pk.ch.name, pk.ch.groupTitle ? groupLabel(pk.ch.groupTitle) : null, pk.ch.logo]
          : [pk.item.title, pk.item.description, pk.item.poster];
        picks.set('hero', pk);
        heroEl.innerHTML = `<div style="position:relative;height:300rem;border-radius:12rem;overflow:hidden;background:var(--graphite);margin-bottom:20rem">
          ${img(image)}
          <div style="position:absolute;inset:0;background:linear-gradient(transparent, rgba(11,12,14,.55) 50%, var(--ink))"></div>
          <div style="position:absolute;inset:0;background:linear-gradient(to ${document.documentElement.dir === 'rtl' ? 'left' : 'right'}, rgba(11,12,14,.75), transparent)"></div>
          <div style="position:absolute;inset-inline-start:20rem;bottom:18rem;max-width:560rem;display:flex;flex-direction:column;gap:8rem">
            <div class="t-display auto clamp2">${esc(name)}</div>
            ${text ? `<div class="t-small auto clamp3" style="color:var(--text)">${esc(text)}</div>` : ''}
            <div class="row fx" data-k="hero" style="width:190rem;height:48rem"><span class="t-title">▶  ${esc(t('vod_play'))}</span></div>
          </div></div>`;
      };
      const shelfRow = (head: string, allKey: string | null, tiles: string) =>
        `<section style="margin-bottom:22rem"><div style="display:flex;align-items:center;gap:12rem;margin-bottom:8rem">
          <span class="t-headline grow auto ellipsis">${esc(head)}</span>
          ${allKey ? `<div class="row fx" data-k="${allKey}" style="height:38rem;padding:0 12rem;flex:none"><span class="t-label" style="color:var(--text);white-space:nowrap">${esc(t('vod_see_all'))}  ›</span></div>` : ''}</div>
          <div class="shelf" data-scroll="x" data-restore style="overflow:hidden;padding:6rem 4rem">${tiles}</div></section>`;
      const sized = (html: string) => `<div style="width:200rem;flex:none">${html}</div>`;
      const add = (html: string) => { if (my === token) rowsEl.insertAdjacentHTML('beforeend', html); };

      const hidden = await ctx.hidden();
      const [resume, favs, free] = await Promise.all([
        shelf === 'animation' ? Promise.resolve([]) : ctx.db.continueWatching(20),
        ctx.db.favorites(200),
        ctx.db.inCategory(category, 500),
      ]);
      if (my !== token) return;
      if (resume.length) {
        add(shelfRow(t('home_continue'), null, resume.map((r) => {
          picks.set('r:' + r.key, { t: 'ia', item: { key: r.key, title: r.title, year: null, poster: r.poster, description: null, license: null, directUrl: r.url } });
          return sized(poster('r:' + r.key, r.title, r.poster, `${Math.round((r.positionMs * 100) / Math.max(1, r.durationMs))} %`));
        }).join('')));
      }
      const favTitles = favs.filter((c) => c.kind === favKind && !hidden.has(c.groupTitle)).slice(0, 40);
      if (favTitles.length) add(shelfRow(t('home_favorites'), null, favTitles.map((c) => sized(chPoster(c))).join('')));

      for (const [i, s] of sections.entries()) {
        if (s.t !== 'group' || !p || i > MAX_SOURCE_ROWS) continue;
        const got = await (await lineup(ctx.db, p, s.kind, s.group, region, hidden)).load(0, ROW_ITEMS);
        if (my !== token) return;
        if (!got.length) continue;
        const withLogo = got.find((c) => c.logo);
        offerHero(withLogo ? { t: 'ch', ch: withLogo } : null);
        add(shelfRow(sectionLabel(s), `sec:${i}`, got.map((c) => sized(chPoster(c))).join('')));
      }
      const liveFree = free.filter((c) => c.kind === ContentKind.LIVE);
      if (liveFree.length) {
        const i = sections.findIndex((s) => s.t === 'channels');
        const withLogo = liveFree.find((c) => c.logo);
        offerHero(withLogo ? { t: 'ch', ch: withLogo } : null);
        add(shelfRow(groupLabel(category), i >= 0 ? `sec:${i}` : null, liveFree.slice(0, ROW_ITEMS).map((c) => sized(chTile(c))).join('')));
      }
      if (!ctx.focus.current || !el.contains(ctx.focus.current)) ctx.focus.enter(content);
      // Public-domain genres: one request each, one after the other (cached for 30 minutes).
      for (const [i, s] of sections.entries()) {
        if (s.t !== 'archive') continue;
        let got: VodItem[] = [];
        try { got = await archive.shelf(shelf, 1, null, s.genre); } catch { got = []; }
        if (my !== token || !alive) return;
        if (!got.length) continue;
        const described = got.find((v) => v.description);
        offerHero(described ? { t: 'ia', item: described } : null);
        const uniq = got.filter((v, j, a) => a.findIndex((x) => x.key === v.key) === j).slice(0, ROW_ITEMS);
        add(shelfRow(sectionLabel(s), `sec:${i}`, uniq.map((v) => sized(iaPoster(v))).join('')));
        if (!ctx.focus.current || !el.contains(ctx.focus.current)) ctx.focus.enter(content);
      }
    }

    async function renderGroup(s: Extract<Section, { t: 'group' }>, my: number) {
      if (!p) return;
      const pl = p;
      // M3U series come one row per episode: grouped by show. Account series are one row per show.
      const lu = await lineup(ctx.db, pl, s.kind, s.group, region, new Set());
      if (s.kind === ContentKind.SERIES) {
        const sample = await lu.load(0, 50);
        if (sample.some((c) => !isXtreamSeries(c.url))) { await renderShows(pl, s, my); return; }
      }
      let sort = 0;
      const sortable = s.count <= SORT_MAX;
      const box = h(`<div style="height:100%;display:flex;flex-direction:column"><div class="ghead"></div><div class="ggrid" style="flex:1;min-height:0"></div></div>`);
      content.append(box);
      const head = box.querySelector<HTMLElement>('.ghead')!;
      const gridEl = box.querySelector<HTMLElement>('.ggrid')!;
      const labels: Key[] = ['sort_source', 'sort_name', 'sort_recent', 'sort_rating'];
      const drawHead = () => {
        head.innerHTML = gridHead(groupLabel(s.group), t(s.kind === ContentKind.SERIES ? 'vod_shows' : 'vod_items', s.count),
          sortable ? `<div class="chips" data-scroll="x" style="margin-top:6rem">${labels.map((l, i) => `<div class="chip fx${i === sort ? ' selected' : ''}" data-k="sort:${i}">${esc(t(l))}</div>`).join('')}</div>` : '');
      };
      let sorted: Channel[] | null = null;
      const drawGrid = async () => {
        list?.destroy();
        if (sort > 0) {
          const all = await lu.load(0, SORT_MAX);
          const cmp: Record<number, (a: Channel, b: Channel) => number> = {
            1: (a, b) => a.nameNorm.localeCompare(b.nameNorm),
            2: (a, b) => b.addedAt - a.addedAt,
            3: (a, b) => b.rating - a.rating,
          };
          sorted = all.sort(cmp[sort]);
        } else sorted = null;
        if (my !== token) return;
        // The virtual list owns focus and clicks of its cells: no data-k on them.
        list = new VirtualList<Channel>({
          rowHeight: 168, columns: 4, gap: 14, total: lu.total, pageSize: 120,
          load: async (o, l) => (sorted ? sorted.slice(o, o + l) : lu.load(o, l)),
          render: (c) => chPoster(c, 'data-x').replace('style="background:var(--graphite);', 'style="height:100%;background:var(--graphite);'),
          onActivate: (c) => void open({ t: 'ch', ch: c }),
          onLong: (c) => void favoriteDialog(c),
        });
        gridEl.replaceChildren(list.el);
        list.renderWindow();
      };
      head.addEventListener('fx:click', async (e) => {
        const k = (e.target as HTMLElement).closest<HTMLElement>('[data-k]')?.dataset.k ?? '';
        if (!k.startsWith('sort:')) return;
        e.stopPropagation();
        sort = Number(k.substring(5));
        drawHead();
        await drawGrid();
        ctx.focus.focus(head.querySelector<HTMLElement>(`[data-k="sort:${sort}"]`));
      });
      drawHead();
      await drawGrid();
    }

    async function renderShows(pl: Playlist, s: Extract<Section, { t: 'group' }>, my: number) {
      content.innerHTML = `<div data-scroll="y" data-restore style="height:100%;overflow:hidden">${gridHead(groupLabel(s.group), t('vod_loading'))}</div>`;
      const all = await (await lineup(ctx.db, pl, s.kind, s.group, region, new Set())).load(0, MAX_EPISODES);
      if (my !== token) return;
      const shows = new Map<string, Show>();
      for (const c of all) {
        const name = seriesShow(c.name);
        const k = normalize(name);
        const sh = shows.get(k) ?? { name, logo: null, eps: [] };
        sh.logo = sh.logo ?? c.logo;
        sh.eps.push(c);
        shows.set(k, sh);
      }
      const arr = [...shows.values()];
      showMap.clear();
      content.innerHTML = `<div data-scroll="y" data-restore style="height:100%;overflow:hidden">${gridHead(groupLabel(s.group), t('vod_shows', arr.length))}
        <div style="${grid(150)}">${arr.map((sh, i) => { showMap.set(`show:${i}`, sh); return poster(`show:${i}`, sh.name, sh.logo, t('vod_episodes', sh.eps.length)); }).join('')}</div></div>`;
    }

    function renderArchive(genre: Genre, my: number) {
      let page = 1;
      let query = '';
      let loading = false;
      const results: VodItem[] = [];
      const box = h(`<div data-scroll="y" data-restore style="height:100%;overflow:hidden">
        ${gridHead(t(`genre_${genre.id}` as Key), t('vod_archive'))}
        <input class="field fx auto" type="text" placeholder="${esc(t('vod_search_hint'))}" autocomplete="off" spellcheck="false" style="max-width:420rem;margin-bottom:12rem">
        <div class="agrid" style="${grid(150)}"></div><div class="afoot" style="padding-bottom:60rem"></div></div>`);
      content.replaceChildren(box);
      const input = box.querySelector<HTMLInputElement>('input')!;
      const agrid = box.querySelector<HTMLElement>('.agrid')!;
      const foot = box.querySelector<HTMLElement>('.afoot')!;
      const load = async (reset: boolean) => {
        if (loading) return;
        loading = true;
        foot.innerHTML = `<div class="t-body dim">${esc(t('vod_loading'))}</div>`;
        try {
          const next = reset ? 1 : page + 1;
          const got = await archive.shelf(shelf, next, query || null, genre);
          if (my !== token) return;
          if (reset) { results.length = 0; agrid.innerHTML = ''; }
          const fresh = got.filter((n) => !results.some((r) => r.key === n.key));
          results.push(...fresh);
          agrid.insertAdjacentHTML('beforeend', fresh.map(iaPoster).join(''));
          page = next;
          foot.innerHTML = results.length >= page * PAGE_SIZE ? `<div class="row fx" data-k="more" style="height:52rem;justify-content:center"><span class="t-title">${esc(t('vod_load_more'))}</span></div>` : '';
          if (!ctx.focus.current || !el.contains(ctx.focus.current)) ctx.focus.enter(content);
        } catch {
          if (my === token) foot.innerHTML = `<div class="t-body" style="color:var(--caution)">${esc(t('vod_error'))}</div>`;
        } finally { loading = false; }
      };
      input.addEventListener('keydown', (e) => {
        if (e.keyCode === 13 || e.keyCode === 65376) { input.blur(); if (input.value !== query) { query = input.value; void load(true); } }
      });
      foot.addEventListener('fx:click', (e) => {
        e.stopPropagation();
        const before = results.length;
        void load(false).then(() => { const first = agrid.children[before] as HTMLElement | undefined; if (first) ctx.focus.focus(first); });
      });
      void load(true);
    }

    // ---- opening -----------------------------------------------------------------------------

    async function favoriteDialog(c: Channel) {
      const fav = await ctx.db.isFavorite(c.channelKey);
      const r = await ctx.dialog({ title: c.name, buttons: [['fav', t(fav ? 'live_remove_favorite' : 'live_add_favorite')], ['cancel', '×']] });
      if (r === 'fav') await ctx.db.toggleFavorite(c.channelKey);
    }

    function play(item: PlayItem) {
      void ctx.playback.play(item);
      ctx.push('player');
    }

    const plain = (key: string, title: string, url: string, posterUrl: string | null): PlayItem => ({
      key, title, urls: [url], isLive: false, logo: null, poster: posterUrl, userAgent: null, referrer: null, drm: false,
      tvgId: null, channel: null, group: null, upNext: [],
    });
    const fileItem = (v: VodItem, f: VodFile): PlayItem => ({
      ...plain(`${v.key}/${f.url.substring(f.url.lastIndexOf('/') + 1)}`, f.title.toLowerCase() === v.title.toLowerCase() ? v.title : `${v.title} · ${f.title}`, f.url, v.poster),
      subtitles: f.subtitles,
    });

    async function open(pk: Pick) {
      if (pk.t === 'ch') {
        const c = pk.ch;
        if (isXtreamSeries(c.url)) { await openSeries(c); return; }
        play(itemFromChannel(c, c.kind === ContentKind.LIVE ? null : c.groupTitle));
        return;
      }
      const v = pk.item;
      if (v.directUrl) {
        // A resumed title that is an entry of a playlist plays with its own settings.
        const ch = await ctx.db.byKey(v.key);
        play(ch ? itemFromChannel(ch, null) : plain(v.key, v.title, v.directUrl, v.poster));
        return;
      }
      if (v.archiveId) await openDetail(v);
    }

    function openSide(html: string) {
      sideReturn = ctx.focus.current;
      side.classList.remove('hidden');
      side.innerHTML = html;
    }
    function closeSide() {
      side.classList.add('hidden');
      side.innerHTML = '';
      sideHandler = null;
      ctx.focus.current = null;
      if (sideReturn && document.contains(sideReturn)) ctx.focus.focus(sideReturn); else ctx.focus.ensure();
    }
    const sideOpen = () => !side.classList.contains('hidden');
    const backRow = () => `<div class="row fx" data-k="close" style="height:48rem;margin-top:12rem"><span class="t-title">${esc(t('player_back'))}</span></div>`;
    const fileRow = (k: string, label: string, extra: string) => `<div class="row fx" data-k="${k}" style="height:50rem">
      <span class="t-mono tally" style="width:24rem">▶</span><span class="grow t-body auto ellipsis">${esc(label)}</span>${extra}</div>`;

    async function openDetail(v: VodItem) {
      const headHtml = `<div class="t-headline auto clamp2">${esc(v.title)}</div>
        <div class="t-mono">${esc([v.year, v.license ? '· ' + v.license.replace(/^[a-z]+:\/\//, '').slice(0, 40) : null].filter(Boolean).join(' '))}</div>
        ${v.description ? `<div class="t-small auto clamp6" style="color:var(--text)">${esc(v.description)}</div>` : ''}`;
      openSide(headHtml + `<div class="t-body dim">${esc(t('vod_loading'))}</div>`);
      let files: VodFile[] | null = null;
      try { files = await archive.files(v.archiveId!); } catch { files = null; }
      if (!sideOpen()) return;
      if (files == null || !files.length) {
        side.innerHTML = headHtml + `<div class="t-body"${files == null ? ' style="color:var(--caution)"' : ''}>${esc(t(files == null ? 'vod_error' : 'vod_no_files'))}</div>` + backRow();
        ctx.focus.enter(side);
        return;
      }
      const fl = files;
      side.innerHTML = headHtml + `<div class="t-title tally">${esc(t('vod_episodes', fl.length))}</div>
        <div data-scroll="y" data-restore style="flex:1;overflow:hidden">${fl.map((f, i) => fileRow(`f:${i}`, f.title,
          `${f.durationSec ? `<span class="t-mono">${Math.round(f.durationSec / 60)} min</span>` : ''}${f.subtitles.length ? '<span class="t-mono"> CC</span>' : ''}`)).join('')}</div>`;
      sideHandler = (k) => {
        if (!k.startsWith('f:')) return;
        const i = Number(k.substring(2));
        const items = fl.map((f) => fileItem(v, f));
        play({ ...items[i], upNext: items.slice(i + 1) });
      };
      ctx.focus.enter(side.querySelector('[data-k^="f:"]')?.parentElement ?? side);
    }

    function openShow(sh: Show) {
      const only = sh.eps.length === 1 ? sh.eps[0] : null;
      if (only && isXtreamSeries(only.url)) { void openSeries(only); return; }
      openSide(`<div class="t-headline auto clamp2">${esc(sh.name)}</div><div class="t-title tally">${esc(t('vod_episodes', sh.eps.length))}</div>
        <div data-scroll="y" data-restore style="flex:1;overflow:hidden">${sh.eps.map((e, i) => fileRow(`e:${i}`, e.name, '')).join('')}</div>`);
      sideHandler = (k) => {
        if (!k.startsWith('e:')) return;
        const i = Number(k.substring(2));
        const items = sh.eps.slice(i, i + 61).map((e) => itemFromChannel(e, e.groupTitle));
        play({ ...items[0], upNext: items.slice(1) });
      };
      ctx.focus.enter(side.querySelector('[data-k^="e:"]')?.parentElement ?? side);
    }

    async function openSeries(c: Channel) {
      openSide(`<div class="t-display auto clamp2">${esc(c.name)}</div><div class="t-body dim">${esc(t('vod_loading'))}</div>`);
      const info = await details(ctx, c) as SeriesInfo | null;
      if (!sideOpen()) return;
      if (!info || !('episodes' in info) || info.episodes.length === 0) {
        side.innerHTML = `<div class="t-display auto clamp2">${esc(c.name)}</div><div class="t-body dim">${esc(t('vod_no_files'))}</div>` + backRow();
        ctx.focus.enter(side);
        return;
      }
      const items = episodeItems(c, info);
      const seasons = info.seasons.map((x) => x.number);
      let season = seasons[0];
      const draw = () => {
        const eps = info.episodes.map((e, i) => ({ e, i })).filter((x) => x.e.season === season);
        side.innerHTML = `<div class="t-display auto clamp2">${esc(c.name)}</div>
          ${info.plot ? `<div class="t-small auto clamp3" style="color:var(--text)">${esc(info.plot)}</div>` : ''}
          <div class="chips" data-scroll="x">${seasons.map((n) => `<div class="chip fx${n === season ? ' selected' : ''}" data-k="season:${n}">${esc(t('series_season', n))}</div>`).join('')}</div>
          <div data-scroll="y" data-restore style="flex:1;overflow:hidden">${eps.map(({ e, i }) => `<div class="row fx" data-k="ep:${i}" style="height:50rem">
            <span class="t-mono tally" style="width:48rem">É${e.episode}</span><span class="grow t-body auto ellipsis">${esc(e.title)}</span>
            ${e.durationSecs ? `<span class="t-mono">${Math.round(e.durationSecs / 60)} min</span>` : ''}</div>`).join('')}</div>`;
      };
      draw();
      ctx.focus.enter(side.querySelector('[data-k^="ep:"]')?.parentElement ?? side);
      sideHandler = (k) => {
        if (k.startsWith('season:')) { season = Number(k.substring(7)); draw(); ctx.focus.focus(side.querySelector<HTMLElement>(`[data-k="season:${season}"]`)); return; }
        if (k.startsWith('ep:')) { const i = Number(k.substring(3)); play({ ...items[i], upNext: items.slice(i + 1) }); }
      };
    }

    // ---- events ------------------------------------------------------------------------------

    onActivate(rstrip, async (k) => {
      if (!k.startsWith('region:')) return;
      region = k.length > 7 ? k.substring(7) : null;
      const cur = sections[selected];
      await buildSections();
      // The selected group stays when the region still has it, else the start page (VodScreen.kt).
      const same = cur.t === 'group' ? sections.findIndex((x) => x.t === 'group' && x.group === cur.group && x.kind === cur.kind) : selected;
      selected = same >= 0 ? same : 0;
      secs.querySelectorAll<HTMLElement>('[data-k^="sec:"]').forEach((r) => r.classList.toggle('selected', r.dataset.k === `sec:${selected}`));
      await renderContent();
      ctx.focus.focus(rstrip.querySelector<HTMLElement>(`[data-k="${CSS.escape(k)}"]`));
    });
    onActivate(side, (k) => { if (k === 'close') closeSide(); else sideHandler?.(k); });
    onActivate(el.querySelector('aside')!, async (k) => {
      if (k === 'src') {
        if (!p || playlists.length < 2) return;
        p = playlists[(playlists.indexOf(p) + 1) % playlists.length];
        selected = 0;
        renderSource(); await buildSections(); await renderContent();
        return;
      }
      if (k.startsWith('sec:')) await select(Number(k.substring(4)));
    });
    onActivate(content, async (k) => {
      if (k.startsWith('sec:')) { await select(Number(k.substring(4))); return; }
      if (k.startsWith('show:')) { const sh = showMap.get(k); if (sh) openShow(sh); return; }
      if (k === 'more' || k.startsWith('sort:')) return;
      const pk = picks.get(k);
      if (pk) await open(pk);
    });
    content.addEventListener('fx:long', (e) => {
      const k = (e.target as HTMLElement).closest<HTMLElement>('[data-k]')?.dataset.k ?? '';
      const pk = picks.get(k);
      if (pk?.t === 'ch') void favoriteDialog(pk.ch);
    });

    return {
      el,
      async mount() {
        playlists = (await ctx.db.playlists()).filter((x) => countOf(x) > 0);
        p = playlists.find((x) => x.id === params['playlistId']) ?? playlists[0] ?? null;
        renderSource();
        await buildSections();
        // Opened on one category (from the search): that subsection directly.
        const wanted = params['group'] as string | undefined;
        if (wanted != null) {
          const i = sections.findIndex((x) => x.t === 'group' && x.group === wanted);
          if (i >= 0) { selected = i; secs.querySelectorAll<HTMLElement>('[data-k^="sec:"]').forEach((r) => r.classList.toggle('selected', r.dataset.k === `sec:${i}`)); }
        }
        await renderContent();
        if (!ctx.focus.current || !el.contains(ctx.focus.current)) ctx.focus.enter(content);
        const openKey = params['open'] as string | undefined;
        if (openKey) { const c = await ctx.db.byKey(openKey); if (c) void openSeries(c); }
      },
      unmount() { alive = false; token++; list?.destroy(); },
      onKey(a) {
        if (a === 'back' && sideOpen()) { closeSide(); return true; }
        return false;
      },
    };
  };
}
