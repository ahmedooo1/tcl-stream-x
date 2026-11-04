/**
 * Settings, port of SettingsScreen.kt: language, preview, daily refresh, guide, parental code,
 * history, about (with the developer card) and version.
 */
import { systemLang } from '../app';
import type { Ctx, Screen } from '../app';
import { h, onActivate } from '../dom';
import { groupLabel } from '../labels';
import { t } from '../../i18n/index';
import { esc } from '../../core/text';
import { S } from '../../storage/db';
import { developerCard } from '../about';
import { Pin } from '../../security/pin';
import { showUpdate } from '../updateinfo';
import type { LockedGroup } from '../../storage/model';

export function settingsScreen(ctx: Ctx): Screen {
  const el = h(`<div class="screen pad" style="display:flex;flex-direction:column">
    <div class="t-display" style="margin-bottom:8rem">${esc(t('settings_title'))}</div>
    <div class="body" data-scroll="y" data-restore style="flex:1;overflow:hidden"></div></div>`);
  const body = el.querySelector<HTMLElement>('.body')!;
  const pin = new Pin(ctx.db);
  let confirmHistory = false;
  let checking = false;
  let updateFailed = false;
  let unsub: (() => void) | null = null;

  const section = (title: string) => `<div class="t-headline" style="margin:22rem 0 6rem">${esc(title)}</div>`;
  const row = (k: string, label: string, value = '', valueClass = '') =>
    `<div class="row fx" data-k="${k}" style="height:50rem"><span class="grow t-body auto">${esc(label)}</span>${value ? `<span class="t-mono ${valueClass}">${esc(value)}</span>` : ''}</div>`;
  const toggle = (k: string, label: string, on: boolean) => row(k, label, t(on ? 'on' : 'off'), on ? 'ok' : 'faint');

  async function render() {
    const s = ctx.settings.value;
    const programmes = await ctx.db.count(S.programmes, null, null);
    const pinSet = await pin.isSet();
    const locks = await ctx.db.locks();
    const open = locks.length > 0 && locks.every((l) => l.hidden === 0);
    const e = ctx.epg.state.value;
    const u = ctx.update.value;
    const updateLine = checking ? t('update_checking') : u ? t('update_available', u.build) : updateFailed ? t('update_failed_network') : `${t('update_none', __APP_BUILD__)} · ${t('update_check')}`;
    const epgLine = e.kind === 'loading' ? t('settings_epg_loading') : e.kind === 'done' ? t('settings_epg_done', e.programmes, e.sources, e.failed) : t('settings_epg_count', programmes);
    body.innerHTML = `
      ${section(t('settings_language'))}
      ${row('lang:', t('settings_language_system'), s.langChoice === '' ? '●' : '', 'tally')}
      ${row('lang:fr', 'Français', s.langChoice === 'fr' ? '●' : '', 'tally')}
      ${row('lang:ar', 'العربية', s.langChoice === 'ar' ? '●' : '', 'tally')}
      ${section(t('nav_live'))}
      ${toggle('preview', t('settings_preview'), s.previewOnFocus)}
      ${toggle('autorefresh', t('settings_autorefresh'), s.autoRefresh)}
      ${toggle('autocheck', t('settings_autocheck'), s.autoCheckBuiltin)}
      ${section(t('settings_subtitles'))}
      ${row('subsize', t('settings_subtitle_size'), `${Math.round((s.subtitleScale || 1) * 100)} %`)}
      ${row('substyle', t('settings_subtitle_style'), t((['subtitle_style_white', 'subtitle_style_yellow', 'subtitle_style_box'] as const)[Math.min(2, Math.max(0, s.subtitleStyle))]))}
      ${section(t('settings_epg'))}
      ${row('epgurl', s.epgUrl || t('settings_epg_hint'))}
      ${row('epgrefresh', t('settings_epg_refresh'), epgLine)}
      ${section(t('settings_parental'))}
      <div class="t-small" style="padding:0 14rem 6rem">${esc(t('parental_explain'))}</div>
      ${pinSet
        ? `${row('lock', locks.length ? t(open ? 'parental_open' : 'parental_locked', locks.length) : t('parental_lock_now'), open ? t('parental_lock_now') : t('tv_unlock'))}
           ${locks.map((l: LockedGroup) => row('unlockgroup:' + l.groupTitle, groupLabel(l.groupTitle), t('parental_remove'))).join('')}
           ${row('lockgroup', '＋  ' + t('parental_group_locked'))}
           ${row('clearpin', t('parental_clear'))}`
        : row('setpin', t('tv_set_pin'))}
      ${section(t('settings_history'))}
      ${row('history', confirmHistory ? t('settings_history_confirm') : t('settings_history_clear'))}
      ${section(t('update_title'))}
      ${row('update', updateLine, '')}
      ${section(t('settings_about'))}
      <div style="margin:6rem 0 12rem">${developerCard()}</div>
      <div class="t-body dim" style="padding:0 14rem">${esc(t('settings_about_body'))}</div>
      <div class="t-mono" style="padding:10rem 14rem">${esc(ctx.platform.label)} · ${esc(t('settings_version', ctx.platform.version))} · ${esc(ctx.platform.deviceInfo())}</div>
      <div class="t-small" style="padding:0 14rem 6rem">${esc(t('tv_storage_note'))}</div>
      <div class="t-small" style="padding:0 14rem 40rem">${esc(t('tv_updates_store'))}</div>`;
  }

  async function askPin(title: string): Promise<string | null> {
    const r = await ctx.dialog({ title, input: { placeholder: t('parental_set_hint'), type: 'password' }, buttons: [['ok', t('settings_save')], ['cancel', '×']] });
    if (!r?.startsWith('ok:')) return null;
    const v = r.substring(3).trim();
    return /^\d{4,8}$/.test(v) ? v : null;
  }

  onActivate(body, async (k) => {
    const keep = k;
    if (k.startsWith('lang:')) {
      const choice = k.substring(5) as '' | 'fr' | 'ar';
      await ctx.saveSettings({ langChoice: choice, lang: choice || systemLang() });
    }
    else if (k === 'update') {
      const u = ctx.update.value;
      if (u) { showUpdate(ctx, u); return; }
      checking = true; await render();
      const r = await ctx.checkUpdate();
      checking = false; updateFailed = r === 'error';
      if (r && r !== 'error') showUpdate(ctx, r);
    }
    else if (k === 'autocheck') await ctx.saveSettings({ autoCheckBuiltin: !ctx.settings.value.autoCheckBuiltin });
    else if (k === 'subsize') {
      const scales = [0.8, 1, 1.25, 1.5, 1.8];
      const i = scales.indexOf(ctx.settings.value.subtitleScale || 1);
      await ctx.saveSettings({ subtitleScale: scales[(i + 1) % scales.length] });
    } else if (k === 'substyle') await ctx.saveSettings({ subtitleStyle: ((ctx.settings.value.subtitleStyle || 0) + 1) % 3 });
    else if (k === 'preview') await ctx.saveSettings({ previewOnFocus: !ctx.settings.value.previewOnFocus });
    else if (k === 'autorefresh') await ctx.saveSettings({ autoRefresh: !ctx.settings.value.autoRefresh });
    else if (k === 'epgurl') {
      const r = await ctx.dialog({ title: t('settings_epg'), input: { placeholder: t('settings_epg_hint'), value: ctx.settings.value.epgUrl }, buttons: [['ok', t('settings_save')], ['cancel', '×']] });
      if (r?.startsWith('ok:')) {
        const v = r.substring(3).trim();
        if (v === '' || /^https?:\/\//i.test(v)) await ctx.saveSettings({ epgUrl: v });
      }
    } else if (k === 'epgrefresh') {
      void ctx.epg.refresh(ctx.settings.value.epgUrl).then(() => ctx.saveSettings({ lastEpgRefresh: Date.now() }));
    } else if (k === 'setpin') {
      const p = await askPin(t('parental_set_hint'));
      if (p) { await pin.set(p); await pin.lockAdultLooking(); ctx.toast(t('tv_pin_set')); }
    } else if (k === 'lock') {
      const locks = await ctx.db.locks();
      const open = locks.length > 0 && locks.every((l) => l.hidden === 0);
      if (open) await ctx.db.setLocksHidden(1);
      else {
        const p = await askPin(t('parental_unlock_hint'));
        if (p && await pin.check(p)) await ctx.db.setLocksHidden(0); else if (p) ctx.toast(t('parental_wrong'));
      }
    } else if (k === 'lockgroup') {
      const r = await ctx.dialog({ title: t('parental_group_locked'), input: { placeholder: t('search_categories') }, buttons: [['ok', t('settings_save')], ['cancel', '×']] });
      if (r?.startsWith('ok:') && r.length > 3) await ctx.db.put<LockedGroup>(S.locks, { groupTitle: r.substring(3).trim(), hidden: 1 });
    } else if (k.startsWith('unlockgroup:')) {
      const p = await askPin(t('parental_unlock_hint'));
      if (p && await pin.check(p)) await ctx.db.delete(S.locks, k.substring(12)); else if (p) ctx.toast(t('parental_wrong'));
    } else if (k === 'clearpin') {
      const p = await askPin(t('parental_unlock_hint'));
      if (p && await pin.check(p)) await pin.clear(); else if (p) ctx.toast(t('parental_wrong'));
    } else if (k === 'history') {
      if (!confirmHistory) { confirmHistory = true; }
      else {
        confirmHistory = false;
        for (const h of await ctx.db.all<{ channelKey: string }>(S.history)) await ctx.db.delete(S.history, h.channelKey);
        for (const p of await ctx.db.all<{ key: string }>(S.progress)) await ctx.db.delete(S.progress, p.key);
        await ctx.db.setSetting('lastChannel', '');
        ctx.toast(t('settings_history_done'));
      }
    }
    if (k.startsWith('lang:') && !document.contains(el)) return; // rebuilt in the new language
    await render();
    ctx.focus.focus(body.querySelector<HTMLElement>(`[data-k="${CSS.escape(keep)}"]`));
  });

  return {
    el,
    mount() {
      void render().then(() => ctx.focus.enter(body));
      unsub = ctx.epg.state.subscribe(() => { if (body.childElementCount) { const k = ctx.focus.current?.dataset.k; void render().then(() => { if (k) ctx.focus.focus(body.querySelector<HTMLElement>(`[data-k="${CSS.escape(k)}"]`)); }); } }, false);
    },
    unmount() { unsub?.(); },
  };
}
