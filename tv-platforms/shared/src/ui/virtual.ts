/**
 * Virtualised list or grid (Paging 3 + LazyColumn/LazyVerticalGrid on Android): only the rows near
 * the visible area exist in the page, pages of items are read from the database on demand, so a
 * section of 50 000 titles costs the same as one of 50.
 */
export interface VirtualOptions<T> {
  /** Row height and column width in rem (dp). */
  rowHeight: number;
  columns?: number;
  gap?: number;
  total: number;
  pageSize?: number;
  load(offset: number, limit: number): Promise<T[]>;
  render(item: T, index: number): string;
  onFocus?(item: T, index: number): void;
  onActivate?(item: T, index: number): void;
  onLong?(item: T, index: number): void;
}

const remPx = () => window.innerWidth / 960;

export class VirtualList<T> {
  readonly el: HTMLElement;
  private readonly inner: HTMLElement;
  private readonly pages = new Map<number, T[] | Promise<void>>();
  private readonly rendered = new Map<number, HTMLElement>();
  private total: number;
  private frame = 0;
  private alive = true;

  constructor(private readonly o: VirtualOptions<T>) {
    this.total = o.total;
    this.el = document.createElement('div');
    this.el.className = 'vlist';
    this.el.setAttribute('data-scroll', 'y');
    this.el.setAttribute('data-restore', '');
    this.el.setAttribute('data-margin', String(o.rowHeight * 1.5));
    this.el.style.cssText = 'position:relative;overflow:hidden;height:100%';
    this.inner = document.createElement('div');
    this.inner.style.cssText = 'position:relative;width:100%';
    this.el.append(this.inner);
    this.el.addEventListener('scroll', () => this.schedule());
    this.el.addEventListener('fx:focus', (e) => {
      const i = Number((e.target as HTMLElement).closest<HTMLElement>('[data-i]')?.dataset.i);
      const item = this.item(i);
      if (item !== undefined) this.o.onFocus?.(item, i);
    });
    this.el.addEventListener('fx:click', (e) => {
      const i = Number((e.target as HTMLElement).closest<HTMLElement>('[data-i]')?.dataset.i);
      const item = this.item(i);
      if (item !== undefined) this.o.onActivate?.(item, i);
    });
    this.el.addEventListener('fx:long', (e) => {
      const i = Number((e.target as HTMLElement).closest<HTMLElement>('[data-i]')?.dataset.i);
      const item = this.item(i);
      if (item !== undefined) this.o.onLong?.(item, i);
    });
    this.layout();
  }

  private get cols() { return this.o.columns ?? 1; }
  private get page() { return this.o.pageSize ?? 120; }
  private rowPx() { return this.o.rowHeight * remPx(); }

  private layout() {
    const rows = Math.ceil(this.total / this.cols);
    this.inner.style.height = rows * this.rowPx() + 'px';
    this.schedule();
  }

  setTotal(n: number) { this.total = n; this.pages.clear(); this.rendered.forEach((e) => e.remove()); this.rendered.clear(); this.el.scrollTop = 0; this.layout(); }

  item(i: number): T | undefined {
    const p = this.pages.get(Math.floor(i / this.page));
    return Array.isArray(p) ? p[i % this.page] : undefined;
  }

  private ensurePage(n: number) {
    if (this.pages.has(n)) return;
    const pr = this.o.load(n * this.page, this.page).then((items) => {
      if (!this.alive) return;
      this.pages.set(n, items);
      this.schedule();
    }).catch(() => { this.pages.delete(n); });
    this.pages.set(n, pr);
  }

  private schedule() {
    if (this.frame) return;
    this.frame = requestAnimationFrame(() => { this.frame = 0; this.renderWindow(); });
  }

  /** Renders rows around the viewport (one screen above and below, so the focus can always move). */
  renderWindow() {
    const rowPx = this.rowPx();
    const h = this.el.clientHeight || window.innerHeight;
    const firstRow = Math.max(0, Math.floor(this.el.scrollTop / rowPx) - Math.ceil(h / rowPx));
    const lastRow = Math.min(Math.ceil(this.total / this.cols) - 1, Math.ceil((this.el.scrollTop + h) / rowPx) + Math.ceil(h / rowPx));
    const from = firstRow * this.cols;
    const to = Math.min(this.total - 1, (lastRow + 1) * this.cols - 1);
    for (let p = Math.floor(from / this.page); p <= Math.floor(Math.max(from, to) / this.page); p++) this.ensurePage(p);
    for (const [i, el] of this.rendered) {
      if (i < from || i > to) {
        if (el.contains(document.querySelector('.focused'))) continue; // never remove the focused row
        el.remove();
        this.rendered.delete(i);
      }
    }
    const gap = (this.o.gap ?? 0) * remPx();
    const colW = 100 / this.cols;
    for (let i = from; i <= to; i++) {
      if (this.rendered.has(i)) continue;
      const item = this.item(i);
      if (item === undefined) continue;
      const wrap = document.createElement('div');
      wrap.dataset.i = String(i);
      const r = Math.floor(i / this.cols), c = i % this.cols;
      wrap.style.cssText = `position:absolute;top:${r * rowPx}px;inset-inline-start:calc(${c * colW}% + ${gap / 2}px);width:calc(${colW}% - ${gap}px);height:${rowPx - gap}px`;
      wrap.innerHTML = this.o.render(item, i);
      this.inner.append(wrap);
      this.rendered.set(i, wrap);
    }
  }

  /** First focusable element of item i (rendered or not yet). */
  async focusIndex(i: number, focus: (el: HTMLElement) => void) {
    const rowPx = this.rowPx();
    this.el.scrollTop = Math.max(0, Math.floor(i / this.cols) * rowPx - rowPx);
    this.ensurePage(Math.floor(i / this.page));
    const p = this.pages.get(Math.floor(i / this.page));
    if (p instanceof Promise) await p;
    this.renderWindow();
    const el = this.rendered.get(i)?.querySelector<HTMLElement>('.fx');
    if (el) focus(el);
  }

  destroy() { this.alive = false; if (this.frame) cancelAnimationFrame(this.frame); }
}
