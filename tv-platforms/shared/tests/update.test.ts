import { describe, expect, it } from 'vitest';
import { checkTvUpdate, newestTvRelease } from '@shared/update/update';

const list = [
  { tag_name: 'build-310', html_url: 'https://github.com/ahmedooo1/tcl-stream-x/releases/tag/build-310', assets: [{ name: 'StreamX.apk' }] },
  { tag_name: 'tv-build-12', html_url: 'https://github.com/ahmedooo1/tcl-stream-x/releases/tag/tv-build-12', prerelease: true, assets: [{ name: 'StreamX-LG.ipk' }, { name: 'StreamX-Samsung-unsigned.zip' }] },
  { tag_name: 'tv-build-9', prerelease: true, assets: [] },
  { tag_name: 'tv-build-15', draft: true },
  { tag_name: 'tv-build-x' },
];

describe('TV updates', () => {
  it('reads only TV builds, never the Android ones or drafts', () => {
    expect(newestTvRelease(list)).toEqual({ build: 12, page: 'https://github.com/ahmedooo1/tcl-stream-x/releases/tag/tv-build-12', assets: ['StreamX-LG.ipk', 'StreamX-Samsung-unsigned.zip'] });
    expect(newestTvRelease({ message: 'rate limited' })).toBeNull();
  });
  it('offers a build only when newer than the installed one', async () => {
    const f = (async () => new Response(JSON.stringify(list))) as unknown as typeof fetch;
    expect((await checkTvUpdate(11, f))?.build).toBe(12);
    expect(await checkTvUpdate(12, f)).toBeNull();
  });
});
