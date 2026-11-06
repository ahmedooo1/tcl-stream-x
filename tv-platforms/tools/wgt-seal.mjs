// Seals the signed Samsung package with authenticated encryption (AES-256-GCM, key from scrypt),
// because workflow artifacts of a public repository can be downloaded by any GitHub account.
// A modified file or a wrong passphrase fails, and then no output file is written.
//   WGT_PASSPHRASE=... node tools/wgt-seal.mjs seal   in.wgt     out.wgt.enc
//   WGT_PASSPHRASE=... node tools/wgt-seal.mjs unseal in.wgt.enc out.wgt
// The passphrase comes from the environment only: never as an argument, never printed.
import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAGIC = Buffer.from('SXWGT\x001\n', 'latin1');
const SALT = 16, IV = 12, TAG = 16;
const HEADER = MAGIC.length + SALT + IV;
const SCRYPT = { N: 2 ** 17, r: 8, p: 1, maxmem: 256 * 1024 * 1024 };

function keyFor(passphrase, salt) {
  if (!passphrase) throw new Error('empty passphrase');
  return scryptSync(passphrase, salt, 32, SCRYPT);
}

export function seal(plain, passphrase) {
  const salt = randomBytes(SALT), iv = randomBytes(IV);
  const header = Buffer.concat([MAGIC, salt, iv]);
  const c = createCipheriv('aes-256-gcm', keyFor(passphrase, salt), iv);
  c.setAAD(header);
  const body = Buffer.concat([c.update(plain), c.final()]);
  return Buffer.concat([header, c.getAuthTag(), body]);
}

export function unseal(sealed, passphrase) {
  if (sealed.length < HEADER + TAG || !sealed.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error('not a sealed package');
  const salt = sealed.subarray(MAGIC.length, MAGIC.length + SALT);
  const d = createDecipheriv('aes-256-gcm', keyFor(passphrase, salt), sealed.subarray(MAGIC.length + SALT, HEADER));
  d.setAAD(sealed.subarray(0, HEADER));
  d.setAuthTag(sealed.subarray(HEADER, HEADER + TAG));
  try {
    return Buffer.concat([d.update(sealed.subarray(HEADER + TAG)), d.final()]);
  } catch {
    throw new Error('authentication failed: wrong passphrase or modified file');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [mode, input, output] = process.argv.slice(2);
  try {
    if ((mode !== 'seal' && mode !== 'unseal') || !input || !output) throw new Error('usage: wgt-seal.mjs seal|unseal <in> <out>');
    const run = mode === 'seal' ? seal : unseal;
    writeFileSync(output, run(readFileSync(input), process.env.WGT_PASSPHRASE), { mode: 0o600 });
    console.log(`${mode}: ok`);
  } catch (e) {
    console.error(`${mode ?? 'wgt-seal'}: ${e.message}`);
    process.exit(1);
  }
}
