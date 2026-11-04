/**
 * What a list shows (the Lineup of the Android app): one section of a playlist, optionally one
 * group, optionally one country or language, without the locked groups. Counting and paging use
 * the database indexes, so lists of 250 000 entries open at once.
 */
import type { Db } from '../storage/db';
import type { Channel, Playlist } from '../storage/model';
import type { ContentKindCode } from '../core/m3u';

export interface Lineup { total: number; load(offset: number, limit: number): Promise<Channel[]> }

/** Groups of a section for a region (counts restricted to that region), locked ones removed. */
export function groupsFor(p: Playlist, kind: ContentKindCode, region: string | null, hidden: Set<string>): { groupTitle: string; count: number }[] {
  const all = region ? (p.regionGroups?.[kind]?.[region] ?? []) : (p.groups[kind] ?? []);
  return all.filter((g) => !hidden.has(g.groupTitle));
}

export async function lineup(db: Db, p: Playlist, kind: ContentKindCode, group: string | null, region: string | null, hidden: Set<string>): Promise<Lineup> {
  if (group != null) {
    return region
      ? { total: await db.regionCount(p, kind, group, region), load: (o, l) => db.regionChannels(p, kind, group, region, o, l) }
      : { total: await db.countOf(p, kind, group), load: (o, l) => db.channelsOf(p, kind, group, o, l) };
  }
  const groups = groupsFor(p, kind, region, hidden);
  if (hidden.size === 0) {
    return region
      ? { total: await db.regionCount(p, kind, null, region), load: (o, l) => db.regionChannels(p, kind, null, region, o, l) }
      : { total: await db.countOf(p, kind, null), load: (o, l) => db.channelsOf(p, kind, null, o, l) };
  }
  // A lock is active: read group by group so locked groups never appear.
  return {
    total: groups.reduce((s, g) => s + g.count, 0),
    async load(o, l) {
      const out: Channel[] = [];
      let skip = o;
      for (const g of groups) {
        if (skip >= g.count) { skip -= g.count; continue; }
        out.push(...await (region ? db.regionChannels(p, kind, g.groupTitle, region, skip, l - out.length) : db.channelsOf(p, kind, g.groupTitle, skip, l - out.length)));
        skip = 0;
        if (out.length >= l) break;
      }
      return out;
    },
  };
}
