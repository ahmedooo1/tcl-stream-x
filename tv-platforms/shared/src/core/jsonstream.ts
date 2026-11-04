/**
 * Streaming reader for the top-level JSON array a panel returns (get_live_streams, get_vod_streams:
 * tens of megabytes). Text arrives in chunks; each element of the array is parsed on its own and
 * handed out, so only one entry is held at a time instead of the whole document.
 * Port of the idea of JsonStream.kt (one entry in memory at a time).
 */
export class JsonArrayStream {
  private buf = '';
  private depth = 0;
  private inString = false;
  private escape = false;
  private elementStart = -1;
  private started = false;
  private done = false;
  /** Panels sometimes answer an object ({"user_info":...} or {} on error) instead of an array. */
  private objectRoot = false;
  private rootText = '';
  count = 0;

  constructor(private readonly onElement: (value: unknown) => void, private readonly maxElement = 2_000_000) {}

  push(chunk: string): void {
    if (this.done) return;
    if (this.objectRoot) { this.rootText += chunk; return; }
    let text = this.buf + chunk;
    let i = this.buf.length;
    // Absolute positions are kept relative to `text`; consumed prefixes are dropped below.
    for (; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (!this.started) {
        if (c === 0x5b /* [ */) { this.started = true; this.depth = 1; continue; }
        if (c === 0x7b /* { */) { this.objectRoot = true; this.rootText = text.substring(i); this.buf = ''; return; }
        continue; // whitespace or BOM before the array
      }
      if (this.inString) {
        if (this.escape) this.escape = false;
        else if (c === 0x5c) this.escape = true;
        else if (c === 0x22) this.inString = false;
        continue;
      }
      if (c === 0x22) {
        this.inString = true;
        if (this.depth === 1 && this.elementStart < 0) this.elementStart = i;
        continue;
      }
      if (c === 0x7b || c === 0x5b) {
        if (this.depth === 1 && this.elementStart < 0) this.elementStart = i;
        this.depth++;
        continue;
      }
      if (c === 0x7d || c === 0x5d) {
        this.depth--;
        if (this.depth === 1 && this.elementStart >= 0) {
          this.emit(text.substring(this.elementStart, i + 1));
          this.elementStart = -1;
        } else if (this.depth === 0) {
          this.flushScalar(text, i);
          this.done = true;
          this.buf = '';
          return;
        }
        continue;
      }
      if (this.depth === 1) {
        if (c === 0x2c /* , */) { this.flushScalar(text, i); continue; }
        if (this.elementStart < 0 && c > 0x20) this.elementStart = i; // number / true / null element
      }
    }
    if (this.elementStart >= 0) {
      if (text.length - this.elementStart > this.maxElement) throw new Error('JSON element too large');
      this.buf = text.substring(this.elementStart);
      this.elementStart = 0;
    } else {
      this.buf = '';
    }
    text = '';
  }

  private flushScalar(text: string, end: number) {
    if (this.elementStart >= 0) {
      const raw = text.substring(this.elementStart, end).trim();
      if (raw !== '') this.emit(raw);
      this.elementStart = -1;
    }
  }

  private emit(raw: string) {
    try {
      this.onElement(JSON.parse(raw));
      this.count++;
    } catch {
      // a malformed element is skipped, the rest of the list still loads
    }
  }

  /** Returns the parsed root object when the answer was an object instead of an array. */
  end(): unknown {
    if (this.objectRoot) {
      try { return JSON.parse(this.rootText); } catch { return null; }
    }
    return null;
  }
}

/** Reads a fetch Response body as text chunks, without holding the whole body. */
export async function readTextStream(res: Response, onChunk: (s: string) => void, maxBytes = 300 * 1024 * 1024): Promise<number> {
  const body = res.body;
  if (!body) {
    const t = await res.text();
    if (t.length > maxBytes) throw new Error('too large');
    onChunk(t);
    return t.length;
  }
  const reader = body.getReader();
  const dec = new TextDecoder('utf-8');
  let total = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) { await reader.cancel(); throw new Error('too large'); }
    onChunk(dec.decode(value, { stream: true }));
  }
  const tail = dec.decode();
  if (tail) onChunk(tail);
  return total;
}
