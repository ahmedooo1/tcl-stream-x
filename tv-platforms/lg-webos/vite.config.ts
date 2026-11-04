import { defineConfig } from 'vite';
import { tvConfig } from '../tools/vite-tv';

export default defineConfig(tvConfig('lg-webos', [
  { from: 'appinfo.json', to: 'appinfo.json', version: true },
  { from: 'icon.png', to: 'icon.png' },
  { from: 'largeIcon.png', to: 'largeIcon.png' },
]));
