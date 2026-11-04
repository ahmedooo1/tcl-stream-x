/** Guide download and lookup, port of EpgRepository.kt. */
import type { Db } from '../storage/db';
import { S } from '../storage/db';
import type { Channel, Programme } from '../storage/model';
import { isInternal } from '../xtream/urls';
import { isSupportedScheme } from '../core/m3u';
import { DEFAULT_GUIDE, FUTURE_WINDOW_MS, PAST_WINDOW_MS, readGuide, XmltvReader } from './xmltv';
import { Store } from '../core/store';

export type EpgState = { kind: 'idle' } | { kind: 'loading'; source: string } | { kind: 'done'; programmes: number; sources: number; failed: number };

export class EpgService {
  readonly state = new Store<EpgState>({ kind: 'idle' });
  constructor(private readonly db: Db, private readonly resolve: (ref: string) => Promise<string | null>) {}

  async refresh(userUrl: string | null): Promise<EpgState> {
    const urls = [userUrl?.trim() || DEFAULT_GUIDE, ...(await this.db.playlists()).flatMap((p) => p.epgUrls)]
      .filter((u, i, a) => (isSupportedScheme(u) || isInternal(u)) && a.indexOf(u) === i);
    const wanted = new Set<string>();
    await this.db.scan<Channel>(S.channels, 'tvg', null, { filter: (c) => { if (c.tvgId) { wanted.add(c.tvgId.toLowerCase()); wanted.add(c.tvgId.split('@')[0].toLowerCase()); } return false; } });
    const now = Date.now();
    await this.db.clearProgrammes();
    let total = 0, failed = 0;
    for (const source of urls) {
      // An account guide is labelled by its reference, never by its address (which holds the login).
      this.state.set({ kind: 'loading', source: isInternal(source) ? source.split('/epg')[0] : source });
      try {
        const url = isInternal(source) ? await this.resolve(source) : source;
        if (!url) throw new Error('unavailable');
        let batch: Programme[] = [];
        const writes: Promise<void>[] = [];
        const reader = new XmltvReader(wanted, now - PAST_WINDOW_MS, now + FUTURE_WINDOW_MS, (p) => {
          batch.push(p);
          if (batch.length >= 1000) { writes.push(this.db.putProgrammes(batch)); batch = []; }
        });
        total += await readGuide(url, reader);
        if (batch.length) writes.push(this.db.putProgrammes(batch));
        await Promise.all(writes);
      } catch {
        failed++;
      }
    }
    const s: EpgState = { kind: 'done', programmes: total, sources: urls.length, failed };
    this.state.set(s);
    return s;
  }

  /** Now and next for a channel (tvg-id with or without its "@variant"). */
  async nowNext(tvgId: string | null): Promise<Programme[]> {
    if (!tvgId) return [];
    const now = Date.now();
    const id = tvgId.toLowerCase();
    let list = await this.db.programmes(id, now, now + 12 * 3600_000, 3);
    if (list.length === 0) list = await this.db.programmes(id.split('@')[0], now, now + 12 * 3600_000, 3);
    return list.filter((p) => p.stop > now).slice(0, 2);
  }
}
