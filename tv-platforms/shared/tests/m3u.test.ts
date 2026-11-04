// Port of app/src/test/.../M3uParserTest.kt: the same inputs must give the same results.
import { describe, expect, it } from 'vitest';
import { classify, channelKey, ContentKind, M3uParser, MAX_NAME, parseM3u, seriesShow } from '@shared/core/m3u';
import { normalize } from '@shared/core/text';
import { sha1Hex } from '@shared/core/sha1';
import { tagRegion } from '@shared/core/region';

const trim = (s: string) => s.split('\n').map((l) => l.trim()).join('\n').trim();

describe('M3U parser (parity with Android)', () => {
  it('parses attributes with commas inside quotes', () => {
    const { entries: e } = parseM3u(trim(`
      #EXTM3U
      #EXTINF:-1 tvg-id="France24.fr@French" tvg-logo="https://x/logo.png" group-title="News, FR",France 24 (1080p)
      https://live.france24.com/hls/live/master.m3u8`));
    expect(e).toHaveLength(1);
    expect(e[0].name).toBe('France 24 (1080p)');
    expect(e[0].group).toBe('News, FR');
    expect(e[0].tvgId).toBe('France24.fr@French');
    expect(e[0].logo).toBe('https://x/logo.png');
  });

  it('reads header guide urls', () => {
    const { epg } = parseM3u('#EXTM3U url-tvg="https://a/guide.xml.gz,https://b/g.xml" x-tvg-url="https://a/guide.xml.gz"\n');
    expect(epg).toEqual(['https://a/guide.xml.gz', 'https://b/g.xml']);
  });

  it('applies VLC options, EXTGRP and pipe headers', () => {
    const { entries: e } = parseM3u(trim(`
      #EXTM3U
      #EXTINF:-1,Channel A
      #EXTGRP:Sport
      #EXTVLCOPT:http-user-agent=MyUA/1.0
      #EXTVLCOPT:http-referrer=https://ref.example/
      https://a.example/live.m3u8
      #EXTINF:-1,Channel B
      https://b.example/live.m3u8|User-Agent=Other&Referer=https://r.example/`));
    expect(e[0].group).toBe('Sport');
    expect(e[0].userAgent).toBe('MyUA/1.0');
    expect(e[0].referrer).toBe('https://ref.example/');
    expect(e[1].url).toBe('https://b.example/live.m3u8');
    expect(e[1].userAgent).toBe('Other');
    expect(e[1].referrer).toBe('https://r.example/');
  });

  it('flags DRM declared by KODIPROP', () => {
    const { entries } = parseM3u(trim(`
      #EXTINF:-1,Protected
      #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
      #KODIPROP:inputstream.adaptive.license_key=https://lic.example/
      https://p.example/manifest.mpd`));
    expect(entries[0].drm).toBe(true);
  });

  it('skips unsupported schemes and counts malformed entries', () => {
    const { entries, stats } = parseM3u(trim(`
      #EXTM3U
      #EXTINF:-1,Multicast
      udp://@239.0.0.1:1234
      #EXTINF:-1,Rtmp
      rtmp://x/live
      #EXTINF:-1,Dangling
      #EXTINF:-1,Good
      http://ok.example/stream.ts`));
    expect(entries.map((x) => x.name)).toEqual(['Good']);
    expect(stats.skippedUnsupported).toBe(2);
    expect(stats.skippedMalformed).toBe(1);
  });

  it('handles BOM, plain urls and a missing title', () => {
    const { entries: e } = parseM3u('﻿#EXTM3U\nhttps://cdn.example/path/channel.m3u8?token=1\n#EXTINF:-1 tvg-name="Named",\nhttps://x.example/a.m3u8\n');
    expect(e[0].name).toBe('channel.m3u8');
    expect(e[1].name).toBe('Named');
    expect(e[0].tvgId).toBeNull();
  });

  it('caps hostile lengths', () => {
    const { entries } = parseM3u(`#EXTINF:-1,${'A'.repeat(10_000)}\nhttps://x.example/a.m3u8\n`);
    expect(entries[0].name.length).toBe(MAX_NAME);
  });

  it('normalizes accents and Arabic diacritics', () => {
    expect(normalize('  Télé   Liban ')).toBe('tele liban');
    expect(normalize('الجَزِيرَة')).toBe('الجزيرة');
    expect(channelKey(null, 'Télé Liban', 'News')).toBe(channelKey(null, 'tele liban', 'news'));
    expect(channelKey('A.fr', 'x', '')).not.toBe(channelKey('B.fr', 'x', ''));
  });

  it('gives the same SHA-1 as the JVM', () => {
    expect(sha1Hex('abc')).toBe('a9993e364706816aba3e25717850c26c9cd0d89d');
    expect(sha1Hex('')).toBe('da39a3ee5e6b4b0d3255bfef95601890afd80709');
    expect(sha1Hex('a'.repeat(1000))).toBe('291e9a6c66994949b57ba5e650361e98fc36b1ba');
  });

  it('streams very large playlists in small chunks', () => {
    const count = 100_000;
    let seen = 0;
    const p = new M3uParser(() => { seen++; });
    const t0 = Date.now();
    let block = '#EXTM3U\n';
    for (let i = 0; i < count; i++) {
      block += `#EXTINF:-1 tvg-id="c${i}" group-title="Group ${i % 50}",Channel ${i}\nhttp://h.example/live/${i}.ts\n`;
      if (block.length > 64 * 1024) {
        // odd chunk sizes: lines are split across chunks
        for (let j = 0; j < block.length; j += 4093) p.push(block.substring(j, j + 4093));
        block = '';
      }
    }
    p.push(block);
    const stats = p.end();
    expect(seen).toBe(count);
    expect(stats.entries).toBe(count);
    expect(Date.now() - t0).toBeLessThan(10_000);
  });

  const entry = (name: string, url: string, group = '') => ({ name, url, group });

  it('splits mixed playlists into sections', () => {
    expect(classify(entry('Breaking Bad S02 E05', 'http://h/x/1.mkv', 'US'))).toBe(ContentKind.SERIES);
    expect(classify(entry('باب الحارة الحلقة 12', 'http://h/v/9.mp4'))).toBe(ContentKind.SERIES);
    expect(classify(entry('Anything', 'http://h:8080/series/u/p/77.mkv'))).toBe(ContentKind.SERIES);
    expect(classify(entry('Dune', 'http://h:8080/movie/u/p/123.mkv'))).toBe(ContentKind.MOVIE);
    expect(classify(entry('Inception', 'http://h/m/2.ts', 'Films Action'))).toBe(ContentKind.MOVIE);
    expect(classify(entry('Clip', 'http://h/m/2.mp4'))).toBe(ContentKind.MOVIE);
    expect(classify(entry('beIN Serie A', 'http://h/live/1.ts', 'Serie A'))).toBe(ContentKind.LIVE);
    expect(classify(entry('Al Jazeera', 'https://x/live.m3u8', 'News'))).toBe(ContentKind.LIVE);
  });

  it('groups episodes by show', () => {
    expect(seriesShow('Breaking Bad S02 E05 - Peekaboo')).toBe('Breaking Bad');
    expect(seriesShow('The Office 3x07')).toBe('The Office');
    expect(seriesShow('باب الحارة الحلقة 12')).toBe('باب الحارة');
    expect(seriesShow('Movie Title')).toBe('Movie Title');
  });
});

describe('Region tagger (parity with Android)', () => {
  it('uses explicit attributes, prefixes, then words', () => {
    expect(tagRegion('Sport', 'fr', null)).toEqual({ country: 'FR', language: 'fr' });
    expect(tagRegion('FR| SPORT')).toEqual({ country: 'FR', language: 'fr' });
    expect(tagRegion('[AR] MBC')).toEqual({ country: null, language: 'ar' });
    expect(tagRegion('UK: NEWS')).toEqual({ country: 'GB', language: 'en' });
    expect(tagRegion('Chaînes Maroc')).toEqual({ country: 'MA', language: 'ar' });
    expect(tagRegion('قنوات عربي')).toEqual({ country: null, language: 'ar' });
    expect(tagRegion('VIP | SPORTS')).toEqual({ country: null, language: null });
  });
});
