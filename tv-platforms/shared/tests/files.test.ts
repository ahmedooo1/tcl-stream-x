/** Samsung "Mes fichiers" walk against a fake tizen.filesystem (the real one exists only on the TV). */
import { afterEach, describe, expect, it } from 'vitest';
import { hasFiles, usbVideos } from '../../samsung-tizen/src/files';

const tree: Record<string, string[]> = {
  removable_sda1: ['Films', 'Séries', '.Trash', 'notes.txt'],
  'removable_sda1/Films': ['Nosferatu.mp4', 'Metropolis.MKV', 'cover.jpg'],
  'removable_sda1/Séries': ['S01'],
  'removable_sda1/Séries/S01': ['E01.ts', 'E02.ts'],
  internal: ['Secret.mp4'],
};
function fakeTizen() {
  (globalThis as unknown as { window: unknown }).window = { tizen: { filesystem: {
    listStorages: (ok: (s: unknown[]) => void) => ok([{ label: 'removable_sda1', type: 'EXTERNAL', state: 'MOUNTED' }, { label: 'internal', type: 'INTERNAL', state: 'MOUNTED' }]),
    listDirectory: (p: string, ok: (n: string[]) => void, err: () => void) => (tree[p] ? ok(tree[p]) : err()),
    isDirectory: (p: string) => p in tree,
    toURI: (p: string) => 'file:///opt/media/USBDriveA1/' + p.split('/').slice(1).join('/'),
  } } };
}
afterEach(() => { delete (globalThis as { window?: unknown }).window; });

describe('Samsung USB videos', () => {
  it('lists the videos of mounted external storages only, by folder', async () => {
    fakeTizen();
    expect(hasFiles()).toBe(true);
    const v = await usbVideos();
    expect(v.map((x) => [x.folder, x.title, x.uri])).toEqual([
      ['Films', 'Nosferatu', 'file:///opt/media/USBDriveA1/Films/Nosferatu.mp4'],
      ['Films', 'Metropolis', 'file:///opt/media/USBDriveA1/Films/Metropolis.MKV'],
      ['Séries/S01', 'E01', 'file:///opt/media/USBDriveA1/Séries/S01/E01.ts'],
      ['Séries/S01', 'E02', 'file:///opt/media/USBDriveA1/Séries/S01/E02.ts'],
    ]);
  });
  it('reports nothing where the system has no file access', async () => {
    (globalThis as unknown as { window: unknown }).window = {};
    expect(hasFiles()).toBe(false);
    expect(await usbVideos()).toEqual([]);
  });
});
