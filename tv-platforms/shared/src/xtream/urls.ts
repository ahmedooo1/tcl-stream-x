/**
 * Xtream Codes URL helpers, port of XtreamUrls.kt.
 *
 * Playable panel addresses embed the username and password in the path. They are never stored: the
 * database keeps `xtream://<playlist>/<type>/<id>.<ext>` references, turned into a real address
 * only in memory, at the moment the player or the guide needs it.
 */

export interface XtreamCredentials {
  readonly username: string;
  readonly password: string;
}

export type XtreamRef = 'live' | 'movie' | 'series' | 'episode' | 'epg' | 'timeshift';
const REFS: XtreamRef[] = ['live', 'movie', 'series', 'episode', 'epg', 'timeshift'];

export interface ParsedRef {
  playlistId: number;
  ref: XtreamRef;
  id: string;
  extension: string | null;
}

export const SCHEME = 'xtream://';

export const isInternal = (url: string) => url.startsWith(SCHEME);

export function makeRef(playlistId: number, ref: XtreamRef, id: string, extension?: string | null): string {
  return SCHEME + playlistId + '/' + ref + '/' + id + (extension ? '.' + extension : '');
}

const ID_OK = /^[\p{L}\p{N}_-]+$/u;
const EXT_OK = /^[\p{L}\p{N}]+$/u;

export function parseRef(url: string): ParsedRef | null {
  if (!isInternal(url)) return null;
  const parts = url.substring(SCHEME.length).split('/');
  if (parts.length !== 3) return null;
  const pid = Number(parts[0]);
  if (!Number.isInteger(pid)) return null;
  const ref = REFS.find((r) => r === parts[1]);
  if (!ref) return null;
  const last = parts[2];
  const dot = last.lastIndexOf('.');
  const id = dot > 0 ? last.substring(0, dot) : last;
  const ext = dot > 0 ? last.substring(dot + 1) : null;
  if (id === '' || !ID_OK.test(id)) return null;
  if (ext != null && !EXT_OK.test(ext)) return null;
  return { playlistId: pid, ref, id, extension: ext };
}

/**
 * Accepts what people paste: `host:port`, `http://host:port/`, the player_api.php address or a full
 * `get.php?...` link. Returns the base URL (scheme, host, port, optional path prefix) or null.
 */
export function normalizeServer(input: string): string | null {
  let s = input.trim();
  if (s === '' || /\s/.test(s)) return null;
  if (!/^https?:\/\//i.test(s)) {
    if (s.includes('://')) return null;
    s = 'http://' + s;
  }
  let u: URL;
  try { u = new URL(s); } catch { return null; }
  const scheme = u.protocol.replace(':', '').toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return null;
  if (!u.hostname) return null;
  let path = u.pathname;
  for (const ep of ['player_api.php', 'get.php', 'xmltv.php', 'panel_api.php']) {
    if (path.endsWith('/' + ep)) path = path.substring(0, path.length - ep.length);
  }
  path = path.replace(/\/+$/, '');
  const port = u.port ? ':' + u.port : '';
  return `${scheme}://${u.hostname}${port}${path}`;
}

/** Server and login found in a pasted `get.php?username=…&password=…` link, if it is one. */
export function fromPlaylistLink(link: string): { server: string; credentials: XtreamCredentials } | null {
  let u: URL;
  try { u = new URL(link.trim()); } catch { return null; }
  if (!u.pathname.endsWith('/get.php') && !u.pathname.endsWith('/player_api.php')) return null;
  const user = u.searchParams.get('username');
  const pass = u.searchParams.get('password');
  if (!user || !pass) return null;
  const server = normalizeServer(link);
  if (!server) return null;
  return { server, credentials: { username: user, password: pass } };
}

const q = (s: string) => encodeURIComponent(s);

export function apiUrl(server: string, c: XtreamCredentials, action?: string, extra: Record<string, string> = {}): string {
  let s = `${server}/player_api.php?username=${q(c.username)}&password=${q(c.password)}`;
  if (action) s += `&action=${q(action)}`;
  for (const [k, v] of Object.entries(extra)) s += `&${q(k)}=${q(v)}`;
  return s;
}

export const epgUrl = (server: string, c: XtreamCredentials) =>
  `${server}/xmltv.php?username=${q(c.username)}&password=${q(c.password)}`;

/** Real address of a playable reference; null for references that are not playable (a series). */
export function resolveRef(parsed: ParsedRef, server: string, c: XtreamCredentials): string | null {
  const auth = '/' + q(c.username) + '/' + q(c.password) + '/';
  const ext = parsed.extension ? '.' + parsed.extension : '';
  switch (parsed.ref) {
    case 'live': return `${server}/live${auth}${parsed.id}${ext}`;
    case 'movie': return `${server}/movie${auth}${parsed.id}${ext}`;
    case 'episode': return `${server}/series${auth}${parsed.id}${ext}`;
    case 'epg': return epgUrl(server, c);
    case 'series': return null;
    case 'timeshift': {
      const parts = parsed.id.split('_');
      if (parts.length !== 3 || parts[1].length !== 12) return null;
      const t = parts[1];
      const start = `${t.substring(0, 4)}-${t.substring(4, 6)}-${t.substring(6, 8)}:${t.substring(8, 10)}-${t.substring(10, 12)}`;
      return `${server}/timeshift${auth}${parts[2]}/${start}/${parts[0]}.ts`;
    }
  }
}

const QUERY = /\b(username|password|user|pass|token)=([^&\s"']*)/gi;
const PATH = /\/(live|movie|series|timeshift)\/[^/\s"']+\/[^/\s"']+\//gi;

/** Removes logins from any text before it can reach a log, an error message or the screen. */
export function redact(text: string | null | undefined): string {
  if (text == null) return '';
  return text.replace(QUERY, (_m, k) => `${k}=***`).replace(PATH, (_m, seg) => `/${seg}/***/***/`);
}
