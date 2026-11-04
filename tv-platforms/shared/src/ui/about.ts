/** Who made the app, with QR codes (a phone camera opens them: nobody types an address with a remote). */
import qrcode from 'qrcode-generator';
import { esc } from '../core/text';
import { t } from '../i18n/index';

export const DEVELOPER_NAME = 'Ahmad Ahmad';
export const DEVELOPER_SITE = 'https://ahmadahmad.aaweb.fr';
export const DEVELOPER_GITHUB = 'https://github.com/ahmedooo1';

export function qrSvg(text: string, sizeRem: number): string {
  const q = qrcode(0, 'M');
  q.addData(text);
  q.make();
  const n = q.getModuleCount();
  let path = '';
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (q.isDark(y, x)) path += `M${x + 1} ${y + 1}h1v1h-1z`;
  return `<svg viewBox="0 0 ${n + 2} ${n + 2}" style="width:${sizeRem}rem;height:${sizeRem}rem;background:#fff;border-radius:10rem;padding:6rem" shape-rendering="crispEdges"><path d="${path}" fill="#000"/></svg>`;
}

export function developerCard(): string {
  const block = (url: string, label: string) => `<div style="display:flex;flex-direction:column;align-items:center;gap:8rem">${qrSvg(url, 150)}<span class="t-label">${esc(label)}</span></div>`;
  return `<div style="display:flex;align-items:center;justify-content:space-between;background:var(--graphite);border:1rem solid var(--hairline);border-radius:18rem;padding:24rem">
    <div style="display:flex;flex-direction:column;gap:6rem">
      <span class="t-label">${esc(t('about_made_by'))}</span>
      <span class="t-display">${esc(DEVELOPER_NAME)}</span>
      <span class="t-body dim">${esc(t('about_dev_role'))}</span>
      <span class="t-mono tally" style="margin-top:8rem">${esc(DEVELOPER_SITE.replace('https://', ''))}</span>
      <span class="t-mono" style="color:var(--text)">${esc(DEVELOPER_GITHUB.replace('https://', ''))}</span>
    </div>
    <div style="display:flex;gap:18rem">${block(DEVELOPER_SITE, t('about_site'))}${block(DEVELOPER_GITHUB, 'GitHub')}</div>
  </div>`;
}
