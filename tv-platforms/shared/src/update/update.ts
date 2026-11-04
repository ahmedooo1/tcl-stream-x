/**
 * Updates of the TV editions (Updater.kt for Samsung and LG). A web app on these TVs cannot install
 * a package itself: the check finds a newer TV build on the project's releases and the app shows
 * where to get it (address and QR code) and how to install it from a computer.
 *
 * TV builds are published as pre-releases tagged "tv-build-N": GitHub never makes a pre-release the
 * "latest" release, which is what the Android updater reads, so the Android update path is untouched.
 */
export const REPO = 'ahmedooo1/tcl-stream-x';
const API = `https://api.github.com/repos/${REPO}`;
const TAG = /^(?:refs\/tags\/)?tv-build-(\d+)$/;
export const CHECK_EVERY_MS = 30 * 60_000;

export interface TvRelease { build: number; page: string; assets: string[] }

/** Highest build number among the "tv-build-N" tags (git/matching-refs answer), or 0. */
export function newestTvTag(refs: unknown): number {
  if (!Array.isArray(refs)) return 0;
  let best = 0;
  for (const r of refs) {
    const m = TAG.exec(String((r as Record<string, unknown> | null)?.['ref'] ?? ''));
    if (m) best = Math.max(best, Number(m[1]));
  }
  return best;
}

/** Page and file names of one TV release (releases/tags/tv-build-N answer). */
export function releaseInfo(build: number, release: unknown): TvRelease {
  const o = (release && typeof release === 'object' ? release : {}) as Record<string, unknown>;
  const page = typeof o['html_url'] === 'string' && o['html_url'].startsWith('https://github.com/') ? o['html_url'] : `https://github.com/${REPO}/releases/tag/tv-build-${build}`;
  const assets = Array.isArray(o['assets']) ? (o['assets'] as Record<string, unknown>[]).map((a) => String(a?.['name'] ?? '')).filter(Boolean) : [];
  return { build, page, assets };
}

/**
 * The tags are read rather than the list of releases: the Android builds are far more numerous and
 * would push the TV ones out of the first page.
 */
export async function checkTvUpdate(current: number, fetcher: typeof fetch = fetch): Promise<TvRelease | null> {
  const init: RequestInit = { credentials: 'omit', headers: { Accept: 'application/vnd.github+json' } };
  const res = await fetcher(`${API}/git/matching-refs/tags/tv-build-`, init);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const build = newestTvTag(await res.json());
  if (build <= current) return null;
  const rel = await fetcher(`${API}/releases/tags/tv-build-${build}`, init);
  return releaseInfo(build, rel.ok ? await rel.json() : null);
}
