import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

export default defineConfig({
  resolve: { alias: { '@shared': resolve(__dirname, 'shared/src') } },
  test: {
    include: ['shared/tests/**/*.test.ts'],
    environment: 'node',
    testTimeout: 60000,
  },
});
