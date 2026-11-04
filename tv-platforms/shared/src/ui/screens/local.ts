/**
 * "Mes fichiers" (LocalScreen.kt): the videos of the TV's USB storage, by folder, when the system
 * lets the app read them (Samsung through tizen.filesystem). Elsewhere the screen says it cannot.
 */
import type { Ctx, Screen } from '../app';
import { h, onActivate } from '../dom';
import { t } from '../../i18n/index';
import { esc } from '../../core/text';
import type { LocalVideo } from '../../platform/platform';

export function localScreen(ctx: Ctx): Screen {
  const el = h(`<div class="screen pad" style="display:flex;flex-direction:column">
    <div class="t-display">${esc(t('local_title'))}</div>
    <div class="t-small" style="margin:6rem 0 10rem">${esc(t('local_permission_body'))}</div>
    <div class="list" data-scroll="y" data-restore style="flex:1;overflow:hidden"></div></div>`);
  const list = el.querySelector<HTMLElement>('.list')!;
  let videos: LocalVideo[] = [];

  onActivate(list, (k) => {
    const v = videos[Number(k.substring(2))];
    if (!k.startsWith('v:') || !v) return;
    const items = videos.filter((x) => x.folder === v.folder).map((x) => ({
      key: 'local:' + x.uri, title: x.title, urls: [x.uri], isLive: false, logo: null, poster: null,
      userAgent: null, referrer: null, drm: false, tvgId: null, channel: null, group: null, upNext: [],
    }));
    const i = items.findIndex((x) => x.urls[0] === v.uri);
    void ctx.playback.play({ ...items[i], upNext: items.slice(i + 1) });
    ctx.push('player');
  });

  return {
    el,
    async mount() {
      const read = ctx.platform.localVideos;
      if (!read) { list.innerHTML = `<div class="t-body dim">${esc(t('tv_blocked_local'))}</div>`; return; }
      list.innerHTML = `<div class="t-body dim">${esc(t('tv_loading'))}</div>`;
      videos = await read((n) => { list.innerHTML = `<div class="t-body dim">${esc(t('tv_loading'))} ${n}</div>`; });
      if (!videos.length) { list.innerHTML = `<div class="t-body dim">${esc(t('local_empty'))}</div>`; return; }
      let html = '';
      let folder = '';
      videos.forEach((v, i) => {
        if (v.folder !== folder) { folder = v.folder; html += `<div class="t-headline auto" style="margin:16rem 0 6rem">${esc(`${v.storage} · ${v.folder}`)}</div>`; }
        html += `<div class="row fx" data-k="v:${i}" style="height:50rem"><span class="t-mono tally" style="width:24rem">▶</span><span class="grow t-body auto ellipsis">${esc(v.title)}</span></div>`;
      });
      list.innerHTML = html;
      ctx.focus.enter(list);
    },
  };
}
