/** What the TV editions show for a newer build: where it is (address + QR) and how to install it. */
import type { Ctx } from './app';
import { t } from '../i18n/index';
import { esc } from '../core/text';
import { qrSvg } from './about';
import type { TvRelease } from '../update/update';

export function showUpdate(ctx: Ctx, r: TvRelease): void {
  const file = ctx.platform.id === 'lg' ? 'StreamX-LG.ipk' : ctx.platform.id === 'samsung' ? 'StreamX-Samsung.wgt' : '';
  const has = r.assets.includes(file);
  const body = [
    t('tv_update_where', r.page),
    has ? t('tv_update_file', file) : t('tv_update_unsigned'),
    t(ctx.platform.id === 'lg' ? 'tv_update_how_lg' : 'tv_update_how_samsung'),
  ].join('\n\n');
  void ctx.dialog({ title: t('update_available', r.build).split('·')[0].trim(), body, buttons: [['ok', t('player_back')]], html: `<div style="background:#fff;padding:10rem;border-radius:8rem;width:max-content">${qrSvg(r.page, 120)}</div>` });
}

export const updateBanner = (r: TvRelease) =>
  `<div class="row fx" data-k="update" style="height:42rem;padding:0 14rem;background:var(--tally-deep)"><span class="t-label" style="color:var(--text);white-space:nowrap">⬆  ${esc(t('update_available', r.build).split('·')[0].trim())}</span></div>`;
