/**
 * Remote-control focus, the web counterpart of Compose's focus system on TV.
 *
 * - Any element with class `fx` is focusable. The focused one gets class `focused`.
 * - Arrow keys move to the nearest focusable element in that direction (geometry of the screen,
 *   so it mirrors itself in Arabic).
 * - A container with `data-restore` remembers its last focused child: coming back into it lands on
 *   that child (Compose focusRestorer).
 * - `data-up/down/left/right="<css selector>|none"` on an element or one of its containers overrides
 *   the target in that direction (Compose focusProperties { up = ... }).
 * - The topmost element with `data-scope` (dialog, panel) traps the focus inside it.
 * - Containers with `data-scroll="x"|"y"` scroll so the focused element stays visible.
 */
export type Dir = 'up' | 'down' | 'left' | 'right';

const FOCUSABLE = '.fx';

function visible(el: Element): boolean {
  const r = (el as HTMLElement).getBoundingClientRect();
  if (r.width === 0 && r.height === 0) return false;
  for (let e: HTMLElement | null = el as HTMLElement; e; e = e.parentElement) {
    if (e.classList.contains('hidden')) return false;
  }
  return true;
}

export class FocusManager {
  current: HTMLElement | null = null;
  private readonly memory = new WeakMap<Element, HTMLElement>();

  constructor(private readonly root: HTMLElement) {}

  scope(): HTMLElement {
    const scopes = this.root.querySelectorAll<HTMLElement>('[data-scope]');
    for (let i = scopes.length - 1; i >= 0; i--) if (visible(scopes[i])) return scopes[i];
    return this.root;
  }

  candidates(scope: HTMLElement = this.scope()): HTMLElement[] {
    return Array.from(scope.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((e) => !e.hasAttribute('disabled') && visible(e));
  }

  focus(el: HTMLElement | null, opts: { silent?: boolean } = {}): boolean {
    if (!el) return false;
    if (this.current === el) { this.reveal(el); return true; }
    const prev = this.current;
    if (prev) {
      prev.classList.remove('focused');
      if (prev instanceof HTMLInputElement) prev.blur();
      prev.dispatchEvent(new CustomEvent('fx:blur', { bubbles: true }));
    }
    this.current = el;
    el.classList.add('focused');
    for (let p = el.parentElement; p && p !== this.root; p = p.parentElement) {
      if (p.hasAttribute('data-restore')) this.memory.set(p, el);
    }
    this.reveal(el);
    if (!opts.silent) el.dispatchEvent(new CustomEvent('fx:focus', { bubbles: true }));
    return true;
  }

  /** Focuses a container: its remembered child, else its first focusable child. */
  enter(container: Element | null): boolean {
    if (!container) return false;
    if ((container as HTMLElement).classList?.contains('fx') && visible(container)) return this.focus(container as HTMLElement);
    const remembered = this.memory.get(container);
    if (remembered && container.contains(remembered) && visible(remembered)) return this.focus(remembered);
    const first = this.candidates(container as HTMLElement)[0];
    return this.focus(first ?? null);
  }

  /** Focus the first element of the screen if nothing valid is focused. */
  ensure(): void {
    if (this.current && this.root.contains(this.current) && visible(this.current) && this.scope().contains(this.current)) return;
    const scope = this.scope();
    const preferred = scope.querySelector<HTMLElement>('[data-autofocus]');
    if (preferred && this.enter(preferred)) return;
    this.focus(this.candidates(scope)[0] ?? null);
  }

  private override(el: HTMLElement, dir: Dir): HTMLElement | 'none' | null {
    const attr = 'data-' + dir;
    for (let e: HTMLElement | null = el; e && e !== this.root; e = e.parentElement) {
      const v = e.getAttribute(attr);
      if (v == null) continue;
      if (v === 'none') return 'none';
      // An override on a container applies only when the move would leave that container.
      if (e !== el) { const t = this.nearest(el, dir); if (t && e.contains(t)) return null; }
      const target = this.root.querySelector<HTMLElement>(v);
      return target;
    }
    return null;
  }

  private nearest(from: HTMLElement, dir: Dir, within: HTMLElement = this.scope()): HTMLElement | null {
    const a = from.getBoundingClientRect();
    const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    let best: HTMLElement | null = null;
    let bestScore = Infinity;
    for (const c of this.candidates(within)) {
      if (c === from || from.contains(c)) continue;
      const b = c.getBoundingClientRect();
      const bx = b.left + b.width / 2, by = b.top + b.height / 2;
      let primary: number, secondary: number, overlap: boolean;
      switch (dir) {
        case 'up': primary = a.top - b.bottom; secondary = Math.abs(bx - ax); overlap = b.right > a.left && b.left < a.right; if (by >= ay - 1) continue; break;
        case 'down': primary = b.top - a.bottom; secondary = Math.abs(bx - ax); overlap = b.right > a.left && b.left < a.right; if (by <= ay + 1) continue; break;
        case 'left': primary = a.left - b.right; secondary = Math.abs(by - ay); overlap = b.bottom > a.top && b.top < a.bottom; if (bx >= ax - 1) continue; break;
        default: primary = b.left - a.right; secondary = Math.abs(by - ay); overlap = b.bottom > a.top && b.top < a.bottom; if (bx <= ax + 1) continue; break;
      }
      const score = Math.max(0, primary) + secondary * (overlap ? 0.5 : 3) + (overlap ? 0 : 1000);
      if (score < bestScore) { bestScore = score; best = c; }
    }
    return best;
  }

  move(dir: Dir): boolean {
    const from = this.current;
    if (!from || !this.root.contains(from)) { this.ensure(); return !!this.current; }
    const o = this.override(from, dir);
    if (o === 'none') return false;
    if (o) return this.enter(o);
    const target = this.nearest(from, dir);
    if (!target) return false;
    // Entering a remembering container from outside: land on its remembered child.
    for (let p = target.parentElement; p && p !== this.root; p = p.parentElement) {
      if (p.hasAttribute('data-restore') && !p.contains(from)) {
        const r = this.memory.get(p);
        if (r && p.contains(r) && visible(r)) return this.focus(r);
        break;
      }
    }
    return this.focus(target);
  }

  /** Keeps the focused element inside its scrolling containers. */
  reveal(el: HTMLElement): void {
    for (let p = el.parentElement; p && p !== this.root; p = p.parentElement) {
      const axis = p.getAttribute('data-scroll');
      if (!axis) continue;
      const pr = p.getBoundingClientRect();
      const er = el.getBoundingClientRect();
      const margin = Number(p.getAttribute('data-margin') ?? 48) * (window.innerWidth / 960);
      if (axis === 'y') {
        if (p.hasAttribute('data-top')) p.scrollTop += er.top - pr.top - margin;
        else if (er.top < pr.top + margin) p.scrollTop -= pr.top + margin - er.top;
        else if (er.bottom > pr.bottom - margin) p.scrollTop += er.bottom - (pr.bottom - margin);
      } else {
        if (er.left < pr.left + margin) p.scrollLeft -= pr.left + margin - er.left;
        else if (er.right > pr.right - margin) p.scrollLeft += er.right - (pr.right - margin);
      }
    }
  }
}
