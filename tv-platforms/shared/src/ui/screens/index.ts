/** Every screen of the shared interface, by route. Both editions start the app with this table. */
import type { Route, ScreenFactory } from '../app';
import { homeScreen } from './home';
import { liveScreen } from './live';
import { vodScreen } from './vod';
import { searchScreen } from './search';
import { playlistsScreen } from './playlists';
import { settingsScreen } from './settings';
import { playerScreen } from './player';
import { guideScreen } from './guide';
import { notAvailable } from './notyet';

export const SCREENS: Record<Route, ScreenFactory> = {
  home: homeScreen,
  live: liveScreen,
  movies: vodScreen('movies'),
  series: vodScreen('series'),
  animation: vodScreen('animation'),
  sports: notAvailable('nav_sports', 'tv_not_yet'),
  local: notAvailable('nav_local', 'tv_blocked_local'),
  search: searchScreen,
  playlists: playlistsScreen,
  settings: settingsScreen,
  player: playerScreen,
  guide: guideScreen,
};
