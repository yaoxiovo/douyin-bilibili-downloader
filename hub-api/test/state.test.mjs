import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { openState } from '../state.mjs';

test('sessions survive reopening, expire, and remain revoked after logout', () => {
  const directory = mkdtempSync(join(tmpdir(), 'hub-state-'));
  let state = openState(directory);
  try {
    state.saveSession('active', 'upstream', Date.now() + 60_000);
    state.saveSession('expired', 'old', Date.now() - 1);

    state.close();
    state = openState(directory);
    assert.equal(state.findSession('active').token, 'upstream');
    assert.equal(state.findSession('expired'), undefined);
    state.deleteSession('active');

    state.close();
    state = openState(directory);
    assert.equal(state.findSession('active'), undefined);
  } finally {
    state.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test('only the most recent 50 cloud notifications are retained', () => {
  const state = openState();
  try {
    for (let i = 0; i < 55; i++) state.addNotification('DNS saved', String(i), '2026-11-19');
    const notifications = state.notifications();
    assert.equal(notifications.length, 50);
    assert.equal(notifications[0].detail, '54');
    assert.equal(notifications.at(-1).detail, '5');
  } finally {
    state.close();
  }
});
