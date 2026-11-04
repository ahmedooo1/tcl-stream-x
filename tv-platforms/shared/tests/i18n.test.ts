import { describe, expect, it } from 'vitest';
import { format } from '@shared/i18n/index';
import { ar, fr } from '@shared/i18n/strings.gen';

describe('i18n', () => {
  it('formats like Android', () => {
    expect(format('%1$d chaînes', [25078])).toBe('25078 chaînes');
    expect(format('%2$s · %1$s', ['a', 'b'])).toBe('b · a');
    expect(format('%s et %s', ['x', 'y'])).toBe('x et y');
    expect(format('100%%', [])).toBe('100%');
  });
  it('has every French text in Arabic with the same placeholders', () => {
    for (const [k, v] of Object.entries(fr)) {
      const a = (ar as Record<string, string>)[k];
      expect(a, k).toBeTruthy();
      const ph = (s: string) => (s.match(/%(\d+\$)?[sd]/g) ?? []).sort().join(',');
      expect(ph(a), k).toBe(ph(v));
    }
  });
});
