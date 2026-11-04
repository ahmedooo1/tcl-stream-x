import { describe, expect, it } from 'vitest';
import { cueAt, parseCues } from '@shared/player/subtitles';

describe('subtitles', () => {
  it('reads SRT with numbering, tags and CRLF', () => {
    const c = parseCues('1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00,250 --> 00:01:02,000\r\n{\\an8}Top &amp; more\r\n');
    expect(c).toEqual([
      { start: 1000, end: 3500, text: 'Hello\nthere' },
      { start: 60250, end: 62000, text: 'Top & more' },
    ]);
  });
  it('reads WebVTT with header, settings and short times', () => {
    const c = parseCues('WEBVTT\n\nNOTE a comment\n\n00:05.000 --> 00:06.000 align:start\n<v Bob>Hi\n');
    expect(c).toEqual([{ start: 5000, end: 6000, text: 'Hi' }]);
  });
  it('finds the cue on screen', () => {
    const c = parseCues('00:00:01,000 --> 00:00:02,000\nA\n\n00:00:01,500 --> 00:00:03,000\nB\n\n00:00:05,000 --> 00:00:06,000\nC');
    expect(cueAt(c, 500)).toBeNull();
    expect(cueAt(c, 1600)).toBe('A\nB');
    expect(cueAt(c, 2500)).toBe('B');
    expect(cueAt(c, 4000)).toBeNull();
    expect(cueAt(c, 5000)).toBe('C');
  });
  it('skips broken blocks', () => {
    expect(parseCues('garbage\n\n00:00:02,000 --> 00:00:01,000\nbackwards')).toEqual([]);
  });
});
