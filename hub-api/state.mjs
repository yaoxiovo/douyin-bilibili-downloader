import { DatabaseSync } from 'node:sqlite';
import { closeSync, mkdirSync, openSync } from 'node:fs';
import { join } from 'node:path';

export function openState(directory) {
  const path = directory ? join(directory, 'state.sqlite') : ':memory:';
  if (directory) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    closeSync(openSync(path, 'a', 0o600));
  }
  const database = new DatabaseSync(path);
  database.exec(`
    CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, token TEXT NOT NULL, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY, title TEXT NOT NULL, detail TEXT NOT NULL, time TEXT NOT NULL);
  `);
  const find = database.prepare('SELECT token, expires FROM sessions WHERE id = ? AND expires > ?');
  const insert = database.prepare('INSERT INTO sessions VALUES (?, ?, ?)');
  const remove = database.prepare('DELETE FROM sessions WHERE id = ?');
  const clearExpired = database.prepare('DELETE FROM sessions WHERE expires <= ?');
  const append = database.prepare('INSERT INTO notifications (title, detail, time) VALUES (?, ?, ?)');
  const recent = database.prepare('SELECT title, detail, time FROM notifications ORDER BY id DESC LIMIT 50');
  const trim = database.prepare('DELETE FROM notifications WHERE id <= (SELECT id FROM notifications ORDER BY id DESC LIMIT 1 OFFSET 50)');
  clearExpired.run(Date.now());

  return {
    findSession: id => find.get(id, Date.now()),
    saveSession(id, token, expires) {
      clearExpired.run(Date.now());
      insert.run(id, token, expires);
    },
    deleteSession: id => remove.run(id),
    pruneSessions: () => clearExpired.run(Date.now()),
    addNotification(title, detail, time) {
      database.exec('BEGIN');
      try {
        append.run(title, detail, time);
        trim.run();
        database.exec('COMMIT');
      } catch (error) {
        database.exec('ROLLBACK');
        throw error;
      }
    },
    notifications: () => recent.all(),
    close: () => database.close()
  };
}
