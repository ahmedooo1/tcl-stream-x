import { describe, expect, it } from 'vitest';
// @ts-expect-error plain Node tool, without type declarations
import { seal, unseal } from '../../tools/wgt-seal.mjs';

declare const Buffer: { from(data: string | Uint8Array): any };

const plain = Buffer.from('PK signed package bytes '.repeat(200));
const pass = 'correct horse battery staple';

describe('sealed Samsung package', () => {
  it('gives back the exact bytes, and never contains them in clear', () => {
    const sealed = seal(plain, pass);
    expect(unseal(sealed, pass).equals(plain)).toBe(true);
    expect(sealed.includes(Buffer.from('PK signed'))).toBe(false);
  });

  it('refuses a wrong passphrase', () => {
    expect(() => unseal(seal(plain, pass), 'wrong')).toThrow('authentication failed');
  });

  it('refuses a change anywhere in the file: salt, nonce, tag or body', () => {
    const sealed = seal(plain, pass);
    for (const at of [10, 30, 40, sealed.length - 1]) {
      const bad = Buffer.from(sealed);
      bad[at] ^= 1;
      expect(() => unseal(bad, pass)).toThrow('authentication failed');
    }
  });

  it('refuses a truncated file, a foreign file and an empty passphrase', () => {
    const sealed = seal(plain, pass);
    expect(() => unseal(sealed.subarray(0, sealed.length - 5), pass)).toThrow('authentication failed');
    expect(() => unseal(plain, pass)).toThrow('not a sealed package');
    expect(() => seal(plain, '')).toThrow('empty passphrase');
  });
});
