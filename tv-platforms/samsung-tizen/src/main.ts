/** StreamX Samsung entry point. */
import '@shared/ui/styles.css';
import { startApp } from '@shared/ui/app';
import { SCREENS } from '@shared/ui/screens/index';
import type { CatalogFile } from '@shared/core/importer';
import catalog from '../../shared/assets/catalog.json';
import { TizenPlatform } from './platform';

void startApp(new TizenPlatform(), SCREENS, catalog as unknown as CatalogFile).catch((e) => {
  console.error(e);
  const app = document.getElementById('app');
  if (app) app.textContent = `StreamX: ${e instanceof Error ? e.message : String(e)}`;
});
