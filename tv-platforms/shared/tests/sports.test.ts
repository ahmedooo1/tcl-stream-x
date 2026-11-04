import { describe, expect, it } from 'vitest';
import { parseInstant, parseScoreboard, Sports, sortMatches } from '@shared/sports/sports';

const board = (state: string, id = '1', date = '2026-11-04T20:00Z') => ({
  leagues: [{ name: 'Ligue 1' }],
  events: [{ id, date, competitions: [{ status: { type: { state, shortDetail: "62'" } }, venue: { fullName: 'Parc' }, competitors: [
    { homeAway: 'away', score: '1', team: { shortDisplayName: 'OM', logo: 'https://x/om.png' } },
    { homeAway: 'home', score: '2', team: { displayName: 'Paris SG' } },
  ] }] }, { id: 'broken' }],
});

describe('sports', () => {
  it('reads ESPN dates without seconds', () => {
    expect(parseInstant('2026-10-21T19:00Z')).toBe(Date.UTC(2026, 9, 21, 19, 0));
    expect(parseInstant('nope')).toBeNull();
  });
  it('reads a scoreboard defensively', () => {
    const [m, ...rest] = parseScoreboard(board('in'), 'fra.1');
    expect(rest).toEqual([]);
    expect(m).toMatchObject({ id: '1', leagueName: 'Ligue 1', state: 'live', statusText: "62'", venue: 'Parc' });
    expect(m.home).toEqual({ name: 'Paris SG', logo: null, score: '2' });
    expect(m.away).toEqual({ name: 'OM', logo: 'https://x/om.png', score: '1' });
    expect(parseScoreboard(board('pre'), 'x')[0].home.score).toBeNull();
    expect(parseScoreboard('junk', 'x')).toEqual([]);
  });
  it('puts live matches first, drops old results and duplicates', () => {
    const now = Date.UTC(2026, 10, 4, 21);
    const a = parseScoreboard(board('pre', 'a', '2026-11-04T22:00Z'), 'l')[0];
    const b = parseScoreboard(board('in', 'b', '2026-11-04T20:00Z'), 'l')[0];
    const c = parseScoreboard(board('post', 'c', '2026-11-03T10:00Z'), 'l')[0];
    expect(sortMatches([a, b, c, a], now).map((m) => m.id)).toEqual(['b', 'a']);
  });
  it('one request per league and day, failures reported per league', async () => {
    const urls: string[] = [];
    const s = new Sports({ reviewedAt: '', note: '', regions: { FR: [{ league: 'fra.1', name: 'L1', destinations: ['Ligue 1+'], verified: true }] }, leagues: [{ id: 'fra.1', sport: 'soccer' }, { id: 'bad', sport: 'soccer' }] },
      async (u) => { urls.push(u); if (u.includes('/bad/')) throw new Error('500'); return board('pre', u.slice(-8)); });
    const r = await s.schedule(true, Date.UTC(2026, 10, 4, 12));
    expect(urls.length).toBe(18);
    expect(urls[0]).toMatch(/^https:\/\/site\.api\.espn\.com\/apis\/site\/v2\/sports\/soccer\/(fra\.1|bad)\/scoreboard\?dates=2026110[3-9]$/);
    expect(r.failedLeagues).toEqual(['bad']);
    expect(s.rightsFor('FR', 'fra.1')?.destinations).toEqual(['Ligue 1+']);
    expect(s.rightsFor('MENA', 'fra.1')).toBeNull();
  });
});
