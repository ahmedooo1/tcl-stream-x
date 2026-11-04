/**
 * Match schedules (port of SportsRepository.kt) from ESPN's public scoreboard endpoint (no key, no
 * account). It is not an officially documented API, so every field is read defensively and a failing
 * league is reported instead of breaking the hub. Rights holders come from the reviewed asset file
 * (sports_destinations.json, same file as the Android app). Nothing is ever played from here.
 */
export type MatchState = 'scheduled' | 'live' | 'finished';
export interface Team { name: string; logo: string | null; score: string | null }
export interface Match {
  id: string; leagueId: string; leagueName: string; start: number;
  home: Team; away: Team; state: MatchState; statusText: string; venue: string | null;
}
export interface LeagueRights { league: string; name: string; destinations: string[]; verified: boolean; source?: string; free?: boolean }
export interface SportsGuide { reviewedAt: string; note: string; regions: Record<string, LeagueRights[]>; leagues: { id: string; sport: string }[] }
export interface SportsResult { matches: Match[]; failedLeagues: string[]; fetchedAt: number }

const BASE = 'https://site.api.espn.com/apis/site/v2/sports';
const DAYS_AHEAD = 7;
const PARALLEL = 6;
const CACHE_MS = 10 * 60_000;

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (o: Obj | null, k: string): string | null => {
  const v = o?.[k];
  return typeof v === 'string' ? v : typeof v === 'number' ? String(v) : null;
};

/** ESPN dates look like 2026-10-21T19:00Z (no seconds). */
export function parseInstant(s: string): number | null {
  const fixed = s.length === 17 && s.endsWith('Z') ? s.slice(0, -1) + ':00Z' : s;
  const t = Date.parse(fixed);
  return Number.isFinite(t) ? t : null;
}

function team(c: Obj, state: MatchState): Team {
  const t = obj(c['team']);
  return { name: str(t, 'shortDisplayName') ?? str(t, 'displayName') ?? '?', logo: str(t, 'logo'), score: state === 'scheduled' ? null : str(c, 'score') };
}

export function parseEvent(e: unknown, leagueId: string, leagueName: string): Match | null {
  const ev = obj(e);
  const comp = obj(arr(ev?.['competitions'])[0]);
  if (!ev || !comp) return null;
  const competitors = arr(comp['competitors']).map(obj).filter((x): x is Obj => !!x);
  const home = competitors.find((c) => str(c, 'homeAway') === 'home') ?? competitors[0];
  const away = competitors.find((c) => str(c, 'homeAway') === 'away') ?? competitors[1];
  if (!home || !away) return null;
  const type = obj((obj(comp['status']) ?? obj(ev['status']))?.['type']);
  const s = str(type, 'state');
  const state: MatchState = s === 'in' ? 'live' : s === 'post' ? 'finished' : 'scheduled';
  const date = str(ev, 'date');
  const start = date ? parseInstant(date) : null;
  const id = str(ev, 'id');
  if (start == null || !id) return null;
  return {
    id, leagueId, leagueName, start, home: team(home, state), away: team(away, state), state,
    statusText: str(type, 'shortDetail') ?? '', venue: str(obj(comp['venue']), 'fullName'),
  };
}

export function parseScoreboard(root: unknown, leagueId: string): Match[] {
  const r = obj(root);
  if (!r) return [];
  const leagueName = str(obj(arr(r['leagues'])[0]), 'name') ?? leagueId;
  return arr(r['events']).map((e) => parseEvent(e, leagueId, leagueName)).filter((m): m is Match => !!m);
}

const day = (t: number) => { const d = new Date(t); return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`; };

export function sortMatches(all: Match[], now: number): Match[] {
  const seen = new Set<string>();
  return all
    .filter((m) => { const k = m.leagueId + m.id; if (seen.has(k)) return false; seen.add(k); return true; })
    .filter((m) => m.state !== 'finished' || m.start > now - 6 * 3600_000)
    .sort((a, b) => (a.state === 'live' ? 0 : 1) - (b.state === 'live' ? 0 : 1) || a.start - b.start);
}

export class Sports {
  private cache: SportsResult | null = null;
  constructor(readonly guide: SportsGuide, private readonly fetchJson: (url: string) => Promise<unknown> = defaultFetch) {}

  rightsFor(region: string, leagueId: string): LeagueRights | null {
    return this.guide.regions[region]?.find((r) => r.league === leagueId) ?? null;
  }

  async schedule(force = false, now = Date.now()): Promise<SportsResult> {
    if (this.cache && !force && Date.now() - this.cache.fetchedAt < CACHE_MS) return this.cache;
    // The endpoint rejects date ranges: one request per league and day (as on Android).
    const jobs: { league: { id: string; sport: string }; date: string }[] = [];
    for (const league of this.guide.leagues) for (let d = -1; d <= DAYS_AHEAD; d++) jobs.push({ league, date: day(now + d * 86400_000) });
    const failed = new Set<string>();
    const out: Match[] = [];
    let next = 0;
    const worker = async () => {
      while (next < jobs.length) {
        const j = jobs[next++];
        try { out.push(...parseScoreboard(await this.fetchJson(`${BASE}/${j.league.sport}/${j.league.id}/scoreboard?dates=${j.date}`), j.league.id)); } catch { failed.add(j.league.id); }
      }
    };
    await Promise.all(Array.from({ length: PARALLEL }, worker));
    this.cache = { matches: sortMatches(out, now), failedLeagues: [...failed], fetchedAt: Date.now() };
    return this.cache;
  }
}

async function defaultFetch(url: string): Promise<unknown> {
  const res = await fetch(url, { credentials: 'omit' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
