/**
 * Sources, port of PlaylistsScreen.kt: built-in channels, M3U addresses and Xtream Codes accounts.
 * Each source can be refreshed or deleted; imports show their progress and their errors.
 */
import type { Ctx, Screen } from '../app';
import { h, onActivate } from '../dom';
import { t, Key } from '../../i18n/index';
import { esc } from '../../core/text';
import type { Playlist } from '../../storage/model';
import { ImportException, ImportProgress } from '../../core/importer';
import { parseSummary } from '../../xtream/client';
import { redact } from '../../xtream/urls';

const ERR: Record<string, string> = {
  NETWORK: 'player_issue_network', HTTP: 'player_issue_server', EMPTY: 'live_empty', TOO_LARGE: 'player_issue_format', FORMAT: 'player_issue_format',
  AUTH: 'player_issue_auth', EXPIRED: 'player_issue_auth', INACTIVE: 'player_issue_auth', TIMEOUT: 'player_issue_timeout', INVALID_URL: 'player_issue_not_found',
};

export function playlistsScreen(ctx: Ctx): Screen {
  const el = h(`<div class="screen pad" style="display:flex;flex-direction:column">
    <div class="t-display">${esc(t('playlists_title'))}</div>
    <div class="status t-body" style="min-height:30rem;margin:8rem 0"></div>
    <div data-scroll="y" data-restore class="body" style="flex:1;overflow:hidden"></div>
  </div>`);
  const body = el.querySelector<HTMLElement>('.body')!;
  const status = el.querySelector<HTMLElement>('.status')!;
  let busy = false;

  const date = (ms: number) => new Date(ms).toLocaleString(document.documentElement.lang === 'ar' ? 'ar' : 'fr-FR', { dateStyle: 'medium', timeStyle: 'short' });

  async function render() {
    const lists = await ctx.db.playlists();
    const item = (p: Playlist) => {
      const acc = parseSummary(p.accountSummary);
      const accLine = acc
        ? [t('xtream_status', acc.status), acc.expiresAt ? t('xtream_expires', new Date(acc.expiresAt * 1000).toLocaleDateString()) : t('xtream_no_expiry'),
          acc.maxConnections ? t('xtream_connections', acc.maxConnections) : null, acc.isTrial ? t('xtream_trial') : null].filter(Boolean).join(' · ')
        : '';
      return `<div class="row fx" data-k="p:${p.id}" style="height:auto;padding:12rem 14rem;align-items:flex-start">
        <div class="grow" style="display:flex;flex-direction:column;gap:2rem">
          <span class="t-title auto ellipsis">${esc(p.name)}</span>
          <span class="t-small">${esc(t('playlists_entries', p.count))}${p.updatedAt ? ' · ' + esc(t('playlists_updated', date(p.updatedAt))) : ''}</span>
          ${p.kind === 'builtin' ? `<span class="t-small">${esc(t('playlists_builtin_note'))}</span>` : ''}
          ${p.kind === 'url' && p.source ? `<span class="t-mono ellipsis">${esc(redact(p.source))}</span>` : ''}
          ${accLine ? `<span class="t-mono">${esc(accLine)}</span>` : ''}
        </div><span class="t-mono">${p.kind === 'xtream' ? 'Xtream' : p.kind === 'builtin' ? 'StreamX' : 'M3U'}</span></div>`;
    };
    body.innerHTML = `
      <div class="row fx" data-k="add-url" style="height:52rem"><span class="t-title">＋  ${esc(t('playlists_add_url'))}</span></div>
      <div class="row fx" data-k="add-xtream" style="height:52rem"><span class="t-title">＋  ${esc(t('xtream_title'))}</span></div>
      <div class="t-small" style="padding:6rem 14rem 14rem">${esc(t('playlists_legal'))}</div>
      ${lists.map(item).join('')}
      <div class="t-small" style="padding:14rem">${esc(t('tv_xtream_note'))}</div>
      <div class="t-small" style="padding:0 14rem 6rem">${esc(t('tv_lan_import'))}</div>
      <div class="t-small" style="padding:0 14rem 24rem">${esc(t('tv_lan_unavailable'))}</div>`;
  }

  const progress = (name: string) => (pr: ImportProgress) => { status.textContent = t('import_running', name, pr.count); };

  async function run(name: string, job: () => Promise<Playlist>) {
    if (busy) return;
    busy = true;
    status.textContent = t('tv_import_running');
    try {
      const p = await job();
      status.textContent = t('import_done', p.name, p.count, 0);
    } catch (e) {
      const code = e instanceof ImportException ? e.error : 'NETWORK';
      status.textContent = t('import_failed', name, t((ERR[code] ?? 'player_issue_unknown') as Key));
    } finally {
      busy = false;
      await render();
    }
  }

  async function ask(title: string, placeholder: string, type: 'text' | 'password' = 'text', value = ''): Promise<string | null> {
    const r = await ctx.dialog({ title, input: { placeholder, type, value }, buttons: [['ok', t('settings_save')], ['cancel', '×']] });
    if (!r || !r.startsWith('ok:')) return null;
    const v = r.substring(3).trim();
    return v === '' ? null : v;
  }

  onActivate(body, async (k) => {
    if (k === 'add-url') {
      const url = await ask(t('playlists_add_url'), t('playlists_url_hint'));
      if (!url) return;
      const name = (await ask(t('playlists_name_hint'), t('playlists_name_hint'))) ?? new URL(url, 'http://x').hostname;
      await run(name, () => ctx.importer.importM3u(name, { url }, undefined, progress(name)));
      return;
    }
    if (k === 'add-xtream') {
      const server = await ask(t('xtream_title'), t('xtream_server_hint'));
      if (!server) return;
      const user = await ask(t('xtream_user_hint'), t('xtream_user_hint'));
      if (!user) return;
      const pass = await ask(t('xtream_pass_hint'), t('xtream_pass_hint'), 'password');
      if (!pass) return;
      const name = (await ask(t('playlists_name_hint'), t('playlists_name_hint'))) ?? server;
      await run(name, () => ctx.importer.importXtream(name, server, { username: user, password: pass }, undefined, progress(name)));
      return;
    }
    if (k.startsWith('p:')) {
      const p = (await ctx.db.playlists()).find((x) => x.id === Number(k.substring(2)));
      if (!p) return;
      const buttons: [string, string][] = p.kind === 'builtin' ? [['cancel', '×']] : [['refresh', t('playlists_refresh')], ['delete', t('playlists_delete')], ['cancel', '×']];
      const r = await ctx.dialog({ title: p.name, buttons });
      if (r === 'refresh') await run(p.name, () => ctx.importer.refresh(p, progress(p.name)));
      if (r === 'delete') {
        const sure = await ctx.dialog({ title: p.name, body: t('playlists_delete') + ' ?', buttons: [['no', t('exit_stay')], ['yes', t('playlists_delete')]], focus: 'no' });
        if (sure === 'yes') { await ctx.importer.remove(p); await render(); }
      }
    }
  });

  return { el, mount() { void render().then(() => ctx.focus.enter(body)); } };
}
