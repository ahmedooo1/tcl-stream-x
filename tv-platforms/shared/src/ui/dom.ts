/** Small DOM helpers. All dynamic text goes through esc() (see core/text.ts). */
import { esc, initialsOf, isHttpUrl } from '../core/text';

export function h<T extends HTMLElement = HTMLElement>(html: string): T {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return t.content.firstElementChild as T;
}

export function on<K extends string>(el: Element, type: K, f: (e: CustomEvent & Event) => void): void {
  el.addEventListener(type, f as EventListener);
}

/** Calls `f` when the element (or a descendant with data-k) is activated with OK. */
export function onActivate(root: Element, f: (key: string, el: HTMLElement) => void): void {
  root.addEventListener('fx:click', (e) => {
    const t = (e.target as HTMLElement).closest<HTMLElement>('[data-k]');
    if (t && root.contains(t)) f(t.dataset.k!, t);
  });
}

export function onFocusIn(root: Element, f: (key: string, el: HTMLElement) => void): void {
  root.addEventListener('fx:focus', (e) => {
    const t = (e.target as HTMLElement).closest<HTMLElement>('[data-k]');
    if (t && root.contains(t)) f(t.dataset.k!, t);
  });
}

/** Channel logo, or the channel's initials when there is none or it fails to load. */
export function logoHtml(url: string | null, name: string, cls = ''): string {
  const ini = `<span class="ini">${esc(initialsOf(name))}</span>`;
  if (!isHttpUrl(url)) return `<div class="logo ${cls}">${ini}</div>`;
  return `<div class="logo ${cls}"><img loading="lazy" src="${esc(url)}" alt="" data-ini="${esc(initialsOf(name))}"></div>`;
}

export function img(url: string | null, cls = 'fill'): string {
  return isHttpUrl(url) ? `<img class="${cls}" loading="lazy" src="${esc(url)}" alt="" data-drop>` : '';
}

/**
 * Pictures that fail to load: logos become the channel's initials, posters disappear. One capturing
 * listener instead of inline handlers, so the page keeps a strict Content-Security-Policy.
 */
export function installImageFallback(root: HTMLElement): void {
  root.addEventListener('error', (e) => {
    const t = e.target as HTMLElement;
    if (!(t instanceof HTMLImageElement)) return;
    const ini = t.dataset.ini;
    if (ini != null) {
      const span = document.createElement('span');
      span.className = 'ini';
      span.textContent = ini;
      t.replaceWith(span);
    } else if (t.hasAttribute('data-drop')) {
      t.remove();
    }
  }, true);
}

export const clock = (ms: number) => new Date(ms).toLocaleTimeString(document.documentElement.lang === 'ar' ? 'ar' : 'fr-FR', { hour: '2-digit', minute: '2-digit' });

export function formatTime(ms: number): string {
  const s = Math.floor(ms / 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return s >= 3600 ? `${Math.floor(s / 3600)}:${p(Math.floor((s % 3600) / 60))}:${p(s % 60)}` : `${p(Math.floor(s / 60))}:${p(s % 60)}`;
}
