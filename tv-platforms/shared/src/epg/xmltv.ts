/**
 * Streaming XMLTV reader, port of XmltvParser.kt + EpgRepository.kt. The guide arrives in chunks;
 * each <programme> element is cut out and read on its own, only for the channels the user has and
 * inside a window of 3 h back to 36 h ahead, so a guide of hundreds of megabytes stays small.
 */
import type { Programme } from '../storage/model';
import { readTextStream } from '../core/jsonstream';
import { maybeGunzip } from '../core/importer';

const MAX_TITLE = 200;
const MAX_DESC = 1000;

/** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
function daysFromCivil(y0: number, m: number, d: number): number {
  const y = m <= 2 ? y0 - 1 : y0;
  const era = Math.trunc((y >= 0 ? y : y - 399) / 400);
  const yoe = y - era * 400;
  const doy = Math.trunc((153 * (m > 2 ? m - 3 : m + 9) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.trunc(yoe / 4) - Math.trunc(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** XMLTV time "yyyyMMddHHmmss +hhmm" (seconds and offset optional) → epoch millis, or null. */
export function parseXmltvTime(value: string): number | null {
  const v = value.trim();
  if (v.length < 12 || !/^\d{12}/.test(v)) return null;
  const year = +v.substring(0, 4), month = +v.substring(4, 6), day = +v.substring(6, 8);
  const hour = +v.substring(8, 10), minute = +v.substring(10, 12);
  const hasSec = v.length >= 14 && /\d\d/.test(v.substring(12, 14));
  const second = hasSec ? +v.substring(12, 14) : 0;
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return null;
  let offset = 0;
  const tz = v.substring(hasSec ? 14 : 12).trim();
  if (/^[+-]\d{4}$/.test(tz)) offset = (tz[0] === '-' ? -1 : 1) * (+tz.substring(1, 3) * 60 + +tz.substring(3, 5));
  const secs = daysFromCivil(year, month, day) * 86400 + hour * 3600 + minute * 60 + second - offset * 60;
  return secs * 1000;
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
export function decodeXml(s: string): string {
  return s.replace(/<!\[CDATA\[([\s\S]*?)]]>/g, '$1').replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e: string) => {
    if (e[0] === '#') {
      const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.substring(2), 16) : parseInt(e.substring(1), 10);
      return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : '';
    }
    return ENTITIES[e.toLowerCase()] ?? m;
  });
}

const attr = (tag: string, name: string): string | null => {
  const m = new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, 'i').exec(tag);
  return m ? decodeXml(m[2] ?? m[3] ?? '') : null;
};
const child = (body: string, name: string): string | null => {
  const m = new RegExp(`<${name}\\b[^>]*>([\\s\\S]*?)</${name}>`, 'i').exec(body);
  return m ? decodeXml(m[1]).replace(/\s+/g, ' ').trim() : null;
};

/** Incremental reader: push text chunks, programmes of wanted channels come out. */
export class XmltvReader {
  private buf = '';
  count = 0;

  constructor(
    private readonly wanted: Set<string>,
    private readonly from: number,
    private readonly to: number,
    private readonly onProgramme: (p: Programme) => void,
  ) {}

  push(chunk: string): void {
    this.buf += chunk;
    let pos = 0;
    for (;;) {
      const s = this.buf.indexOf('<programme', pos);
      if (s < 0) { pos = Math.max(pos, this.buf.length - 16); break; }
      const e = this.buf.indexOf('</programme>', s);
      if (e < 0) { pos = s; break; }
      this.element(this.buf.substring(s, e));
      pos = e + 12;
    }
    this.buf = this.buf.substring(pos);
    if (this.buf.length > 4_000_000) this.buf = ''; // runaway element: skipped
  }

  private element(el: string) {
    const close = el.indexOf('>');
    if (close < 0) return;
    const tag = el.substring(0, close);
    const channel = attr(tag, 'channel');
    if (!channel) return;
    const id = channel.toLowerCase();
    const base = id.split('@')[0];
    if (this.wanted.size > 0 && !this.wanted.has(id) && !this.wanted.has(base)) return;
    const start = parseXmltvTime(attr(tag, 'start') ?? '');
    const stop = parseXmltvTime(attr(tag, 'stop') ?? '');
    if (start == null || stop == null || stop <= start || stop < this.from || start > this.to) return;
    const body = el.substring(close + 1);
    const title = child(body, 'title');
    if (!title) return;
    const desc = child(body, 'desc');
    const key = this.wanted.has(id) ? id : base;
    this.count++;
    this.onProgramme({
      id: `${key}|${start}`,
      channelId: key,
      start,
      stop,
      title: title.substring(0, MAX_TITLE),
      description: desc ? desc.substring(0, MAX_DESC) : null,
    });
  }
}

export const DEFAULT_GUIDE = 'https://xmltvfr.fr/xmltv/xmltv.xml.gz';
export const PAST_WINDOW_MS = 3 * 3600_000;
export const FUTURE_WINDOW_MS = 36 * 3600_000;

/** Downloads one guide (gzip handled; zip guides are reported as unsupported on TV builds). */
export async function readGuide(url: string, reader: XmltvReader): Promise<number> {
  let res: Response;
  try { res = await fetch(url, { credentials: 'omit', cache: 'no-store' }); } catch { throw new Error('network'); }
  if (!res.ok) throw new Error('http ' + res.status);
  res = await maybeGunzip(res);
  await readTextStream(res, (t) => reader.push(t), 600 * 1024 * 1024);
  return reader.count;
}
