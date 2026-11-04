/**
 * Storage of Xtream logins on a TV without a hardware keystore for web apps.
 *
 * What is done:
 *  - An AES-256-GCM key is generated on the device with WebCrypto as a NON-EXTRACTABLE CryptoKey and
 *    stored in IndexedDB as a key object (the browser keeps the key material; JavaScript, including
 *    this app, can use it but can never read its bytes, so it cannot be copied out by a script or
 *    leak through an export of the data).
 *  - Each login is encrypted with a fresh 96-bit IV; only the ciphertext is stored, under an opaque id.
 *  - The plaintext exists only in memory, the moment a request or the player needs it.
 *  - No key, salt or secret is embedded in the distributed JavaScript.
 *
 * Limits, stated plainly (see docs/SECURITY.md): this is not equivalent to the Android Keystore.
 * Tizen and webOS do not give web apps a hardware-backed key store. Anyone able to run code inside
 * the app's origin (a compromised build, a rooted TV with developer access to the app's storage)
 * can ask the browser to decrypt with the same key. What it protects against: reading the stored
 * data at rest (backups, storage dumps, other apps), and casual exposure on screen and in logs.
 */
import { Db, S } from '../storage/db';
import type { XtreamCredentials } from '../xtream/urls';

const KEY_ID = 'k1';

interface KeyRow { id: string; key: CryptoKey }
interface SecretRow { id: string; iv: Uint8Array; data: ArrayBuffer }

export class Vault {
  private key: CryptoKey | null = null;

  constructor(private readonly db: Db, private readonly subtle: SubtleCrypto = crypto.subtle, private readonly random: Crypto = crypto) {}

  static available(): boolean {
    return typeof crypto !== 'undefined' && !!crypto.subtle && typeof crypto.getRandomValues === 'function';
  }

  private async masterKey(): Promise<CryptoKey> {
    if (this.key) return this.key;
    const row = await this.db.get<KeyRow>(S.vault, KEY_ID);
    if (row?.key) { this.key = row.key; return row.key; }
    const key = await this.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    await this.db.put<KeyRow>(S.vault, { id: KEY_ID, key });
    this.key = key;
    return key;
  }

  async store(c: XtreamCredentials, id?: string): Promise<string> {
    const key = await this.masterKey();
    const iv = this.random.getRandomValues(new Uint8Array(12));
    const plain = new TextEncoder().encode(JSON.stringify([c.username, c.password]));
    const data = await this.subtle.encrypt({ name: 'AES-GCM', iv }, key, plain);
    plain.fill(0);
    const sid = id ?? 's' + Array.from(this.random.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('');
    await this.db.put<SecretRow>(S.vault, { id: sid, iv, data });
    return sid;
  }

  async load(id: string): Promise<XtreamCredentials | null> {
    const row = await this.db.get<SecretRow>(S.vault, id);
    if (!row || !row.data) return null;
    try {
      const key = await this.masterKey();
      const plain = await this.subtle.decrypt({ name: 'AES-GCM', iv: row.iv }, key, row.data);
      const [username, password] = JSON.parse(new TextDecoder().decode(plain)) as [string, string];
      return {
        username,
        password,
        toString: () => 'XtreamCredentials(***)',
        toJSON: () => '***',
      } as XtreamCredentials;
    } catch {
      return null;
    }
  }

  async remove(id: string): Promise<void> {
    await this.db.delete(S.vault, id);
  }
}
