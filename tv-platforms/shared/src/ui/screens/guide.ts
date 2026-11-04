/**
 * TV guide grid (GuideScreen.kt): channels down, a three-hour window across that moves by an hour and
 * a half with ◀▶ at its edges. A programme on air opens the channel.
 */
import { hasReplay, showReplay } from '../replay';
import type { Ctx, Screen } from '../app';
import { clock, h, logoHtml, onActivate } from '../dom';
import { t } from '../../i18n/index';
import { esc } from '../../core/text';
import { ContentKind } from '../../core/m3u';
import type { Channel, Programme } from '../../storage/model';
import { itemFromChannel } from '../../player/controller';

const HALF_HOUR = 30 * 60_000;
const SPAN = 6 * HALF_HOUR;
const MAX_ROWS = 200;

export function guideScreen(ctx: Ctx, params: Record<string, unknown>): Screen {
  const el = h(`<div class="screen pad" style="display:flex;flex-direction:column">
    <div style="display:flex;align-items:center;gap:16rem;margin-bottom:10rem">
      <span class="t-display">${esc(t('guide_title'))}</span>
      <div class="row fx" data-k="prev" style="width:56rem;justify-content:center">◀</div>
      <span class="t-mono win" style="color:var(--text)"></span>
      <div class="row fx" data-k="next" style="width:56rem;justify-content:center">▶</div>
    </div>
    <div class="times" style="display:flex;margin-inline-start:200rem;height:24rem"></div>
    <div class="grid" data-scroll="y" data-restore style="flex:1;overflow:hidden"></div>
    <div class="side hidden" data-scope style="position:absolute;inset-block:0;inset-inline-end:0;width:460rem;background:var(--graphite);padding:22rem;z-index:5"></div>
  </div>`);
  const side = el.querySelector<HTMLElement>('.side')!;
  let sideReturn: HTMLElement | null = null;
  const closeSide = () => {
    side.classList.add('hidden'); side.innerHTML = '';
    ctx.focus.current = null;
    if (sideReturn && document.contains(sideReturn)) ctx.focus.focus(sideReturn); else ctx.focus.ensure();
  };
  const grid = el.querySelector<HTMLElement>('.grid')!;
  const times = el.querySelector<HTMLElement>('.times')!;
  const win = el.querySelector<HTMLElement>('.win')!;
  const now = Date.now();
  let start = now - (now % HALF_HOUR) - HALF_HOUR;
  let channels: Channel[] = [];
  const progs = new Map<string, Programme>();

  async function render() {
    win.textContent = `${clock(start)} – ${clock(start + SPAN)}`;
    times.innerHTML = Array.from({ length: 6 }, (_, i) => `<span class="t-mono" style="flex:1">${clock(start + i * HALF_HOUR)}</span>`).join('');
    progs.clear();
    const rows: string[] = [];
    for (const ch of channels) {
      const list = ch.tvgId ? await ctx.db.programmes(ch.tvgId, start, start + SPAN, 30) : [];
      const fallback = list.length === 0 && ch.tvgId ? await ctx.db.programmes(ch.tvgId.split('@')[0], start, start + SPAN, 30) : list;
      const cells = fallback.filter((p) => p.stop > start && p.start < start + SPAN).map((p) => {
        progs.set(p.id, p);
        const a = Math.max(p.start, start), b = Math.min(p.stop, start + SPAN);
        const onAir = p.start <= Date.now() && p.stop > Date.now();
        return `<div class="row fx" data-k="p:${esc(ch.channelKey)}|${esc(p.id)}" style="position:absolute;top:2rem;bottom:2rem;inset-inline-start:${((a - start) * 100) / SPAN}%;width:calc(${((b - a) * 100) / SPAN}% - 4rem);min-height:0;padding:0 10rem;${onAir ? 'background:var(--raised)' : ''}">
          <span class="t-small auto ellipsis" style="color:var(--text)">${esc(p.title)}</span></div>`;
      }).join('');
      rows.push(`<div style="display:flex;height:52rem;align-items:stretch;border-bottom:1rem solid var(--hairline)">
        <div style="width:200rem;flex:none;display:flex;align-items:center;gap:8rem;padding-inline-end:8rem"><div style="width:48rem;height:30rem;flex:none">${logoHtml(ch.logo, ch.name)}</div><span class="t-small auto ellipsis" style="color:var(--text)">${esc(ch.name)}</span></div>
        <div style="flex:1;position:relative">${cells || `<div class="row fx" data-k="c:${esc(ch.channelKey)}" style="position:absolute;inset:2rem 0;min-height:0"><span class="t-small faint">${esc(t('live_no_epg'))}</span></div>`}</div></div>`);
    }
    grid.innerHTML = rows.join('');
  }

  onActivate(el, async (k) => {
    if (k === 'prev') { start -= SPAN / 2; await render(); return; }
    if (k === 'next') { start += SPAN / 2; await render(); return; }
    const key = k.startsWith('p:') ? k.substring(2).split('|')[0] : k.startsWith('c:') ? k.substring(2) : null;
    const ch = channels.find((c) => c.channelKey === key);
    if (!ch) return;
    const p = k.startsWith('p:') ? progs.get(k.substring(2).split('|')[1]) : null;
    if (p && p.start > Date.now()) return; // a future programme: nothing to play yet
    // A past programme of a channel whose subscription keeps replays: the replay list (GuideScreen.kt).
    if ((!p || p.stop <= Date.now()) && hasReplay(ch) && (p || k.startsWith('c:'))) {
      if (p) {
        sideReturn = ctx.focus.current;
        side.classList.remove('hidden');
        void showReplay(ctx, side, ch, (item) => { closeSide(); void ctx.playback.play(item); ctx.push('player'); });
        return;
      }
    }
    if (p && p.stop <= Date.now()) return; // over, and no replay
    void ctx.playback.play(itemFromChannel(ch, (params['group'] as string) ?? null));
    ctx.push('player');
  });

  return {
    el,
    async mount() {
      const p = (await ctx.db.playlists()).find((x) => x.id === params['playlistId']) ?? (await ctx.db.playlists())[0];
      if (p) channels = (await ctx.db.channelsOf(p, ContentKind.LIVE, (params['group'] as string) ?? null, 0, MAX_ROWS)).filter((c) => !!c.tvgId);
      await render();
      ctx.focus.enter(grid);
    },
    onKey(a) {
      if (a === 'back' && !side.classList.contains('hidden')) { closeSide(); return true; }
      return false;
    },
  };
}
