/**
 * Sports hub (SportsScreen.kt): the sports channels of the user's sources and the free official ones
 * on the leading side, the schedule of the next days with live scores, and for a match the services
 * that hold its rights in the chosen region. Nothing is played from the schedule itself.
 *
 * Difference with Android: the TV cannot open another app's store page from a web app, so the
 * rights holders are listed as text (docs/AUDIT.md).
 */
import type { Ctx, Screen } from '../app';
import { clock, h, img, onActivate } from '../dom';
import { getLang, t } from '../../i18n/index';
import { esc } from '../../core/text';
import { ContentKind } from '../../core/m3u';
import type { Channel } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';
import { Match, Sports, SportsGuide } from '../../sports/sports';
import guideJson from '../../../assets/sports_destinations.json';

const sports = new Sports(guideJson as unknown as SportsGuide);
const OWN = /sport|bein|رياض|foot/i;
const MAX_OWN = 600;

export function sportsScreen(ctx: Ctx): Screen {
  const el = h(`<div class="screen" style="display:flex;padding:30rem 0 0 0">
    <aside data-scroll="y" data-restore style="width:290rem;flex:none;padding:0 0 0 28rem;overflow:hidden">
      <div class="t-display" style="margin-bottom:14rem">${esc(t('sports_title'))}</div>
      <div class="row fx" data-k="region" style="height:48rem"><span class="grow t-body">${esc(t('sports_region'))}</span><span class="t-mono reg" style="color:var(--text)"></span></div>
      <div class="t-headline chead" style="margin:22rem 0 8rem"></div>
      <div class="chans"></div>
      <div class="t-small dim" style="margin:18rem 0 40rem">${esc(t('sports_source', sports.guide.reviewedAt))}</div>
    </aside>
    <main class="sched" data-scroll="y" data-restore style="flex:1;min-width:0;padding:0 28rem;overflow:hidden"></main>
    <div class="where hidden" data-scope style="position:absolute;top:50%;transform:translateY(-50%);inset-inline-end:28rem;width:380rem;background:var(--graphite);border-radius:12rem;padding:24rem;display:flex;flex-direction:column;gap:10rem;z-index:5"></div>
  </div>`);
  const reg = el.querySelector<HTMLElement>('.reg')!;
  const chead = el.querySelector<HTMLElement>('.chead')!;
  const chans = el.querySelector<HTMLElement>('.chans')!;
  const sched = el.querySelector<HTMLElement>('.sched')!;
  const where = el.querySelector<HTMLElement>('.where')!;
  const channels = new Map<string, Channel>();
  const matches = new Map<string, Match>();
  let whereReturn: HTMLElement | null = null;
  const region = () => ctx.settings.value.sportsRegion ?? 'FR';
  const locale = () => (getLang() === 'ar' ? 'ar' : 'fr-FR');

  function renderRegion() { reg.textContent = `${t(region() === 'FR' ? 'sports_region_fr' : 'sports_region_mena')}  ⇄`; }

  async function renderChannels() {
    const hidden = await ctx.hidden();
    const playlists = await ctx.db.playlists();
    const own: Channel[] = [];
    // The user's own sports channels (subscriptions, playlists) first: groups and names that say so.
    for (const p of playlists.filter((x) => x.kind !== 'builtin')) {
      for (const g of (p.groups[ContentKind.LIVE] ?? []).filter((x) => OWN.test(x.groupTitle) && !hidden.has(x.groupTitle))) {
        if (own.length >= MAX_OWN) break;
        own.push(...await ctx.db.channelsOf(p, ContentKind.LIVE, g.groupTitle, 0, MAX_OWN - own.length));
      }
    }
    if (own.length < MAX_OWN) {
      const byName = await ctx.db.search('sport', MAX_OWN, ContentKind.LIVE, hidden);
      const builtinIds = new Set(playlists.filter((x) => x.kind === 'builtin').map((x) => x.id));
      for (const c of byName[ContentKind.LIVE] ?? []) {
        if (own.length >= MAX_OWN) break;
        if (!builtinIds.has(c.playlistId) && !own.some((o) => o.channelKey === c.channelKey)) own.push(c);
      }
    }
    const builtin = (await ctx.db.inCategory('sports', 200)).filter((c) => c.kind === ContentKind.LIVE);
    const all = [...own, ...builtin.filter((b) => !own.some((o) => o.channelKey === b.channelKey))];
    chead.textContent = `${t(own.length ? 'sports_all_channels' : 'sports_free_channels')}  ${all.length}`;
    channels.clear();
    chans.innerHTML = all.map((c) => {
      channels.set(c.channelKey, c);
      return `<div class="row fx" data-k="c:${esc(c.channelKey)}" style="height:48rem">
        <span class="grow t-body auto ellipsis">${esc(c.name)}</span>
        ${c.geo ? `<span class="t-mono" style="color:var(--caution)">${esc(c.geo)}</span>` : ''}<span class="dot s${c.status}"></span></div>`;
    }).join('');
  }

  const teamCell = (name: string, logo: string | null, end: boolean) => `<div style="flex:1;min-width:0;display:flex;align-items:center;gap:8rem;justify-content:${end ? 'flex-end' : 'flex-start'}">
    ${end ? '' : `<div style="width:28rem;height:28rem;flex:none;position:relative">${img(logo, 'fill contain')}</div>`}
    <span class="t-body auto ellipsis">${esc(name)}</span>
    ${end ? `<div style="width:28rem;height:28rem;flex:none;position:relative">${img(logo, 'fill contain')}</div>` : ''}</div>`;

  async function renderSchedule(force = false) {
    sched.innerHTML = `<div class="t-headline" style="margin:70rem 0 8rem">${esc(t('sports_schedule'))}</div><div class="t-body dim">${esc(t('sports_loading'))}</div>`;
    const r = await sports.schedule(force);
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const dayOf = (m: Match) => { if (m.state === 'live') return today; const d = new Date(m.start); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(); };
    const days = new Map<number, Match[]>();
    for (const m of r.matches) days.set(dayOf(m), [...(days.get(dayOf(m)) ?? []), m]);
    const label = (d: number) => d === today ? t('sports_today') : d === today + 86400_000 ? t('sports_tomorrow')
      : new Intl.DateTimeFormat(locale(), { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date(d));
    matches.clear();
    let html = `<div class="t-headline" style="margin:70rem 0 8rem">${esc(t('sports_schedule'))}</div>`;
    if (r.failedLeagues.length) html += `<div class="t-small" style="color:var(--caution)">${esc(t('sports_failed', r.failedLeagues.join(', ')))}</div>`;
    for (const d of [...days.keys()].sort((a, b) => a - b)) {
      html += `<div class="t-title tally" style="margin:20rem 0 6rem">${esc(label(d))}</div>`;
      for (const m of days.get(d)!) {
        const k = `m:${m.leagueId}:${m.id}`;
        matches.set(k, m);
        const rights = sports.rightsFor(region(), m.leagueId);
        const status = m.state === 'live' ? `<span class="live-badge">${esc(t('sports_live'))}</span>`
          : m.state === 'finished' ? `<span class="t-mono">${esc(t('sports_finished'))}</span>` : `<span class="t-numeral-s" style="color:var(--text)">${clock(m.start)}</span>`;
        html += `<div class="row fx" data-k="${esc(k)}" style="height:64rem">
          <div style="width:76rem;flex:none;white-space:nowrap">${status}</div>
          ${teamCell(m.home.name, m.home.logo, true)}
          <span class="t-numeral-s" style="padding:0 14rem;color:var(--text)">${m.state === 'scheduled' ? 'vs' : `${esc(m.home.score ?? '0')} : ${esc(m.away.score ?? '0')}`}</span>
          ${teamCell(m.away.name, m.away.logo, false)}
          <div style="width:150rem;flex:none;padding-inline-start:12rem;display:flex;flex-direction:column">
            <span class="t-mono ellipsis">${esc(m.leagueName)}</span>
            <span class="t-small ellipsis" style="color:${rights?.verified ? 'var(--text)' : 'var(--text-dim)'}">${esc(rights?.destinations.join(' · ') ?? t('sports_unknown_rights'))}</span>
          </div></div>`;
      }
    }
    sched.innerHTML = html;
  }

  function openWhere(m: Match) {
    whereReturn = ctx.focus.current;
    const rights = sports.rightsFor(region(), m.leagueId);
    where.innerHTML = `<div class="t-headline auto">${esc(`${m.home.name} vs ${m.away.name}`)}</div>
      <div class="t-small dim auto">${esc(m.leagueName + (m.venue ? ` · ${m.venue}` : ''))}</div>
      <div class="t-title tally" style="margin-top:4rem">${esc(t('sports_where'))}</div>
      ${rights == null ? `<div class="t-body">${esc(t('sports_unknown_rights'))}</div>` : `
        ${rights.verified ? '' : `<div class="t-small" style="color:var(--caution)">${esc(t('sports_unverified'))}</div>`}
        ${rights.destinations.map((d) => `<div class="t-title" style="padding:6rem 0">${esc(d)}</div>`).join('')}
        ${rights.source ? `<div class="t-mono clamp2">${esc(rights.source)}</div>` : ''}`}
      <div class="row fx" data-k="close" style="height:44rem"><span class="t-body">${esc(t('player_back'))}</span></div>`;
    where.classList.remove('hidden');
    ctx.focus.enter(where);
  }
  function closeWhere() {
    where.classList.add('hidden');
    ctx.focus.current = null;
    if (whereReturn && document.contains(whereReturn)) ctx.focus.focus(whereReturn); else ctx.focus.ensure();
  }

  onActivate(where, (k) => { if (k === 'close') closeWhere(); });
  onActivate(el.querySelector('aside')!, async (k) => {
    if (k === 'region') {
      await ctx.saveSettings({ sportsRegion: region() === 'FR' ? 'MENA' : 'FR' });
      renderRegion();
      const f = ctx.focus.current;
      await renderSchedule();
      if (f) ctx.focus.focus(f);
      return;
    }
    const c = channels.get(k.substring(2));
    if (c) { void ctx.playback.play(itemFromChannel(c, null)); ctx.push('player'); }
  });
  onActivate(sched, (k) => { const m = matches.get(k); if (m) openWhere(m); });

  return {
    el,
    async mount() {
      renderRegion();
      await renderChannels();
      ctx.focus.enter(el.querySelector('aside'));
      try { await renderSchedule(); } catch {
        sched.innerHTML = `<div class="t-headline" style="margin:70rem 0 8rem">${esc(t('sports_schedule'))}</div><div class="t-body" style="color:var(--caution)">${esc(t('vod_error'))}</div>`;
      }
    },
    onKey(a) {
      if (a === 'back' && !where.classList.contains('hidden')) { closeWhere(); return true; }
      return false;
    },
  };
}
