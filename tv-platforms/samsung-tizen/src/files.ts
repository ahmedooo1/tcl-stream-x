/**
 * "Mes fichiers" on Samsung: videos of the USB storages through tizen.filesystem (privileges
 * filesystem.read and externalstorage in config.xml). The walk is bounded (depth, entries, time) so a
 * large disk cannot block the app. Played by AVPlay from their file:// address.
 */
import type { LocalVideo } from '@shared/platform/platform';

interface Storage { label: string; type: string; state: string }
interface Fs {
  listStorages(ok: (s: Storage[]) => void, err?: (e: unknown) => void): void;
  listDirectory?(path: string, ok: (names: string[]) => void, err?: (e: unknown) => void): void;
  isDirectory?(path: string): boolean;
  toURI?(path: string): string;
}
const fs = (): Fs | undefined => (window as unknown as { tizen?: { filesystem?: Fs } }).tizen?.filesystem;

const VIDEO = /\.(mp4|m4v|mkv|avi|mov|ts|m2ts|mts|webm|wmv|flv|mpg|mpeg|3gp)$/i;
const MAX_DEPTH = 6;
const MAX_ENTRIES = 20000;
const MAX_MS = 20000;
const MAX_VIDEOS = 2000;

export const hasFiles = () => typeof fs()?.listDirectory === 'function';

export async function usbVideos(onProgress?: (n: number) => void): Promise<LocalVideo[]> {
  const f = fs();
  if (!f?.listDirectory) return [];
  const storages = await new Promise<Storage[]>((resolve) => { try { f.listStorages(resolve, () => resolve([])); } catch { resolve([]); } });
  const out: LocalVideo[] = [];
  const t0 = Date.now();
  let seen = 0;
  const list = (path: string) => new Promise<string[]>((resolve) => { try { f.listDirectory!(path, resolve, () => resolve([])); } catch { resolve([]); } });
  const walk = async (storage: string, path: string, depth: number) => {
    if (depth > MAX_DEPTH || seen > MAX_ENTRIES || out.length >= MAX_VIDEOS || Date.now() - t0 > MAX_MS) return;
    for (const name of await list(path)) {
      if (name.startsWith('.')) continue;
      seen++;
      const full = `${path}/${name}`;
      let dir = false;
      try { dir = f.isDirectory ? f.isDirectory(full) : !/\.[a-z0-9]{2,4}$/i.test(name); } catch { dir = false; }
      if (dir) await walk(storage, full, depth + 1);
      else if (VIDEO.test(name)) {
        let uri = full;
        try { uri = f.toURI ? f.toURI(full) : `file:///${full}`; } catch { /* keep the virtual path */ }
        out.push({ title: name.replace(VIDEO, ''), uri, folder: path.substring(storage.length + 1) || '/', storage });
        onProgress?.(out.length);
      }
      if (out.length >= MAX_VIDEOS) return;
    }
  };
  for (const s of storages) {
    if (s.type !== 'EXTERNAL' || (s.state && s.state !== 'MOUNTED')) continue;
    await walk(s.label, s.label, 0);
  }
  return out;
}
