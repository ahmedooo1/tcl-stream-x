/** Film and series details from the account they belong to (plot, backdrop, seasons and episodes). */
import type { Ctx } from './app';
import type { Channel } from '../storage/model';
import { makeRef, parseRef, XtreamCredentials } from '../xtream/urls';
import { MovieInfo, SeriesInfo, XtreamClient } from '../xtream/client';
import type { PlayItem } from '../player/controller';

const client = new XtreamClient(15_000, 60_000);
const cache = new Map<string, Promise<MovieInfo | SeriesInfo | null>>();

async function account(ctx: Ctx, playlistId: number): Promise<{ server: string; c: XtreamCredentials } | null> {
  const p = (await ctx.db.playlists()).find((x) => x.id === playlistId);
  if (!p?.source || !p.vaultId) return null;
  const c = await ctx.vault.load(p.vaultId);
  return c ? { server: p.source, c } : null;
}

export function isXtreamSeries(url: string): boolean {
  return parseRef(url)?.ref === 'series';
}

export function details(ctx: Ctx, ch: Channel): Promise<MovieInfo | SeriesInfo | null> {
  const ref = parseRef(ch.url);
  if (!ref || (ref.ref !== 'movie' && ref.ref !== 'series')) return Promise.resolve(null);
  const k = ch.url;
  let p = cache.get(k);
  if (!p) {
    p = (async () => {
      const a = await account(ctx, ref.playlistId);
      if (!a) return null;
      try {
        return ref.ref === 'series' ? await client.seriesInfo(a.server, a.c, ref.id) : await client.movieInfo(a.server, a.c, ref.id);
      } catch {
        return null;
      }
    })();
    cache.set(k, p);
    if (cache.size > 200) cache.delete(cache.keys().next().value as string);
  }
  return p;
}

/** Episodes of an account series, ready to play in order. */
export function episodeItems(series: Channel, info: SeriesInfo): PlayItem[] {
  const ref = parseRef(series.url)!;
  return info.episodes.map((e) => ({
    key: `${series.channelKey}:e${e.id}`,
    title: `${series.name} · S${e.season}E${e.episode} ${e.title}`.trim(),
    urls: [makeRef(ref.playlistId, 'episode', e.id, e.extension)],
    isLive: false,
    logo: series.logo,
    poster: e.image ?? series.logo,
    userAgent: null,
    referrer: null,
    drm: false,
    tvgId: null,
    channel: null,
    group: null,
    upNext: [],
  }));
}
