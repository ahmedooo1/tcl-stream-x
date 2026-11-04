/**
 * Updates of the TV editions (Updater.kt for Samsung and LG). A web app on these TVs cannot install
 * a package itself: the check finds a newer TV build on the project's releases and the app shows
 * where to get it (address and QR code) and how to install it from a computer.
 *
 * TV builds are published as pre-releases tagged "tv-build-N": GitHub never makes a pre-release the
 * "latest" release, which is what the Android updater reads, so the Android update path is untouched.
 */
export const REPO = 'ahmedooo1/tcl-stream-x';
const LIST = `https://api.github.com/repos/${REPO}/releases?per_page=30`;
const TAG = /^tv-build-(\d+)$/;
export const CHECK_EVERY_MS = 30 * 60_000;

export interface TvRelease { build: number; page: string; assets: string[] }

/** Newest TV build in a releases listing (GitHub REST shape), or null. */
export function newestTvRelease(list: unknown): TvRelease | null {
  if (!Array.isArray(list)) return null;
  let best: TvRelease | null = null;
  for (const r of list) {
    if (!r || typeof r !== 'object') continue;
    const o = r as Record<string, unknown>;
    const m = TAG.exec(String(o['tag_name'] ?? ''));
    if (!m || o['draft'] === true) continue;
    const build = Number(m[1]);
    const page = typeof o['html_url'] === 'string' && o['html_url'].startsWith('https://github.com/') ? o['html_url'] : `https://github.com/${REPO}/releases/tag/tv-build-${build}`;
    const assets = Array.isArray(o['assets']) ? (o['assets'] as Record<string, unknown>[]).map((a) => String(a['name'] ?? '')).filter(Boolean) : [];
    if (!best || build > best.build) best = { build, page, assets };
  }
  return best;
}

export async function checkTvUpdate(current: number, fetcher: typeof fetch = fetch): Promise<TvRelease | null> {
  const res = await fetcher(LIST, { credentials: 'omit', headers: { Accept: 'application/vnd.github+json' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const r = newestTvRelease(await res.json());
  return r && r.build > current ? r : null;
}
