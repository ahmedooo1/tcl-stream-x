/**
 * Home, port of HomeScreen.kt: a stage on top (focused title in large, its details, its picture
 * behind) and rows below: continue watching, sections, newest films and series, best rated,
 * recently watched, favorites, news and free sport. Up from the first row goes to the search bar.
 */
import { showUpdate, updateBanner } from '../updateinfo';
import type { Ctx, Screen } from '../app';
import { SECTIONS } from '../app';
import { h, img, logoHtml, onActivate, onFocusIn } from '../dom';
import { icon } from '../icons';
import { groupLabel } from '../labels';
import { t } from '../../i18n/index';
import { esc, initialsOf, isHttpUrl } from '../../core/text';
import { ContentKind } from '../../core/m3u';
import type { Channel, VodProgress } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';
import { details, isXtreamSeries } from '../details';

interface Spot { title: string; meta: string; image: string | null; accent: string; channel?: Channel }

/** Each section has its own light (HomeScreen.kt accentOf). Hex so an alpha suffix works on older engines. */
const ACCENT: Record<string, string> = {
  live: '#ff4d2e', movies: '#ff963c', series: '#9c8cff', sports: '#5bd98f', animation: '#3fc8e4',
  local: '#f2b33d', search: '#ff6f91', playlists: '#9ba1a8', settings: '#9ba1a8',
};

export function homeScreen(ctx: Ctx): Screen {
  const el = h(`<div class="screen home">
    <div class="backdrop" style="position:absolute;inset:0 0 auto 0;height:390rem;overflow:hidden"></div>
    <div style="position:absolute;inset:0;display:flex;flex-direction:column">
      <div class="header" style="display:flex;align-items:center;gap:10rem;padding:20rem 40rem 0 36rem">
        <img src="${new URL('../../../assets/img/logo_mark.png', import.meta.url).href}" style="width:38rem;height:38rem" alt="">
        <span class="t-title">StreamX</span>
        <span style="width:28rem"></span>
        <div class="row fx search-bar" data-k="search" style="width:300rem;height:46rem;border-radius:23rem;background:var(--raised)" data-down=".home .rows">
          ${icon('Search', 20)}<span class="t-title dim">${esc(t('home_search'))}</span>
        </div>
        <span style="flex:1"></span>
        <span class="upd"></span>
        <span class="t-numeral clock"></span>
      </div>
      <div class="stage" style="height:190rem;padding:0 40rem 0 36rem;display:flex;flex-direction:column;justify-content:flex-end"></div>
      <div class="rows" data-scroll="y" data-top data-margin="6" data-restore data-up=".home .search-bar" style="flex:1;overflow:hidden;padding-top:6rem"></div>
    </div>
  </div>`);
  const stage = el.querySelector<HTMLElement>('.stage')!;
  const backdrop = el.querySelector<HTMLElement>('.backdrop')!;
  const rows = el.querySelector<HTMLElement>('.rows')!;
  const clockEl = el.querySelector<HTMLElement>('.clock')!;
  const upd = el.querySelector<HTMLElement>('.upd')!;
  // Update banner on the start page, like the Android app (UpdateRow).
  const unsubUpdate = ctx.update.subscribe((r) => { upd.innerHTML = r ? updateBanner(r) : ''; });
  onActivate(upd, (k) => { const r = ctx.update.value; if (k === 'update' && r) showUpdate(ctx, r); });
  const spots = new Map<string, Spot>();
  let spotTimer: ReturnType<typeof setTimeout> | null = null;
  let shownKey = '';
  let clockTimer: ReturnType<typeof setInterval> | null = null;

  const tick = () => { clockEl.textContent = new Date().toLocaleTimeString(document.documentElement.lang === 'ar' ? 'ar' : 'fr-FR', { hour: '2-digit', minute: '2-digit' }); };

  function showSpot(s: Spot | undefined, key: string) {
    if (!s) return;
    shownKey = key;
    stage.innerHTML = `<div style="max-width:560rem;display:flex;flex-direction:column;gap:6rem">
      <div class="t-display auto ellipsis s-title">${esc(s.title)}</div>
      ${s.meta ? `<div class="t-label auto ellipsis" style="color:${s.accent}">${esc(s.meta)}</div>` : ''}
      <div class="t-small auto clamp2 s-plot" style="color:var(--text)"></div></div>`;
    backdrop.innerHTML = isHttpUrl(s.image)
      ? `<img src="${esc(s.image)}" alt="" data-drop style="position:absolute;inset-block:0;inset-inline-end:0;width:62%;height:100%;object-fit:cover;opacity:.55">
         <div style="position:absolute;inset:0;background:linear-gradient(to right, var(--ink) 38%, transparent 75%), linear-gradient(to top, var(--ink), transparent 50%)"></div>`
      : '';
    if (document.documentElement.dir === 'rtl') {
      const g = backdrop.querySelector<HTMLElement>('div');
      if (g) g.style.background = 'linear-gradient(to left, var(--ink) 38%, transparent 75%), linear-gradient(to top, var(--ink), transparent 50%)';
    }
    // Summary of an account title, fetched after a short dwell (the title keeps one line when it shows).
    if (spotTimer) clearTimeout(spotTimer);
    if (s.channel && (s.channel.kind === ContentKind.MOVIE || s.channel.kind === ContentKind.SERIES)) {
      spotTimer = setTimeout(() => {
        void details(ctx, s.channel!).then((d) => {
          if (shownKey !== key || !d?.plot) return;
          stage.querySelector('.s-plot')!.textContent = d.plot;
          stage.querySelector('.s-title')!.classList.add('ellipsis');
          if (isHttpUrl(d.backdrop)) {
            const im = backdrop.querySelector('img');
            if (im) im.src = d.backdrop;
          }
        });
      }, 500);
    }
  }

  function channelSpot(ch: Channel): Spot {
    const film = ch.kind === ContentKind.MOVIE, series = ch.kind === ContentKind.SERIES;
    if (film || series) {
      const meta = [t(film ? 'nav_movies' : 'nav_series'), ch.rating > 0 ? `★ ${ch.rating.toFixed(1)}` : null,
        ch.addedAt > 0 ? new Date(ch.addedAt * 1000).toLocaleDateString(document.documentElement.lang === 'ar' ? 'ar' : 'fr-FR', { day: 'numeric', month: 'short', year: 'numeric' }) : null,
        ch.groupTitle ? groupLabel(ch.groupTitle) : null].filter(Boolean).join('  ·  ');
      return { title: ch.name, meta, image: ch.logo, accent: film ? 'var(--ember)' : 'var(--violet)', channel: ch };
    }
    return { title: ch.name, meta: [t('home_live_tag'), ch.groupTitle ? groupLabel(ch.groupTitle) : null].filter(Boolean).join('  ·  '), image: null, accent: 'var(--tally)', channel: ch };
  }

  const shelf = (title: string, cards: string) =>
    `<section style="margin-bottom:18rem"><div class="t-title shelf-title">${esc(title)}</div><div class="shelf" data-scroll="x" data-restore style="overflow:hidden">${cards}</div></section>`;

  const posterCard = (ch: Channel) => {
    spots.set('c:' + ch.channelKey, channelSpot(ch));
    return `<div style="width:118rem;flex:none;display:flex;flex-direction:column;gap:6rem">
      <div class="card fx" data-k="c:${esc(ch.channelKey)}" style="aspect-ratio:2/3;background:var(--raised)">
        <span class="poster-ini">${esc(initialsOf(ch.name))}</span>${img(ch.logo)}
        ${ch.rating > 0 ? `<span class="t-mono" style="position:absolute;top:6rem;inset-inline-start:6rem;color:var(--text);background:rgba(11,12,14,.7);border-radius:6rem;padding:2rem 6rem">★ ${ch.rating.toFixed(1)}</span>` : ''}
      </div><div class="t-small auto ellipsis" style="color:var(--text)">${esc(ch.name)}</div></div>`;
  };

  const channelTile = (ch: Channel) => {
    spots.set('c:' + ch.channelKey, channelSpot(ch));
    return `<div class="card fx" data-k="c:${esc(ch.channelKey)}" style="width:186rem;flex:none">
      <div style="height:92rem;position:relative">${logoHtml(ch.logo, ch.name, '')}<span class="dot s${ch.status}" style="position:absolute;top:8rem;inset-inline-end:8rem"></span></div>
      <div class="t-small auto ellipsis" style="padding:0 12rem 10rem;color:var(--text)">${esc(ch.name)}</div></div>`;
  };

  const resumeCard = (r: VodProgress) => {
    spots.set('r:' + r.key, { title: r.title, meta: `${t('home_resume')}  ·  ${Math.round((r.positionMs * 100) / Math.max(1, r.durationMs))} %`, image: r.poster, accent: 'var(--tally)' });
    return `<div style="width:236rem;flex:none;display:flex;flex-direction:column;gap:6rem">
      <div class="card fx" data-k="r:${esc(r.key)}" style="aspect-ratio:16/9;background:var(--raised)">
        <span class="poster-ini">${esc(initialsOf(r.title))}</span>${img(r.poster)}
        <div class="progress" style="position:absolute;inset:auto 0 0 0"><i style="width:${(r.positionMs * 100) / Math.max(1, r.durationMs)}%"></i></div>
      </div><div class="t-small auto ellipsis" style="color:var(--text)">${esc(r.title)}</div></div>`;
  };

  const liveResumeCard = (ch: Channel) => {
    spots.set('c:' + ch.channelKey, channelSpot(ch));
    return `<div style="width:236rem;flex:none;display:flex;flex-direction:column;gap:6rem">
      <div class="card fx" data-k="c:${esc(ch.channelKey)}" style="aspect-ratio:16/9;background:linear-gradient(135deg, var(--tally-deep), var(--raised))">
        <span class="live-badge" style="position:absolute;top:8rem;inset-inline-start:8rem">${esc(t('home_live_tag'))}</span>
        <div style="position:absolute;inset:28rem 60rem 16rem">${logoHtml(ch.logo, ch.name, '')}</div>
      </div><div class="t-small auto ellipsis" style="color:var(--text)">${esc(ch.name)}</div></div>`;
  };

  async function load() {
    const db = ctx.db;
    const hidden = await ctx.hidden();
    const visible = (l: Channel[]) => l.filter((c) => !hidden.has(c.groupTitle)).filter((c, i, a) => a.findIndex((x) => x.channelKey === c.channelKey) === i);
    const [resume, newMovies, newSeries, topMovies, recent, favorites, news, sports, liveCount, movieCount, seriesCount, playlists, lastKey] = await Promise.all([
      db.continueWatching(12), db.newest(ContentKind.MOVIE, 30), db.newest(ContentKind.SERIES, 30), db.topRated(ContentKind.MOVIE, 30),
      db.recent(12), db.favorites(30), db.inCategory('news', 30), db.inCategory('sports', 30),
      db.countKind(ContentKind.LIVE), db.countKind(ContentKind.MOVIE), db.countKind(ContentKind.SERIES), db.playlists(),
      db.setting<string>('lastChannel', ''),
    ]);
    const last = lastKey ? await db.byKey(lastKey) : undefined;
    const subtitle: Record<string, string> = {
      live: t('home_tile_channels', liveCount),
      movies: movieCount > 0 ? t('home_tile_titles', movieCount) : t('home_tile_public'),
      series: seriesCount > 0 ? t('home_tile_titles', seriesCount) : t('home_tile_public'),
      sports: t('home_tile_matches'), animation: t('home_tile_public'), local: t('tv_blocked_local'),
      search: t('home_tile_search'), playlists: t('home_tile_playlists', playlists.length), settings: t('home_tile_settings'),
    };
    const tiles = SECTIONS.filter((s) => s.route !== 'home').map((s) => {
      spots.set('s:' + s.route, { title: t(s.label), meta: subtitle[s.route] ?? '', image: null, accent: ACCENT[s.route] ?? '#9ba1a8' });
      return `<div class="card fx" data-k="s:${s.route}" style="width:186rem;height:92rem;flex:none;padding:14rem;display:flex;flex-direction:column;justify-content:flex-end;gap:2rem;
        background:linear-gradient(135deg, ${ACCENT[s.route]}47, var(--graphite) 70%)">
        <span style="position:absolute;top:12rem;inset-inline-start:14rem;color:${ACCENT[s.route]}">${icon(s.icon, 22)}</span>
        <span class="t-title ellipsis">${esc(t(s.label))}</span><span class="t-mono ellipsis">${esc(subtitle[s.route] ?? '')}</span></div>`;
    }).join('');

    let html = '';
    if (last || resume.length) html += shelf(t('home_continue'), (last && !hidden.has(last.groupTitle) ? liveResumeCard(last) : '') + resume.map(resumeCard).join(''));
    html += shelf(t('home_sections'), tiles);
    if (newMovies.length) html += shelf(t('home_new_movies'), visible(newMovies).map(posterCard).join(''));
    if (newSeries.length) html += shelf(t('home_new_series'), visible(newSeries).map(posterCard).join(''));
    if (topMovies.length) html += shelf(t('home_top_rated'), visible(topMovies).map(posterCard).join(''));
    if (recent.length) html += shelf(t('home_recent'), visible(recent).map(channelTile).join(''));
    if (favorites.length) html += shelf(t('home_favorites'), visible(favorites).map(channelTile).join(''));
    if (news.length) html += shelf(t('home_news'), visible(news).map(channelTile).join(''));
    if (sports.length) html += shelf(t('home_free_sports'), visible(sports).map(channelTile).join(''));
    html += '<div style="height:400rem"></div>';
    const had = rows.childElementCount > 0;
    rows.innerHTML = html;
    const featured = resume[0] ? 'r:' + resume[0].key : newMovies[0] ? 'c:' + newMovies[0].channelKey : last ? 'c:' + last.channelKey : 's:live';
    showSpot(spots.get(featured), featured);
    if (!had || !ctx.focus.current || !el.contains(ctx.focus.current)) ctx.focus.enter(rows);
  }

  onFocusIn(rows, (k) => showSpot(spots.get(k), k));
  onActivate(el, async (k) => {
    if (k === 'search') { ctx.go('search'); return; }
    if (k.startsWith('s:')) { ctx.go(k.substring(2) as never); return; }
    if (k.startsWith('r:')) {
      const r = await ctx.db.progress(k.substring(2));
      if (!r) return;
      const ch = await ctx.db.byKey(r.key);
      const item = ch ? itemFromChannel(ch, null) : { key: r.key, title: r.title, urls: [r.url], isLive: false, logo: null, poster: r.poster, userAgent: null, referrer: null, drm: false, tvgId: null, channel: null, group: null, upNext: [] };
      void ctx.playback.play(item);
      ctx.push('player');
      return;
    }
    if (k.startsWith('c:')) {
      const ch = await ctx.db.byKey(k.substring(2));
      if (!ch) return;
      if (isXtreamSeries(ch.url)) { ctx.push('series', { open: ch.channelKey }); return; }
      void ctx.playback.play(itemFromChannel(ch, null));
      ctx.push('player');
    }
  });

  return {
    el,
    mount() { tick(); clockTimer = setInterval(tick, 15_000); void load(); },
    unmount() { if (clockTimer) clearInterval(clockTimer); if (spotTimer) clearTimeout(spotTimer); unsubUpdate(); },
    resume() { void load(); },
  };
}
