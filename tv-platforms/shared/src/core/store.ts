/** Minimal observable value (StateFlow of the Android app). */
export class Store<T> {
  private readonly subs = new Set<(v: T) => void>();
  constructor(private v: T) {}
  get value(): T { return this.v; }
  set(v: T): void { this.v = v; this.subs.forEach((f) => f(v)); }
  update(f: (v: T) => T): void { this.set(f(this.v)); }
  subscribe(f: (v: T) => void, now = true): () => void {
    this.subs.add(f);
    if (now) f(this.v);
    return () => { this.subs.delete(f); };
  }
}

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
