/**
 * Legal video sources of the Films / Séries / Animation shelves (port of VodRepository.kt):
 *  - Internet Archive collections of public-domain and freely licensed films (official public
 *    API, no key): one search per genre and page, files listed on demand
 *  - openly licensed films bundled as a list (open_media.json, same file as the Android app)
 */
import type { ExternalSub } from '../player/subtitles';

export type VodShelf = 'movies' | 'series' | 'animation';

/** Subsections of the public-domain shelves, as fixed search clauses (never user text). */
export const GENRES: { id: string; shelf: VodShelf; clause: string | null }[] = [
  { id: 'MOVIES_ALL', shelf: 'movies', clause: null },
  { id: 'COMEDY', shelf: 'movies', clause: 'subject:(comedy)' },
  { id: 'DRAMA', shelf: 'movies', clause: 'subject:(drama)' },
  { id: 'HORROR', shelf: 'movies', clause: 'subject:(horror)' },
  { id: 'SCIFI', shelf: 'movies', clause: 'subject:("science fiction" OR sci-fi OR scifi)' },
  { id: 'WESTERN', shelf: 'movies', clause: 'subject:(western)' },
  { id: 'CRIME', shelf: 'movies', clause: 'subject:("film noir" OR noir OR crime)' },
  { id: 'WAR', shelf: 'movies', clause: 'subject:(war)' },
  { id: 'SILENT', shelf: 'movies', clause: 'subject:(silent)' },
  { id: 'SERIES_ALL', shelf: 'series', clause: null },
  { id: 'SITCOM', shelf: 'series', clause: 'subject:(comedy OR sitcom)' },
  { id: 'MYSTERY', shelf: 'series', clause: 'subject:(mystery OR detective OR crime)' },
  { id: 'TV_WESTERN', shelf: 'series', clause: 'subject:(western)' },
  { id: 'TV_SCIFI', shelf: 'series', clause: 'subject:("science fiction" OR sci-fi OR scifi)' },
  { id: 'ADVENTURE', shelf: 'series', clause: 'subject:(adventure)' },
  { id: 'KIDS', shelf: 'series', clause: 'subject:(children OR kids)' },
  { id: 'ANIMATION_ALL', shelf: 'animation', clause: null },
  { id: 'FLEISCHER', shelf: 'animation', clause: 'title:(popeye OR superman OR "betty boop")' },
  { id: 'LOONEY', shelf: 'animation', clause: 'title:(looney OR merrie OR "bugs bunny" OR daffy OR porky)' },
  { id: 'FELIX', shelf: 'animation', clause: 'title:(felix)' },
];
export const genresOf = (shelf: VodShelf) => GENRES.filter((g) => g.shelf === shelf);
export type Genre = (typeof GENRES)[number];

export interface VodItem {
  key: string;
  title: string;
  year: string | null;
  poster: string | null;
  description: string | null;
  license: string | null;
  archiveId?: string;
  directUrl?: string;
}

export interface VodFile { title: string; url: string; durationSec: number | null; subtitles: ExternalSub[] }

export interface OpenFilm { key: string; title: string; year: number; license: string; kind: string; url: string; poster?: string | null }
export interface OpenMedia { films: OpenFilm[] }

export const PAGE_SIZE = 40;
const SHELF_TTL_MS = 30 * 60_000;
const IDENTIFIER = /^[A-Za-z0-9._-]{1,100}$/;
const BASE: Record<VodShelf, string> = {
  movies: 'collection:(feature_films) AND mediatype:(movies)',
  series: 'collection:(classic_tv) AND mediatype:(movies)',
  animation: 'collection:(classic_cartoons) AND mediatype:(movies)',
};

/** Keeps letters, digits and spaces so user text cannot change the search syntax. */
export function sanitize(q: string): string {
  return (Array.from(q).filter((c) => /[\p{L}\p{N} ]/u.test(c)).join('').trim().slice(0, 60)) || '*';
}

export function searchUrl(shelf: VodShelf, page: number, query: string | null, genre: Genre | null): string {
  const withGenre = genre?.clause ? `${BASE[shelf]} AND ${genre.clause}` : BASE[shelf];
  const q = query && query.trim() ? `${withGenre} AND title:(${sanitize(query)})` : withGenre;
  const p = new URLSearchParams();
  p.set('q', q);
  for (const f of ['identifier', 'title', 'year', 'description', 'licenseurl']) p.append('fl[]', f);
  p.append('sort[]', 'downloads desc');
  p.set('rows', String(PAGE_SIZE));
  p.set('page', String(Math.max(1, page)));
  p.set('output', 'json');
  return `https://archive.org/advancedsearch.php?${p.toString()}`;
}

type Obj = Record<string, unknown>;
const str = (o: Obj, k: string): string | null => {
  const v = o[k];
  if (typeof v === 'string') return v;
  if (typeof v === 'number') return String(v);
  if (Array.isArray(v) && (typeof v[0] === 'string' || typeof v[0] === 'number')) return String(v[0]);
  return null;
};

export function parseSearch(root: unknown): VodItem[] {
  const docs = ((root as Obj | null)?.['response'] as Obj | undefined)?.['docs'];
  if (!Array.isArray(docs)) return [];
  const out: VodItem[] = [];
  for (const d of docs) {
    if (!d || typeof d !== 'object') continue;
    const o = d as Obj;
    const id = str(o, 'identifier');
    if (!id || !IDENTIFIER.test(id)) continue;
    out.push({
      key: `ia:${id}`,
      title: str(o, 'title') ?? id,
      year: str(o, 'year'),
      poster: `https://archive.org/services/img/${id}`,
      description: str(o, 'description')?.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 600) || null,
      license: str(o, 'licenseurl'),
      archiveId: id,
    });
  }
  return out;
}

const downloadUrl = (id: string, name: string) =>
  `https://archive.org/download/${id}/` + name.split('/').map((p) => encodeURIComponent(p)).join('/');

const formatRank = (f: string | null) => {
  switch ((f ?? '').toLowerCase()) {
    case 'h.264': case 'h.264 ia': return 0;
    case 'mpeg4': return 1;
    case '512kb mpeg4': return 2;
    default: return 3;
  }
};

const languageFromName = (name: string) => /[._-]([a-z]{2,3})\.(srt|vtt)$/i.exec(name)?.[1].toLowerCase() ?? null;

/** Playable MP4 files of an item, one per original, with the subtitle files of the item. */
export function parseFiles(identifier: string, root: unknown): VodFile[] {
  const files = ((root as Obj | null)?.['files']);
  if (!Array.isArray(files)) return [];
  const rows = files.filter((f): f is Obj => !!f && typeof f === 'object');
  const subs: ExternalSub[] = [];
  for (const f of rows) {
    const name = str(f, 'name');
    if (!name) continue;
    const l = name.toLowerCase();
    if (l.endsWith('.srt')) subs.push({ url: downloadUrl(identifier, name), language: languageFromName(name), mime: 'application/x-subrip' });
    else if (l.endsWith('.vtt')) subs.push({ url: downloadUrl(identifier, name), language: languageFromName(name), mime: 'text/vtt' });
  }
  const groups = new Map<string, Obj[]>();
  for (const f of rows) {
    const name = str(f, 'name') ?? '';
    if (!name.toLowerCase().endsWith('.mp4') || str(f, 'source') === 'metadata') continue;
    const orig = (str(f, 'original') ?? name).replace(/\.[^.]*$/, '');
    groups.set(orig, [...(groups.get(orig) ?? []), f]);
  }
  return [...groups.values()]
    .map((g) => g.slice().sort((a, b) => formatRank(str(a, 'format')) - formatRank(str(b, 'format')))[0])
    .sort((a, b) => (str(a, 'name')! < str(b, 'name')! ? -1 : 1))
    .map((f) => {
      const name = str(f, 'name')!;
      const len = Number(str(f, 'length'));
      return {
        title: str(f, 'title') ?? name.replace(/\.[^.]*$/, '').replace(/_/g, ' '),
        url: downloadUrl(identifier, name),
        durationSec: Number.isFinite(len) && len > 0 ? len : null,
        subtitles: subs,
      };
    });
}

async function getJson(url: string): Promise<unknown> {
  const res = await fetch(url, { credentials: 'omit' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export class Archive {
  private cache = new Map<string, { at: number; items: VodItem[] }>();

  async shelf(shelf: VodShelf, page: number, query: string | null, genre: Genre | null): Promise<VodItem[]> {
    const key = `${shelf}|${page}|${query ?? ''}|${genre?.id ?? ''}`;
    const hit = this.cache.get(key);
    if (hit && Date.now() - hit.at < SHELF_TTL_MS) return hit.items;
    const items = parseSearch(await getJson(searchUrl(shelf, page, query, genre)));
    this.cache.set(key, { at: Date.now(), items });
    if (this.cache.size > 80) this.cache.delete(this.cache.keys().next().value as string);
    return items;
  }

  async files(identifier: string): Promise<VodFile[]> {
    if (!IDENTIFIER.test(identifier)) throw new Error('bad identifier');
    return parseFiles(identifier, await getJson(`https://archive.org/metadata/${identifier}`));
  }
}

export function openFilms(media: OpenMedia, shelf: VodShelf): VodItem[] {
  return media.films
    .filter((f) => (shelf === 'movies' ? f.kind === 'film' || f.kind === 'demo' : shelf === 'animation' ? f.kind === 'animation' : false))
    .map((f) => ({ key: f.key, title: f.title, year: String(f.year), poster: f.poster ?? null, description: null, license: f.license, directUrl: f.url }));
}
