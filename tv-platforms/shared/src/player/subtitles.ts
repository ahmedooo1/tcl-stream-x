/**
 * Subtitles drawn by the app: external .srt / .vtt files (public-domain films ship them next to the
 * video) and the text AVPlay hands over for embedded tracks (AVPlay does not draw them itself).
 * One overlay, one style, on both TV systems.
 */
export interface ExternalSub { url: string; language: string | null; mime: 'application/x-subrip' | 'text/vtt' }
export interface Cue { start: number; end: number; text: string }

const TIME = /(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})/;

function ms(s: string): number | null {
  const m = TIME.exec(s);
  if (!m) return null;
  return ((Number(m[1] ?? 0) * 60 + Number(m[2])) * 60 + Number(m[3])) * 1000 + Number(m[4].padEnd(3, '0'));
}

/** SRT and WebVTT share the "start --> end" line; numbering, headers, notes and styles are skipped. */
export function parseCues(raw: string): Cue[] {
  const out: Cue[] = [];
  const blocks = raw.replace(/^﻿/, '').replace(/\r\n?/g, '\n').split(/\n{2,}/);
  for (const b of blocks) {
    const lines = b.split('\n');
    const i = lines.findIndex((l) => l.includes('-->'));
    if (i < 0) continue;
    const [a, z] = lines[i].split('-->');
    const start = ms(a), end = ms(z);
    if (start == null || end == null || end <= start) continue;
    const text = lines.slice(i + 1).join('\n')
      .replace(/<[^>]+>/g, '') // <i>, <b>, <c.class>, voice tags
      .replace(/\{\\[^}]*\}/g, '') // SSA overrides left in some SRT files
      .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ')
      .trim();
    if (text) out.push({ start, end, text });
  }
  return out.sort((x, y) => x.start - y.start);
}

/** Cue showing at `t` (binary search; overlapping cues are joined). */
export function cueAt(cues: Cue[], t: number): string | null {
  let lo = 0, hi = cues.length - 1, found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (cues[mid].start <= t) { found = mid; lo = mid + 1; } else hi = mid - 1;
  }
  if (found < 0) return null;
  const lines: string[] = [];
  for (let i = found; i >= 0 && i > found - 4; i--) if (cues[i].start <= t && t < cues[i].end) lines.unshift(cues[i].text);
  return lines.length ? lines.join('\n') : null;
}

const MAX_BYTES = 2 * 1024 * 1024;

export async function loadCues(url: string, signal?: AbortSignal): Promise<Cue[]> {
  const res = await fetch(url, { signal, credentials: 'omit' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const buf = await res.arrayBuffer();
  if (buf.byteLength > MAX_BYTES) throw new Error('subtitle file too large');
  // Most files are UTF-8; old SRT files are often Windows-1252 / 1256 (Arabic).
  let text = new TextDecoder('utf-8').decode(buf);
  if (text.includes('�')) {
    for (const enc of ['windows-1256', 'windows-1252']) {
      try { text = new TextDecoder(enc).decode(buf); break; } catch { /* encoding not supported */ }
    }
  }
  return parseCues(text);
}
