import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { inspectManifest, probe, statusForHttp } from '@shared/check/probe';
import { StreamStatus } from '@shared/storage/model';

const res = (body: string | Uint8Array, status = 200, type = 'application/vnd.apple.mpegurl', url = '') => {
  const r = new Response((status === 204 ? null : body) as BodyInit | null, { status, headers: { 'content-type': type } });
  Object.defineProperty(r, 'url', { value: url });
  return r;
};

describe('availability probe', () => {
  it('inspects HLS masters, media playlists, DRM and DASH', () => {
    const master = inspectManifest('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nhi.m3u8\n');
    expect(master.variants).toEqual([{ uri: 'low.m3u8', bandwidth: 800000, height: 360 }, { uri: 'hi.m3u8', bandwidth: 3000000, height: 1080 }]);
    expect(inspectManifest('﻿#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts').segments).toEqual(['a.ts', 'b.ts']);
    expect(inspectManifest('#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://x"\n').drm).toBe(true);
    expect(inspectManifest('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="k",KEYFORMAT="identity"\n').drm).toBe(false);
    expect(inspectManifest('<MPD><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6"/></MPD>')).toMatchObject({ isDash: true, drm: true });
    expect(inspectManifest('<html>')).toMatchObject({ isHls: false, isDash: false });
    expect([statusForHttp(401), statusForHttp(451), statusForHttp(500)]).toEqual([StreamStatus.AUTH, StreamStatus.FORBIDDEN, StreamStatus.OFFLINE]);
  });
  it('follows the lightest variant down to one segment', async () => {
    const seen: string[] = [];
    const f = async (u: string) => {
      seen.push(u);
      if (u.endsWith('master.m3u8')) return res('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhi/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nlo/index.m3u8\n', 200, 'application/x-mpegurl', u);
      if (u.endsWith('lo/index.m3u8')) return res('#EXTM3U\n#EXTINF:6,\n1.ts\n#EXTINF:6,\n2.ts\n', 200, 'application/x-mpegurl', u);
      return res(new Uint8Array(188), 206, 'video/mp2t', u);
    };
    expect(await probe('http://cdn/live/master.m3u8', f)).toEqual({ status: StreamStatus.ONLINE, detail: 'segment 188 B' });
    expect(seen).toEqual(['http://cdn/live/master.m3u8', 'http://cdn/live/lo/index.m3u8', 'http://cdn/live/lo/2.ts']);
  });
  it('maps refusals, timeouts and opaque network errors honestly', async () => {
    expect((await probe('http://x/a.m3u8', async () => res('', 403))).status).toBe(StreamStatus.FORBIDDEN);
    expect((await probe('http://x/a.mp4', async () => res(new Uint8Array(10), 200, 'video/mp4'))).status).toBe(StreamStatus.ONLINE);
    expect((await probe('http://x/a', async () => { throw new TypeError('Failed to fetch'); })).status).toBe(StreamStatus.UNKNOWN);
    expect((await probe('http://x/a', async () => { throw new DOMException('aborted', 'AbortError'); })).status).toBe(StreamStatus.TIMEOUT);
    expect((await probe('http://x/a.m3u8', async () => res('<html>', 200, 'text/html'))).status).toBe(StreamStatus.INVALID);
  });
});
