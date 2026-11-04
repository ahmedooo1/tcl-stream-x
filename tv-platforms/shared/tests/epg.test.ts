// Port of XmltvParserTest.kt.
import { describe, expect, it } from 'vitest';
import { parseXmltvTime, XmltvReader } from '@shared/epg/xmltv';
import type { Programme } from '@shared/storage/model';

describe('XMLTV (parity with Android)', () => {
  it('parses times with offsets', () => {
    expect(parseXmltvTime('20261021200000 +0200')).toBe(1792605600000);
    expect(parseXmltvTime('20261021180000 +0000')).toBe(1792605600000);
    expect(parseXmltvTime('202610211800')).toBe(1792605600000);
    expect(parseXmltvTime('garbage')).toBeNull();
    expect(parseXmltvTime('20261341000000')).toBeNull();
  });

  it('keeps only wanted channels inside the window, in any chunk size', () => {
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
      <tv>
        <channel id="France24.fr"><display-name>France 24</display-name></channel>
        <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="France24.fr">
          <title lang="fr">Le Journal</title><title lang="en">The News</title>
          <desc lang="fr">Actualité &amp; analyses</desc>
          <category>News</category>
        </programme>
        <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="Other.xx">
          <title>Ignored</title>
        </programme>
        <programme start="20261030180000 +0000" stop="20261030190000 +0000" channel="France24.fr">
          <title>Too far</title>
        </programme>
      </tv>`;
    const window = parseXmltvTime('20261021000000 +0000')!;
    for (const size of [3, 50, 100000]) {
      const out: Programme[] = [];
      const r = new XmltvReader(new Set(['france24.fr']), window, window + 36 * 3600_000, (p) => out.push(p));
      for (let i = 0; i < xml.length; i += size) r.push(xml.substring(i, i + size));
      expect(out).toHaveLength(1);
      expect(out[0].title).toBe('Le Journal');
      expect(out[0].description).toBe('Actualité & analyses');
      expect(out[0].channelId).toBe('france24.fr');
    }
  });
});
