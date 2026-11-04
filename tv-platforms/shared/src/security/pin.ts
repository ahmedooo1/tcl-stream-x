/**
 * Parental code, port of SettingsStore.setPin/checkPin. Only a salted hash is stored (PBKDF2-SHA-256,
 * 100 000 rounds: slower to guess than the Android salted SHA-256, same purpose). It keeps children
 * out of chosen categories; it is not a protection against someone with developer access to the TV.
 */
import type { Db } from '../storage/db';
import { S } from '../storage/db';
import type { LockedGroup } from '../storage/model';

const hex = (b: ArrayBuffer | Uint8Array) => Array.from(new Uint8Array(b instanceof Uint8Array ? b : new Uint8Array(b)), (x) => x.toString(16).padStart(2, '0')).join('');

/** Same rule as ChannelDao.adultLookingGroups on Android. */
export function adultLooking(group: string): boolean {
  const g = group.toLowerCase();
  return g.includes('xxx') || g.includes('adult') || g.includes('18+') || g.includes('+18') || g.includes('porn') || g.includes('للكبار');
}

export class Pin {
  constructor(private readonly db: Db) {}

  private async hash(salt: string, pin: string): Promise<string> {
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: new TextEncoder().encode(salt), iterations: 100_000 }, key, 256);
    return hex(bits);
  }

  async isSet(): Promise<boolean> {
    return (await this.db.setting<string | null>('pinHash', null)) != null;
  }

  async set(pin: string): Promise<void> {
    const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
    await this.db.setSetting('pinSalt', salt);
    await this.db.setSetting('pinHash', await this.hash(salt, pin));
  }

  async check(pin: string): Promise<boolean> {
    const salt = await this.db.setting<string | null>('pinSalt', null);
    const expected = await this.db.setting<string | null>('pinHash', null);
    if (!salt || !expected) return false;
    const got = await this.hash(salt, pin);
    let diff = got.length ^ expected.length;
    for (let i = 0; i < Math.min(got.length, expected.length); i++) diff |= got.charCodeAt(i) ^ expected.charCodeAt(i);
    return diff === 0;
  }

  async clear(): Promise<void> {
    await this.db.delete(S.settings, 'pinHash');
    await this.db.delete(S.settings, 'pinSalt');
    for (const l of await this.db.locks()) await this.db.delete(S.locks, l.groupTitle);
  }

  /** Categories that look adult-only are locked right away when the code is created. */
  async lockAdultLooking(): Promise<number> {
    let n = 0;
    for (const p of await this.db.playlists()) {
      for (const k of [0, 1, 2]) for (const g of p.groups[k] ?? []) {
        if (adultLooking(g.groupTitle)) { await this.db.put<LockedGroup>(S.locks, { groupTitle: g.groupTitle, hidden: 1 }); n++; }
      }
    }
    return n;
  }
}
