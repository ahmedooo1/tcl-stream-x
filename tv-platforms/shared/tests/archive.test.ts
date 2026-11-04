import { describe, expect, it } from 'vitest';
import { genresOf, openFilms, parseFiles, parseSearch, sanitize, searchUrl } from '@shared/vod/archive';

describe('public-domain shelves', () => {
  it('builds the same search as the Android app, user text cannot change it', () => {
    const u = new URL(searchUrl('movies', 2, 'nosferatu") OR (x', genresOf('movies')[1]));
    expect(u.searchParams.get('q')).toBe('collection:(feature_films) AND mediatype:(movies) AND subject:(comedy) AND title:(nosferatu OR x)');
    expect(u.searchParams.getAll('fl[]')).toEqual(['identifier', 'title', 'year', 'description', 'licenseurl']);
    expect(u.searchParams.get('page')).toBe('2');
    expect(sanitize('***')).toBe('*');
    expect(sanitize('ليلى والذئب')).toBe('ليلى والذئب');
  });
  it('reads search results defensively', () => {
    const items = parseSearch({ response: { docs: [
      { identifier: 'Nosferatu_1922', title: 'Nosferatu', year: 1922, description: ['<p>A <b>vampire</b></p>'] },
      { identifier: '../etc', title: 'bad' },
      'junk',
    ] } });
    expect(items).toEqual([{ key: 'ia:Nosferatu_1922', title: 'Nosferatu', year: '1922', poster: 'https://archive.org/services/img/Nosferatu_1922', description: 'A vampire', license: null, archiveId: 'Nosferatu_1922' }]);
    expect(parseSearch(null)).toEqual([]);
  });
  it('lists one MP4 per original with subtitles', () => {
    const files = parseFiles('show', { files: [
      { name: 'ep 1.mpeg', source: 'original' },
      { name: 'ep 1.mp4', format: 'h.264', original: 'ep 1.mpeg', length: '1500.5' },
      { name: 'ep 1_512kb.mp4', format: '512Kb MPEG4', original: 'ep 1.mpeg' },
      { name: 'ep_2.mp4', format: 'MPEG4' },
      { name: 'ep.fr.srt' },
      { name: '__ia_thumb.mp4', source: 'metadata' },
    ] });
    expect(files.map((f) => [f.title, f.url, f.durationSec])).toEqual([
      ['ep 1', 'https://archive.org/download/show/ep%201.mp4', 1500.5],
      ['ep 2', 'https://archive.org/download/show/ep_2.mp4', null],
    ]);
    expect(files[0].subtitles).toEqual([{ url: 'https://archive.org/download/show/ep.fr.srt', language: 'fr', mime: 'application/x-subrip' }]);
  });
  it('sorts open films into their shelves', () => {
    const media = { films: [
      { key: 'a', title: 'A', year: 2008, license: 'CC', kind: 'animation', url: 'https://x/a.mp4' },
      { key: 'b', title: 'B', year: 2010, license: 'CC', kind: 'film', url: 'https://x/b.mp4' },
    ] };
    expect(openFilms(media, 'animation').map((f) => f.key)).toEqual(['a']);
    expect(openFilms(media, 'movies').map((f) => f.directUrl)).toEqual(['https://x/b.mp4']);
    expect(openFilms(media, 'series')).toEqual([]);
  });
});
