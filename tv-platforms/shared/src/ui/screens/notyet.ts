/**
 * Sections of the Android app that this edition does not have yet, or that the TV system does not
 * allow. They say so plainly instead of showing an empty screen (docs/PARITY.md lists each one).
 */
import type { Ctx, Screen } from '../app';
import { h } from '../dom';
import { t, Key } from '../../i18n/index';
import { esc } from '../../core/text';

export function notAvailable(title: Key, reason: Key) {
  return (_ctx: Ctx): Screen => ({
    el: h(`<div class="screen pad"><div class="t-display">${esc(t(title))}</div>
      <div class="empty" style="padding-inline-start:0"><div class="t-body dim">${esc(t(reason))}</div></div></div>`),
  });
}
