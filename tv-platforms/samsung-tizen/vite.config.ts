import { defineConfig } from 'vite';
import { tvConfig } from '../tools/vite-tv';

export default defineConfig(tvConfig('samsung-tizen', [
  { from: 'config.xml', to: 'config.xml', version: true },
  { from: '../shared/assets/img/icon.png', to: 'icon.png' },
]));
