/**
 * Full-screen player, port of PlayerScreen.kt + PlayerPanels.kt.
 * Live: ▲▼ / CH± zap, digits for a number (0 = previous channel), OK shows the bar or resumes a
 * paused channel, ◀ channel list, ▶ / MENU options. Films: OK play/pause, ◀▶ seek (holding speeds
 * up: 10 s, 30 s, 1 min, 2 min), ▲ options. The bar hides after 5 s, except while a live channel is paused.
 */
import type { Ctx, Screen } from '../app';
import { clock, formatTime, h, logoHtml, onActivate } from '../dom';
import { t, Key } from '../../i18n/index';
import { esc } from '../../core/text';
import { digitOf, Action } from '../../platform/platform';
import { itemFromChannel, PlayerUi } from '../../player/controller';
import { VirtualList } from '../virtual';
import type { Channel, Programme } from '../../storage/model';
import type { PlaybackIssue, Track } from '../../player/types';

const OVERLAY_MS = 5_000;
const SEEK_COMMIT_MS = 450;

const ISSUE_TEXT: Record<PlaybackIssue, Key> = {
  NETWORK: 'player_issue_network', TIMEOUT: 'player_issue_timeout', FORBIDDEN: 'player_issue_forbidden',
  NOT_FOUND: 'player_issue_not_found', AUTH: 'player_issue_auth', GEO: 'player_issue_forbidden',
  DRM: 'player_issue_drm', FORMAT: 'player_issue_format', DECODER: 'player_issue_decoder', UNKNOWN: 'player_issue_unknown',
};

type Panel = 'none' | 'options' | 'channels';

export function playerScreen(ctx: Ctx, params: Record<string, unknown>): Screen {
  const pb = ctx.playback;
  const el = h(`<div class="screen player" style="background:transparent">
    <div class="center" style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;pointer-events:none"></div>
    <div class="digits t-display hidden" style="position:absolute;top:36rem;inset-inline-end:36rem;background:rgba(11,12,14,.7);padding:6rem 18rem"></div>
    <div class="bar" style="position:absolute;inset:auto 0 0 0;padding:64rem 48rem 32rem;background:linear-gradient(transparent, rgba(11,12,14,.92) 40%, var(--ink));display:flex;flex-direction:column;gap:10rem"></div>
    <div class="cue t-title" style="position:absolute;inset-inline:10%;bottom:56rem;text-align:center;white-space:pre-line;pointer-events:none;text-shadow:0 0 4rem #000,0 0 8rem #000;font-size:calc(22rem * var(--sub-scale, 1))"></div>
    <div class="err hidden" data-scope style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center"></div>
    <div class="panel hidden" data-scope style="position:absolute;inset-block:0;width:400rem;background:rgba(19,21,24,.96);padding:24rem 0;overflow:hidden"></div>
  </div>`);
  const center = el.querySelector<HTMLElement>('.center')!;
  const digitsEl = el.querySelector<HTMLElement>('.digits')!;
  const bar = el.querySelector<HTMLElement>('.bar')!;
  const err = el.querySelector<HTMLElement>('.err')!;
  const panelEl = el.querySelector<HTMLElement>('.panel')!;
  const cueEl = el.querySelector<HTMLElement>('.cue')!;
  el.style.setProperty('--sub-scale', String(ctx.settings.value.subtitleScale || 1));

  let overlay = true;
  let hideTimer: ReturnType<typeof setTimeout> | null = null;
  let digits = '';
  let digitTimer: ReturnType<typeof setTimeout> | null = null;
  let panel: Panel = 'none';
  let epg: Programme[] = [];
  let epgKey = '';
  let pendingSeek: number | null = null;
  let seekTotal = 0;
  let seekTimer: ReturnType<typeof setTimeout> | null = null;
  let tick: ReturnType<typeof setInterval> | null = null;
  let unsub: (() => void) | null = null;
  let channelList: VirtualList<Channel> | null = null;

  const ui = () => pb.ui.value;

  function touch() {
    overlay = true;
    bar.classList.remove('hidden');
    if (hideTimer) clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (panel === 'none' && !ui().issue && ui().livePausedAt === 0) { overlay = false; bar.classList.add('hidden'); }
    }, OVERLAY_MS);
    renderBar();
  }

  function renderBar() {
    const u = ui();
    const item = u.item;
    if (!item) { bar.innerHTML = ''; return; }
    const v = u.video;
    const tags = v && v.height > 0
      ? [`${v.width ? v.width + '×' : ''}${v.height}`, v.codec ? v.codec.split('.')[0].toUpperCase() : null, v.bitrate > 0 ? `${(v.bitrate / 1e6).toFixed(1)} Mb/s` : null].filter(Boolean).map((x) => `<span class="tag">${esc(x as string)}</span>`).join('')
      : '';
    const ch = item.channel;
    let lower = '';
    if (item.isLive) {
      const now = Date.now();
      const cur = epg[0], next = epg[1];
      if (cur) {
        lower += `<div style="display:flex;align-items:center"><span class="t-mono tally" style="width:96rem">${esc(t('now'))}</span><span class="t-title auto ellipsis" style="flex:1">${esc(cur.title)}</span><span class="t-mono">${clock(cur.start)} - ${clock(cur.stop)}</span></div>`;
        if (cur.start <= now) lower += `<div class="progress"><i style="width:${Math.min(100, ((now - cur.start) * 100) / Math.max(1, cur.stop - cur.start))}%"></i></div>`;
      }
      if (next) lower += `<div style="display:flex"><span class="t-mono" style="width:96rem">${esc(t('next'))}</span><span class="t-body dim auto ellipsis">${clock(next.start)}  ${esc(next.title)}</span></div>`;
      if (u.livePausedAt > 0 || u.liveBehindMs > 0) {
        const behind = u.liveBehindMs + (u.livePausedAt > 0 ? Date.now() - u.livePausedAt : 0);
        lower += `<div style="display:flex;gap:12rem;align-items:center">${u.livePausedAt > 0 ? `<span class="t-title warn">❚❚  ${esc(t('player_paused_live'))}</span>` : ''}<span class="t-mono" style="color:var(--text)">${esc(t('player_behind_live', formatTime(behind)))}</span></div>`;
      }
      lower += `<div class="t-mono">${esc(t(u.livePausedAt > 0 ? 'player_paused_hint' : 'player_hint'))}</div>`;
    } else {
      const d = pb.adapter.durationMs();
      const pos = pendingSeek ?? pb.adapter.positionMs();
      if (d > 0) {
        lower += `<div class="progress" style="height:4rem;direction:ltr"><i style="width:${Math.min(100, (pos * 100) / d)}%"></i></div>
          <div style="display:flex;direction:ltr"><span class="t-mono" style="color:var(--text)">${formatTime(pos)}</span><span style="flex:1"></span><span class="t-mono">${formatTime(d)}</span></div>`;
      }
      lower += `<div class="t-mono">${esc(t('player_hint_vod'))}</div>`;
    }
    bar.innerHTML = `<div style="display:flex;align-items:center">
        ${ch ? `<span class="t-display tally" style="margin-inline-end:20rem">${ch.position}</span>` : ''}
        ${item.isLive ? `<div style="width:96rem;height:58rem;flex:none">${logoHtml(item.logo, item.title)}</div>` : ''}
        <div style="flex:1;min-width:0;margin-inline-start:18rem">
          <div style="display:flex;align-items:center;gap:10rem"><span class="t-headline auto ellipsis">${esc(item.title)}</span>${item.isLive ? `<span class="live-badge">${esc(t('live_badge'))}</span>` : ''}${u.isFavorite ? '<span class="t-title warn">★</span>' : ''}</div>
          <div style="display:flex;gap:8rem;align-items:center">${tags}${ch ? `<span class="dot s${ch.status}"></span>` : ''}</div>
        </div>
        <span class="t-numeral">${clock(Date.now())}</span>
      </div>${lower}`;
  }

  function renderCenter() {
    const u = ui();
    if (seekTotal !== 0 && u.item && !u.item.isLive) {
      center.innerHTML = `<div style="background:rgba(11,12,14,.72);border-radius:14rem;padding:12rem 22rem;text-align:center;direction:ltr">
        <div class="t-headline">${seekTotal < 0 ? '⏪  −' : '⏩  +'}${formatTime(Math.abs(seekTotal))}</div>
        <div class="t-mono" style="color:var(--text)">${formatTime(pendingSeek ?? pb.adapter.positionMs())} / ${formatTime(pb.adapter.durationMs())}</div></div>`;
    } else if (u.buffering && !u.issue) {
      center.innerHTML = `<div class="t-mono" style="background:rgba(11,12,14,.6);padding:10rem 16rem;color:var(--text)">${u.attempt ? esc(t('player_trying', u.attempt)) : '···'}</div>`;
    } else center.innerHTML = '';
  }

  function renderError(u: PlayerUi) {
    if (!u.issue) { err.classList.add('hidden'); return; }
    err.classList.remove('hidden');
    err.innerHTML = `<div style="width:560rem;background:var(--graphite);padding:28rem;display:flex;flex-direction:column;gap:12rem">
      <div class="t-headline">${esc(t('player_error_title'))}</div>
      <div class="t-body">${esc(t(ISSUE_TEXT[u.issue]))}</div>
      <div class="row fx" data-k="retry" style="height:48rem"><span class="t-title">${esc(t('player_retry'))}</span></div>
      ${u.item?.isLive ? `<div class="row fx" data-k="nextch" style="height:48rem"><span class="t-title">${esc(t('player_next_channel'))}</span></div>` : ''}
      <div class="row fx" data-k="back" style="height:48rem"><span class="t-title">${esc(t('player_back'))}</span></div></div>`;
    ctx.focus.enter(err);
  }

  onActivate(err, (k) => {
    if (k === 'retry') pb.retry();
    else if (k === 'nextch') void pb.zap(1);
    else leave();
  });

  // ---- options panel ----
  function trackRows(title: Key, list: Track[], kind: 'q' | 'a' | 's') {
    const auto = kind === 'a' ? '' : `<div class="row fx${list.every((x) => !x.selected) ? ' selected' : ''}" data-k="${kind}:" style="height:46rem"><span class="grow t-body">${esc(t(kind === 'q' ? 'player_auto' : 'player_off'))}</span></div>`;
    return `<div class="t-headline" style="padding:22rem 22rem 6rem">${esc(t(title))}</div>`
      + (list.length === 0 && kind === 'a' ? `<div class="t-small" style="padding:0 22rem">${esc(t('player_no_tracks'))}</div>` : '')
      + auto + list.map((x) => `<div class="row fx${x.selected ? ' selected' : ''}" data-k="${kind}:${esc(x.id)}" style="height:46rem"><span class="grow t-body auto ellipsis">${esc(x.label)}</span>${x.selected ? '<span class="t-mono tally">●</span>' : ''}</div>`).join('');
  }

  function renderOptions() {
    const u = ui();
    const item = u.item;
    const aspect = (['player_aspect_fit', 'player_aspect_zoom', 'player_aspect_stretch'] as Key[])[u.resizeMode];
    const opt = (k: string, label: string, value = '') => `<div class="row fx" data-k="${k}" style="height:50rem;padding:0 18rem"><span class="grow t-title auto">${esc(label)}</span>${value ? `<span class="t-mono" style="color:var(--text)">${esc(value)}</span>` : ''}</div>`;
    let html = opt('fav', `${u.isFavorite ? '★' : '☆'}  ${t(u.isFavorite ? 'live_remove_favorite' : 'live_add_favorite')}`);
    if (item?.isLive) {
      html += opt('pause', (u.livePausedAt > 0 ? '▶  ' : '❚❚  ') + t(u.livePausedAt > 0 ? 'player_resume' : 'player_pause'));
      if (u.livePausedAt > 0 || u.liveBehindMs > 0) html += opt('golive', '⏭  ' + t('player_go_live'));
      if (u.previousTitle) html += opt('prev', '↺  ' + t('player_previous_channel'), u.previousTitle);
    } else {
      html += opt('speed', t('player_speed'), '×' + String(u.speed));
    }
    html += opt('sleep', t('player_sleep'), u.sleepMinutes != null ? t('player_sleep_left', u.sleepMinutes) : t('player_off'));
    html += opt('aspect', t('player_aspect'), t(aspect));
    html += trackRows('player_quality', pb.qualities(), 'q') + trackRows('player_audio', pb.audio(), 'a') + trackRows('player_subtitles', pb.text(), 's');
    panelEl.innerHTML = `<div data-scroll="y" style="height:100%;overflow:hidden">${html}</div>`;
  }

  async function renderChannels() {
    const item = ui().item;
    const ch = item?.channel;
    if (!ch) { closePanel(); return; }
    const p = (await ctx.db.playlists()).find((x) => x.id === ch.playlistId);
    if (!p) { closePanel(); return; }
    const g = item!.group;
    const total = await ctx.db.countOf(p, ch.kind, g);
    channelList?.destroy();
    channelList = new VirtualList<Channel>({
      rowHeight: 52, total,
      load: (o, l) => ctx.db.channelsOf(p, ch.kind, g, o, l),
      render: (c) => `<div class="row fx${c.channelKey === ui().item?.key ? ' selected' : ''}" style="height:52rem"><span class="t-numeral-s" style="width:52rem">${c.position}</span><span class="grow t-body auto ellipsis">${esc(c.name)}</span><span class="dot s${c.status}"></span></div>`,
      onActivate: (c) => { void pb.play(itemFromChannel(c, g)); closePanel(); },
    });
    panelEl.replaceChildren(channelList.el);
    channelList.renderWindow();
    // Start on the channel being watched.
    const idx = Math.max(0, (await ctx.db.channelsOf(p, ch.kind, g, 0, total)).findIndex((c) => c.channelKey === ch.channelKey));
    await channelList.focusIndex(idx, (e) => ctx.focus.focus(e));
  }

  function openPanel(which: Panel) {
    panel = which;
    panelEl.classList.remove('hidden');
    panelEl.style.insetInlineStart = which === 'channels' ? '0' : 'auto';
    panelEl.style.insetInlineEnd = which === 'options' ? '0' : 'auto';
    if (which === 'options') { renderOptions(); ctx.focus.enter(panelEl); }
    else void renderChannels();
    touch();
  }

  function closePanel() {
    panel = 'none';
    panelEl.classList.add('hidden');
    channelList?.destroy();
    channelList = null;
    ctx.focus.current = null;
    touch();
  }

  onActivate(panelEl, (k) => {
    if (panel !== 'options') return;
    const keep = k;
    switch (true) {
      case k === 'fav': void pb.toggleFavorite(); break;
      case k === 'pause': pb.togglePause(); break;
      case k === 'golive': pb.goLive(); break;
      case k === 'prev': pb.recall(); break;
      case k === 'speed': pb.cycleSpeed(); break;
      case k === 'sleep': pb.cycleSleep(); break;
      case k === 'aspect': pb.cycleResize(); break;
      case k.startsWith('q:'): pb.adapter.selectQuality(k.length > 2 ? k.substring(2) : null); break;
      case k.startsWith('a:'): pb.adapter.selectAudio(k.substring(2)); break;
      case k.startsWith('s:'): pb.selectText(k.length > 2 ? k.substring(2) : null); break;
    }
    setTimeout(() => { renderOptions(); ctx.focus.focus(panelEl.querySelector<HTMLElement>(`[data-k="${CSS.escape(keep)}"]`)); }, 60);
  });

  // ---- seeking: presses add up, the jump happens once the user stops ----
  function nudge(delta: number) {
    const d = pb.adapter.durationMs();
    const base = pendingSeek ?? pb.adapter.positionMs();
    let target = Math.max(0, base + delta);
    if (d > 0) target = Math.min(target, d - 1000);
    pendingSeek = target;
    seekTotal += delta;
    if (seekTimer) clearTimeout(seekTimer);
    seekTimer = setTimeout(() => {
      if (pendingSeek != null) pb.seekTo(pendingSeek);
      pendingSeek = null;
      setTimeout(() => { seekTotal = 0; renderCenter(); }, 900);
    }, SEEK_COMMIT_MS);
    renderCenter();
    touch();
  }
  let repeatCount = 0;
  const stepFor = (r: number) => (r >= 20 ? 120_000 : r >= 8 ? 60_000 : r >= 3 ? 30_000 : 10_000);

  function leave() {
    ctx.back();
  }

  async function refreshEpg() {
    const item = ui().item;
    if (!item?.isLive) { epg = []; return; }
    if (epgKey === item.key) return;
    epgKey = item.key;
    epg = await ctx.epg.nowNext(item.tvgId);
    renderBar();
  }

  function onKey(a: Action, repeat: boolean): boolean {
    if (a === 'back') {
      if (panel !== 'none') { closePanel(); return true; }
      return false;
    }
    if (panel !== 'none' || ui().issue) return false;
    const item = ui().item;
    const live = item?.isLive === true;
    repeatCount = repeat ? repeatCount + 1 : 0;
    const d = digitOf(a);
    if (d != null && live) {
      digits = (digits + d).slice(-4);
      digitsEl.textContent = digits;
      digitsEl.classList.remove('hidden');
      if (digitTimer) clearTimeout(digitTimer);
      digitTimer = setTimeout(() => {
        const n = Number(digits);
        digits = '';
        digitsEl.classList.add('hidden');
        if (Number.isFinite(n)) void pb.playNumber(n);
      }, 1_800);
      touch();
      return true;
    }
    switch (a) {
      case 'chup': void pb.zap(1); touch(); return true;
      case 'chdown': void pb.zap(-1); touch(); return true;
      case 'up': if (live) { void pb.zap(1); touch(); } else openPanel('options'); return true;
      case 'down': if (live) { void pb.zap(-1); } touch(); return true;
      case 'ok':
        if (!live) { pb.togglePause(); touch(); }
        else if (ui().livePausedAt > 0) { pb.resume(); touch(); }
        else if (overlay) { overlay = false; bar.classList.add('hidden'); }
        else touch();
        return true;
      case 'playpause': pb.togglePause(); touch(); return true;
      case 'play': pb.resume(); touch(); return true;
      case 'pause': pb.pause(); touch(); return true;
      case 'stop': leave(); return true;
      case 'ff': if (!live) nudge(30_000); return true;
      case 'rw': if (!live) nudge(-30_000); return true;
      case 'next': if (!pb.playNext()) void pb.zap(1); touch(); return true;
      case 'prev': if (live) pb.recall(); touch(); return true;
      case 'left': if (live) openPanel('channels'); else nudge(-stepFor(repeatCount)); return true;
      case 'right': if (live) openPanel('options'); else nudge(stepFor(repeatCount)); return true;
      case 'menu': case 'green': openPanel('options'); return true;
      case 'info': case 'yellow': touch(); return true;
    }
    return false;
  }

  return {
    el,
    fullscreen: true,
    mount() {
      document.body.classList.add('video-on');
      pb.adapter.setRect(null);
      unsub = pb.ui.subscribe((u) => {
        if (u.item?.key !== epgKey) void refreshEpg();
        renderBar();
        renderCenter();
        renderError(u);
        if (cueEl.textContent !== (u.cue ?? '')) cueEl.textContent = u.cue ?? '';
        if (!u.issue && err.contains(ctx.focus.current)) ctx.focus.current = null;
      });
      tick = setInterval(() => { if (overlay) renderBar(); }, 1000);
      touch();
      pb.ensurePrepared();
    },
    unmount() {
      unsub?.();
      if (tick) clearInterval(tick);
      if (hideTimer) clearTimeout(hideTimer);
      if (digitTimer) clearTimeout(digitTimer);
      channelList?.destroy();
      // Back to the live list: the preview keeps the channel. Anywhere else: stop.
      if (!params['fromLive']) { void pb.stop(); document.body.classList.remove('video-on'); }
    },
    onKey,
  };
}
