import { describe, expect, it } from 'vitest';
import { checkTvUpdate, newestTvTag, releaseInfo } from '@shared/update/update';

const refs = [{ ref: 'refs/tags/tv-build-9' }, { ref: 'refs/tags/tv-build-12' }, { ref: 'refs/tags/tv-build-x' }, { ref: 'refs/tags/build-310' }];
const rel = { tag_name: 'tv-build-12', html_url: 'https://github.com/ahmedooo1/tcl-stream-x/releases/tag/tv-build-12', prerelease: true, assets: [{ name: 'StreamX-LG.ipk' }, { name: 'StreamX-Samsung-unsigned.zip' }] };

describe('TV updates', () => {
  it('reads only the TV build tags, never the Android ones', () => {
    expect(newestTvTag(refs)).toBe(12);
    expect(newestTvTag({ message: 'rate limited' })).toBe(0);
    expect(releaseInfo(12, rel)).toEqual({ build: 12, page: rel.html_url, assets: ['StreamX-LG.ipk', 'StreamX-Samsung-unsigned.zip'] });
    expect(releaseInfo(3, { html_url: 'javascript:alert(1)' }).page).toBe('https://github.com/ahmedooo1/tcl-stream-x/releases/tag/tv-build-3');
  });
  it('offers a build only when newer than the installed one', async () => {
    const urls: string[] = [];
    const f = (async (u: string) => { urls.push(u); return new Response(JSON.stringify(u.includes('matching-refs') ? refs : rel)); }) as unknown as typeof fetch;
    expect((await checkTvUpdate(11, f))?.assets).toContain('StreamX-LG.ipk');
    expect(urls).toEqual(['https://api.github.com/repos/ahmedooo1/tcl-stream-x/git/matching-refs/tags/tv-build-', 'https://api.github.com/repos/ahmedooo1/tcl-stream-x/releases/tags/tv-build-12']);
    expect(await checkTvUpdate(12, f)).toBeNull();
  });
});
