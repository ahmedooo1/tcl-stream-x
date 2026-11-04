/**
 * Text helpers shared by the importers, the search and the UI.
 * Ported from the Android app (M3uParser.normalize, Labels.initialsOf, XtreamClient.cleanPlot).
 */

/** Every combining mark (Unicode Mn), like the Android version: Latin accents and Arabic diacritics. */
const COMBINING = /\p{Mn}+/gu;
const SPACES = /\s+/g;

/** Lowercase, accents and Arabic diacritics removed: "Télé Liban" and "tele liban" match. */
export function normalize(s: string): string {
  return s
    .normalize('NFD')
    .replace(COMBINING, '')
    .toLowerCase()
    .replace(/\u0640/g, ' ') // tatweel
    .replace(SPACES, ' ')
    .trim();
}

/** Lowercase without accents, for matching words in category names. */
export function fold(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(COMBINING, '').trim();
}

export function cap(s: string, max: number): string {
  return s.length <= max ? s : s.substring(0, max);
}

/**
 * Two letters for a missing picture. Sources put a language or quality tag before the title
 * ("AR: ...", "DE-CAM: ...", "|FR| ...", "[AR] ..."): the tag is skipped.
 */
export function initialsOf(name: string): string {
  const clean = name
    .trim()
    .replace(/^(\|[^|]{1,12}\||\[[^\]]{1,12}]|[A-Za-z0-9-]{2,10}\s*[:|]+)\s*/, '')
    .replace(/[^\p{L}\p{N} ]/gu, ' ')
    .trim();
  const words = clean.split(' ').filter((w) => w.length > 0);
  let letters: string;
  if (words.length >= 2) letters = words.slice(0, 2).map((w) => Array.from(w)[0]).join('');
  else if (words.length === 1) letters = Array.from(words[0]).slice(0, 2).join('');
  else letters = Array.from(name.trim()).slice(0, 2).join('');
  return letters.toUpperCase();
}

/**
 * Some panels escape their summaries twice or three times: the escapes are undone and the spacing
 * tidied, so the summary reads as text.
 */
export function cleanPlot(raw: string | null | undefined): string | null {
  if (raw == null || raw.trim() === '') return null;
  let t = raw;
  for (let i = 0; i < 4; i++) t = t.split('\\\\').join('\\');
  t = t
    .split('\\r\\n').join('\n')
    .split('\\n').join('\n')
    .split('\\r').join('\n')
    .split('\\t').join(' ')
    .split('\\"').join('"')
    .split("\\'").join("'")
    .split('\\/').join('/')
    .split('\\').join('')
    .split('\r').join('');
  t = t.replace(/[ \t]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  return t === '' ? null : t;
}

/** Escapes text for HTML: every string from a playlist or a panel is untrusted. */
export function esc(s: string | null | undefined): string {
  if (s == null) return '';
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Only http(s) addresses are used for pictures and streams (no javascript:, data:, file:). */
export function isHttpUrl(u: string | null | undefined): u is string {
  if (!u) return false;
  const l = u.slice(0, 8).toLowerCase();
  return l.startsWith('http://') || l.startsWith('https://');
}
