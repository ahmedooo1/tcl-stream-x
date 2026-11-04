/** RegionStrip.kt: "all" first, then the countries and languages of the library, most represented first. */
import { getLang, t } from '../i18n/index';
import { esc } from '../core/text';

export function regionLabel(code: string): string {
  if (code === 'ku') return 'Kurdî · کوردی';
  const ui = getLang() === 'ar' ? 'ar' : 'fr';
  try {
    const isCountry = code.length > 0 && code[0] === code[0].toUpperCase() && code[0] !== code[0].toLowerCase();
    const n = new Intl.DisplayNames([ui], { type: isCountry ? 'region' : 'language' }).of(code);
    if (n && n !== code) return n.charAt(0).toLocaleUpperCase(ui) + n.slice(1);
  } catch { /* unknown code */ }
  return code;
}

export function regionStrip(regions: { code: string; count: number }[] | undefined, selected: string | null): string {
  if (!regions || regions.length === 0) return '';
  const chip = (k: string, label: string, on: boolean) => `<div class="chip fx${on ? ' selected' : ''}" data-k="${esc(k)}">${esc(label)}</div>`;
  return chip('region:', t('region_all'), selected == null)
    + regions.map((r) => chip(`region:${r.code}`, `${regionLabel(r.code)}  ${r.count}`, selected === r.code)).join('');
}
