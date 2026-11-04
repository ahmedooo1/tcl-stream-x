/**
 * Live channels, port of LiveScreen.kt: sources and categories on the leading side, the channel list
 * in the middle (virtualised: tens of thousands of channels), and a live preview with the guide on
 * the trailing side. The preview starts after a short dwell on a channel and is the same player as
 * the full screen, so OK goes full screen without rebuffering.
 */
import { groupsFor, lineup } from '../lineup';
import { regionStrip } from '../region';
import { regionCodes } from '../../storage/model';
import type { Ctx, Screen } from '../app';
import { h, clock, logoHtml, onActivate } from '../dom';
import { groupLabel } from '../labels';
import { t } from '../../i18n/index';
import { esc } from '../../core/text';
import { ContentKind } from '../../core/m3u';
import type { Channel, Playlist } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';
import { VirtualList } from '../virtual';

const FAV = '\u0000fav';
const ALL = '\u0000all';
const PREVIEW_DWELL_MS = 700;

function statusText(s: number): string {
  return t((['status_unknown', 'status_online', 'status_offline', 'status_forbidden', 'status_forbidden', 'status_drm', 'status_timeout', 'status_invalid', 'status_auth'] as const)[s] ?? 'status_unknown');
}

export function liveScreen(ctx: Ctx, params: Record<string, unknown>): Screen {
  const el = h(`<div class="screen live" style="display:flex;padding:24rem 0 0 0">
    <aside class="groups" style="width:220rem;flex:none;display:flex;flex-direction:column;padding:0 10rem 0 24rem">
      <div class="row fx src" data-k="src" style="height:auto;padding:8rem 14rem;flex-direction:column;align-items:flex-start;gap:0"></div>
      <div class="glist" data-scroll="y" data-restore style="flex:1;overflow:hidden;margin-top:8rem"></div>
    </aside>
    <main style="flex:1;min-width:0;display:flex;flex-direction:column;padding:0 12rem">
      <div class="rstrip chips" data-scroll="x" data-restore style="flex:none;margin-bottom:6rem"></div>
      <div class="lhead" style="display:flex;align-items:baseline;gap:12rem;height:40rem"></div>
      <div class="clist" style="flex:1;min-height:0"></div>
    </main>
    <aside class="side" style="width:330rem;flex:none;padding:0 32rem 0 10rem;display:flex;flex-direction:column;gap:12rem">
      <div class="pv" style="position:relative;aspect-ratio:16/9;border-radius:12rem;border:1rem solid var(--hairline);overflow:hidden;background:transparent">
        <span class="live-badge" style="position:absolute;top:10rem;inset-inline-start:10rem">${esc(t('live_badge'))}</span>
        <span class="t-mono res" style="position:absolute;top:10rem;inset-inline-end:10rem;color:var(--text)"></span>
      </div>
      <div class="info"></div>
    </aside>
  </div>`);
  const src = el.querySelector<HTMLElement>('.src')!;
  const glist = el.querySelector<HTMLElement>('.glist')!;
  const head = el.querySelector<HTMLElement>('.lhead')!;
  const rstrip = el.querySelector<HTMLElement>('.rstrip')!;
  let region: string | null = null;
  const clist = el.querySelector<HTMLElement>('.clist')!;
  const pv = el.querySelector<HTMLElement>('.pv')!;
  const info = el.querySelector<HTMLElement>('.info')!;

  let playlists: Playlist[] = [];
  let p: Playlist | null = null;
  let group: string = (params['group'] as string) ?? ALL;
  let list: VirtualList<Channel> | null = null;
  let dwell: ReturnType<typeof setTimeout> | null = null;
  let focusedCh: Channel | null = null;
  let goingFull = false;
  let unsub: (() => void) | null = null;

  function placePreview() {
    const r = pv.getBoundingClientRect();
    ctx.playback.adapter.setRect({ x: r.left, y: r.top, w: r.width, h: r.height });
    document.body.classList.add('video-on');
  }

  async function renderInfo(ch: Channel | null) {
    if (!ch) { info.innerHTML = `<div class="t-body dim">${esc(t('live_select_channel'))}</div>`; return; }
    const epg = await ctx.epg.nowNext(ch.tvgId);
    if (focusedCh !== ch) return;
    const now = Date.now();
    const cur = epg[0], next = epg[1];
    info.innerHTML = `
      <div class="t-headline auto clamp2">${esc(ch.name)}</div>
      <div style="display:flex;align-items:center;gap:8rem"><span class="dot s${ch.status}"></span><span class="t-small">${esc(statusText(ch.status))}</span></div>
      <div style="height:1rem;background:var(--hairline);margin:6rem 0"></div>
      ${cur ? `<div class="t-mono tally">${esc(t('now'))}  ${clock(cur.start)} - ${clock(cur.stop)}</div>
        <div class="t-title auto clamp2">${esc(cur.title)}</div>
        <div class="progress" style="margin:6rem 0"><i style="width:${Math.min(100, Math.max(0, ((now - cur.start) * 100) / Math.max(1, cur.stop - cur.start)))}%"></i></div>
        ${cur.description ? `<div class="t-small auto clamp3">${esc(cur.description)}</div>` : ''}
        ${next ? `<div class="t-mono" style="margin-top:8rem">${esc(t('next'))}  ${clock(next.start)}  <span class="auto" style="color:var(--text-dim)">${esc(next.title)}</span></div>` : ''}`
      : `<div class="t-small">${esc(t('live_no_epg'))}</div>`}`;
  }

  function renderSource() {
    src.innerHTML = p
      ? `<span class="t-title auto ellipsis" style="max-width:100%">${esc(p.name)}</span><span class="t-mono">${p.count} ${playlists.length > 1 ? '⇄' : ''}</span>`
      : `<span class="t-title">${esc(t('nav_live'))}</span>`;
  }

  async function renderGroups() {
    if (!p) { glist.innerHTML = ''; return; }
    const hidden = await ctx.hidden();
    const favCount = (await ctx.db.favorites(1000)).filter((c) => c.playlistId === p!.id && c.kind === ContentKind.LIVE && inRegion(c)).length;
    const groups = groupsFor(p, ContentKind.LIVE, region, hidden);
    if (group !== ALL && group !== FAV && !groups.some((g) => g.groupTitle === group)) group = ALL;
    rstrip.innerHTML = regionStrip(p.regions?.[ContentKind.LIVE], region);
    const total = groups.reduce((s, g) => s + g.count, 0);
    const row = (k: string, label: string, n: number) =>
      `<div class="row fx${k === group ? ' selected' : ''}" data-k="${esc(k)}" style="height:44rem"><span class="grow t-body auto ellipsis">${esc(label)}</span><span class="t-mono">${n}</span></div>`;
    glist.innerHTML = row(FAV, t('group_favorites'), favCount)
      + row(ALL, t('group_all'), total)
      + groups.map((g) => row(g.groupTitle, groupLabel(g.groupTitle), g.count)).join('');
  }

  async function renderList() {
    list?.destroy();
    clist.innerHTML = '';
    if (!p) { clist.innerHTML = `<div class="empty t-body dim">${esc(t('live_empty'))}</div>`; return; }
    const pl = p;
    const g = group;
    const hidden = await ctx.hidden();
    let total: number;
    let load: (o: number, l: number) => Promise<Channel[]>;
    if (g === FAV) {
      const favs = (await ctx.db.favorites(1000)).filter((c) => c.playlistId === pl.id && c.kind === ContentKind.LIVE && inRegion(c));
      total = favs.length;
      load = async (o, l) => favs.slice(o, o + l);
    } else {
      const lu = await lineup(ctx.db, pl, ContentKind.LIVE, g === ALL ? null : g, region, hidden);
      total = lu.total;
      load = lu.load;
    }
    const title = g === FAV ? t('group_favorites') : g === ALL ? t('group_all') : groupLabel(g);
    head.innerHTML = `<span class="t-headline auto ellipsis" style="max-width:60%">${esc(title)}</span><span class="t-mono">${total}</span>
      <span class="row fx" data-k="guide" style="min-height:32rem;margin-inline-start:auto"><span class="t-label" style="white-space:nowrap">${esc(t('live_guide'))}</span></span>
      <span class="row fx" data-k="check" style="min-height:32rem;max-width:170rem"><span class="t-label ellipsis chk" style="white-space:nowrap">${esc(checkLabel())}</span></span>`;
    currentLoad = { total, load };
    if (total === 0) { clist.innerHTML = `<div class="empty t-body dim">${esc(t('live_empty'))}</div>`; return; }
    list = new VirtualList<Channel>({
      rowHeight: 52,
      total,
      load,
      render: (ch) => `<div class="row fx" data-ck="${esc(ch.channelKey)}" style="height:52rem">
        <span class="t-numeral-s" style="width:40rem;flex:none">${ch.position}</span>
        <div style="width:52rem;height:32rem;flex:none">${logoHtml(ch.logo, ch.name, '')}</div>
        <span class="grow t-body auto ellipsis">${esc(ch.name)}</span>
        ${ch.drm ? `<span class="tag">${esc(t('live_drm_flag'))}</span>` : ''}<span class="dot s${checked.get(ch.channelKey) ?? ch.status}"></span></div>`,
      onFocus: (ch) => {
        focusedCh = ch;
        void renderInfo(ch);
        if (dwell) clearTimeout(dwell);
        if (!ctx.settings.value.previewOnFocus || ch.drm) return;
        dwell = setTimeout(() => {
          if (ctx.playback.ui.value.item?.key !== ch.channelKey) void ctx.playback.play(itemFromChannel(ch, g === ALL || g === FAV ? null : g));
          placePreview();
        }, PREVIEW_DWELL_MS);
      },
      onActivate: (ch) => {
        if (ctx.playback.ui.value.item?.key !== ch.channelKey) void ctx.playback.play(itemFromChannel(ch, g === ALL || g === FAV ? null : g));
        goingFull = true;
        ctx.push('player', { fromLive: true });
      },
      onLong: async (ch) => {
        const fav = await ctx.db.isFavorite(ch.channelKey);
        const r = await ctx.dialog({ title: ch.name, buttons: [['fav', t(fav ? 'live_remove_favorite' : 'live_add_favorite')], ['cancel', '×']] });
        if (r === 'fav') { await ctx.db.toggleFavorite(ch.channelKey); void renderGroups(); }
      },
    });
    clist.append(list.el);
    list.renderWindow();
  }

  onActivate(el.querySelector('.groups')!, async (k) => {
    if (k === 'src') {
      if (playlists.length < 2 || !p) return;
      p = playlists[(playlists.indexOf(p) + 1) % playlists.length];
      group = ALL;
      await ctx.db.setSetting('livePlaylist', p.id);
      renderSource();
      await renderGroups();
      await renderList();
      return;
    }
    group = k;
    glist.querySelectorAll('.row').forEach((r) => r.classList.toggle('selected', (r as HTMLElement).dataset.k === k));
    await renderList();
    ctx.focus.enter(clist);
  });
  const inRegion = (c: Channel) => region == null || regionCodes(c).includes(region);
  onActivate(rstrip, async (k) => {
    if (!k.startsWith('region:')) return;
    region = k.length > 7 ? k.substring(7) : null;
    await renderGroups();
    await renderList();
    ctx.focus.focus(rstrip.querySelector<HTMLElement>(`[data-k="${CSS.escape(k)}"]`));
  });
  // ---- availability check (AvailabilityChecker.kt): oldest results first, 300 at most ----
  const checked = new Map<string, number>();
  let currentLoad: { total: number; load: (o: number, l: number) => Promise<Channel[]> } | null = null;
  function checkLabel() {
    const pr = ctx.checker.progress.value;
    return pr.running ? t('live_checking', pr.done, pr.total) : pr.total > 0 ? t('live_check_done', pr.online, pr.total) : t('live_check');
  }
  const unsubCheck = ctx.checker.progress.subscribe((pr) => {
    const lbl = head.querySelector('.chk');
    if (lbl) lbl.textContent = checkLabel();
    if (!pr.last) return;
    checked.set(pr.last.key, pr.last.status);
    clist.querySelectorAll<HTMLElement>(`[data-ck="${CSS.escape(pr.last.key)}"] .dot`).forEach((d) => { d.className = `dot s${pr.last!.status}`; });
  });
  async function startCheck() {
    if (!currentLoad || ctx.checker.progress.value.running) return;
    const rows = await currentLoad.load(0, Math.min(currentLoad.total, 3000));
    void ctx.checker.check(rows, 300);
  }

  onActivate(head, (k) => { if (k === 'check') { void startCheck(); return; } if (k === 'guide') ctx.push('guide', { playlistId: p?.id, group: group === ALL || group === FAV ? null : group }); });

  return {
    el,
    async mount() {
      playlists = (await ctx.db.playlists()).filter((x) => (x.groups[ContentKind.LIVE]?.length ?? 0) > 0);
      const wanted = (params['playlistId'] as number | undefined) ?? (await ctx.db.setting<number>('livePlaylist', 0));
      p = playlists.find((x) => x.id === wanted) ?? playlists.find((x) => x.kind !== 'builtin') ?? playlists[0] ?? null;
      renderSource();
      await renderGroups();
      await renderList();
      void renderInfo(null);
      unsub = ctx.playback.ui.subscribe((u) => {
        const v = u.video;
        el.querySelector('.res')!.textContent = v && v.height > 0 ? `${v.height}p` : '';
      });
      if (ctx.playback.ui.value.item) placePreview();
      ctx.focus.enter(clist.querySelector('.vlist') ?? glist);
    },
    unmount() {
      if (dwell) clearTimeout(dwell);
      unsub?.();
      unsubCheck();
      list?.destroy();
      // Back to the list from the player keeps the preview running; anywhere else stops it.
      if (!goingFull) { void ctx.playback.stop(); document.body.classList.remove('video-on'); }
      goingFull = false;
    },
    resume() { if (ctx.playback.ui.value.item) placePreview(); },
  };
}
