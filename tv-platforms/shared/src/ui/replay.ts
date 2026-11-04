/**
 * Replay (catch-up) of a live account channel whose subscription keeps past programmes
 * (ReplayPanel.kt): one tab per day, the programmes the source still holds, newest first.
 * Used from the player options and from the guide.
 */
import type { Ctx } from './app';
import { clock, onActivate } from './dom';
import { getLang, t } from '../i18n/index';
import { esc } from '../core/text';
import type { Channel } from '../storage/model';
import type { PlayItem } from '../player/controller';
import { parseRef, replayRef } from '../xtream/urls';
import type { XtreamProgramme } from '../xtream/client';
import { account, client } from './details';

export const hasReplay = (ch: Channel | null | undefined): ch is Channel => !!ch && ch.archiveDays > 0 && parseRef(ch.url)?.ref === 'live';

/** 0 = today, 1 = yesterday... (TV time zone). */
function dayIndex(ms: number): number {
  const d = new Date(); d.setHours(0, 0, 0, 0);
  const today = d.getTime();
  return ms >= today ? 0 : Math.floor((today - ms) / 86_400_000) + 1;
}
const dayLabel = (i: number) => i === 0 ? t('replay_today') : i === 1 ? t('replay_yesterday')
  : new Intl.DateTimeFormat(getLang() === 'ar' ? 'ar' : 'fr-FR', { weekday: 'short', day: 'numeric', month: 'short' }).format(new Date(Date.now() - i * 86_400_000));

/** Fills `box` with the replay list of `ch`; `onPlay` gets the item to play. */
export async function showReplay(ctx: Ctx, host: HTMLElement, ch: Channel, onPlay: (item: PlayItem) => void): Promise<void> {
  // Own element: the host (a reused side panel) keeps no listener from an earlier list.
  const box = document.createElement('div');
  box.style.cssText = 'height:100%;display:flex;flex-direction:column;gap:4rem';
  host.replaceChildren(box);
  const head = `<div class="t-headline">${esc(t('replay_title'))}</div><div class="t-mono">${esc(ch.name)}</div>`;
  box.innerHTML = head + `<div class="t-body dim" style="margin-top:12rem">${esc(t('vod_loading'))}</div>`;
  let all: XtreamProgramme[];
  try {
    const ref = parseRef(ch.url)!;
    const a = await account(ctx, ref.playlistId);
    if (!a) throw new Error('no account');
    const now = Date.now();
    all = (await client.epgTable(a.server, a.c, ref.id)).filter((p) => p.hasArchive && p.stop < now);
  } catch {
    box.innerHTML = head + `<div class="t-body" style="color:var(--caution);margin-top:12rem">${esc(t('vod_error'))}</div>`;
    return;
  }
  if (!all.length) { box.innerHTML = head + `<div class="t-body" style="margin-top:12rem">${esc(t('replay_empty'))}</div>`; return; }
  const days = [...new Set(all.map((p) => dayIndex(p.start)))].sort((x, y) => x - y);
  let day = days[0];
  const draw = () => {
    const shown = all.filter((p) => dayIndex(p.start) === day).sort((x, y) => y.start - x.start);
    box.innerHTML = head + `<div class="chips" data-scroll="x" style="margin:8rem 0">${days.map((d) => `<div class="chip fx${d === day ? ' selected' : ''}" data-k="d:${d}">${esc(dayLabel(d))}</div>`).join('')}</div>
      <div data-scroll="y" data-restore style="flex:1;overflow:hidden">${shown.map((p) => `<div class="row fx" data-k="p:${p.start}" style="height:56rem">
        <span class="t-mono tally" style="width:64rem">${clock(p.start)}</span><span class="grow t-body auto ellipsis">${esc(p.title)}</span>
        <span class="t-mono">${Math.round((p.stop - p.start) / 60_000)} min</span></div>`).join('')}</div>`;
  };
  draw();
  ctx.focus.enter(box.querySelector('[data-k^="p:"]')?.parentElement ?? box);
  onActivate(box, (k) => {
    if (k.startsWith('d:')) { day = Number(k.substring(2)); draw(); ctx.focus.focus(box.querySelector<HTMLElement>(`[data-k="${k}"]`)); return; }
    if (!k.startsWith('p:')) return;
    const p = all.find((x) => x.start === Number(k.substring(2)));
    const ref = p ? replayRef(ch.url, p.startLocal, p.start, p.stop) : null;
    if (!p || !ref) return;
    onPlay({
      key: `replay:${ch.channelKey}:${p.start}`, title: `${ch.name} · ${p.title}`, urls: [ref], isLive: false,
      logo: ch.logo, poster: null, userAgent: ch.userAgent, referrer: ch.referrer, drm: false, tvgId: null, channel: null, group: null, upNext: [],
    });
  });
}
