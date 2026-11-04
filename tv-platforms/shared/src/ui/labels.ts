/** Labels.kt: built-in catalog groups ("news:ar") read as "Information · العربية". */
import { t, Key } from '../i18n/index';

const BUILTIN = new Set(['news', 'sports', 'entertainment', 'kids', 'music', 'religious', 'documentary', 'movies', 'series']);
const LANG: Record<string, string> = {
  fr: 'Français', ar: 'العربية', en: 'English', es: 'Español', tr: 'Türkçe', it: 'Italiano',
  de: 'Deutsch', pt: 'Português', ku: 'Kurdî · کوردی', fa: 'فارسی',
};

const categoryLabel = (c: string) => t((BUILTIN.has(c) ? `cat_${c}` : 'cat_entertainment') as Key);

function languageLabel(code: string): string {
  if (LANG[code]) return LANG[code];
  try {
    const n = new Intl.DisplayNames([code], { type: 'language' }).of(code);
    if (n && n !== code) return n.charAt(0).toUpperCase() + n.slice(1);
  } catch { /* unknown code */ }
  return code.toUpperCase();
}

export function groupLabel(raw: string): string {
  const parts = raw.split(':');
  if (parts.length === 2 && BUILTIN.has(parts[0])) return `${categoryLabel(parts[0])} · ${languageLabel(parts[1])}`;
  if (BUILTIN.has(raw)) return categoryLabel(raw);
  return raw || t('group_none');
}
