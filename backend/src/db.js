'use strict';
// SQLite（node:sqlite 内置模块）：建表、预编译语句、保留期清理。

const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  pass_hash TEXT NOT NULL,
  partner_id INTEGER,
  sharing INTEGER NOT NULL DEFAULT 1,
  pv INTEGER NOT NULL DEFAULT 0,
  last_seen INTEGER,
  created INTEGER
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY,
  uid INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  type TEXT NOT NULL,
  pkg TEXT,
  label TEXT,
  UNIQUE(uid, ts, type, pkg)
);
CREATE INDEX IF NOT EXISTS idx_events_uid_ts ON events(uid, ts);
-- 注意：无包事件（解锁/亮屏/熄屏/开机）在 normalizeEvent 里已经被统一存成空串而不是 NULL，
-- 否则 SQLite 的 UNIQUE(uid,ts,type,pkg) 对 NULL 不起作用，幂等去重会失效。
-- 这里不再额外建表达式索引，保持简单。
CREATE TABLE IF NOT EXISTS stats (
  id INTEGER PRIMARY KEY,
  uid INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  pkg TEXT NOT NULL,
  label TEXT,
  ms INTEGER NOT NULL,
  UNIQUE(uid, ts, pkg)
);
CREATE INDEX IF NOT EXISTS idx_stats_uid_ts ON stats(uid, ts);
CREATE TABLE IF NOT EXISTS live (
  uid INTEGER PRIMARY KEY,
  json TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
`;

function open(dbPath) {
  const dir = path.dirname(dbPath);
  if (dir && !fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  const db = new DatabaseSync(dbPath);
  db.exec('PRAGMA journal_mode = WAL;');
  db.exec('PRAGMA synchronous = NORMAL;');
  db.exec('PRAGMA foreign_keys = ON;');
  db.exec(SCHEMA);

  // 一次性清理历史遗留的重复事件（老版本因为 NULL 去重失效而积累下来的）。
  // 两个人的数据量很小，启动时跑一次开销可以忽略。
  try {
    const removed = db.exec(
      "DELETE FROM events WHERE id NOT IN (" +
      "  SELECT MIN(id) FROM events GROUP BY uid, ts, type, IFNULL(pkg, '')" +
      ")"
    );
    void removed;
  } catch { /* 清理失败不影响启动 */ }

  const st = {
    userByName: db.prepare('SELECT * FROM users WHERE username = ?'),
    userById: db.prepare('SELECT * FROM users WHERE id = ?'),
    listUsers: db.prepare('SELECT * FROM users ORDER BY id'),
    insertUser: db.prepare('INSERT INTO users (username, pass_hash, partner_id, sharing, pv, created) VALUES (?, ?, NULL, 1, 0, ?)'),
    setPass: db.prepare('UPDATE users SET pass_hash = ?, pv = ? WHERE id = ?'),
    setPartner: db.prepare('UPDATE users SET partner_id = ? WHERE id = ?'),
    setSharing: db.prepare('UPDATE users SET sharing = ? WHERE id = ?'),
    bumpPv: db.prepare('UPDATE users SET pv = ? WHERE id = ?'),
    touchSeen: db.prepare('UPDATE users SET last_seen = ? WHERE id = ?'),

    // 事件幂等：插入过的 (uid,ts,type,pkg) 直接忽略。
    // 用 INSERT OR IGNORE 而不是点名 ON CONFLICT 目标，覆盖任何唯一索引都更稳。
    putEvent: db.prepare(
      'INSERT OR IGNORE INTO events (uid, ts, type, pkg, label) VALUES (?, ?, ?, ?, ?)'
    ),
    // stats 冲突取 ms 最大值
    putStat: db.prepare(
      'INSERT INTO stats (uid, ts, pkg, label, ms) VALUES (?, ?, ?, ?, ?) ' +
      'ON CONFLICT(uid, ts, pkg) DO UPDATE SET ms = max(stats.ms, excluded.ms), label = excluded.label'
    ),
    // live 覆盖写入
    putLive: db.prepare(
      'INSERT INTO live (uid, json, updated_at) VALUES (?, ?, ?) ON CONFLICT(uid) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at'
    ),
    getLive: db.prepare('SELECT json, updated_at FROM live WHERE uid = ?'),

    eventsRange: db.prepare(
      'SELECT ts, type, pkg, label FROM events WHERE uid = ? AND ts >= ? AND ts < ? ORDER BY ts DESC LIMIT ?'
    ),
    statApps: db.prepare(
      'SELECT pkg, max(label) AS label, sum(ms) AS ms FROM stats WHERE uid = ? AND ts >= ? AND ts < ? GROUP BY pkg ORDER BY ms DESC LIMIT ?'
    ),
    statHourly: db.prepare(
      'SELECT CAST(strftime(\'%H\', ts / 1000, \'unixepoch\', \'localtime\') AS INTEGER) AS hour, sum(ms) AS ms ' +
      'FROM stats WHERE uid = ? AND ts >= ? AND ts < ? GROUP BY hour'
    ),
    statTotal: db.prepare('SELECT COALESCE(sum(ms), 0) AS ms FROM stats WHERE uid = ? AND ts >= ? AND ts < ?'),
    countUnlocks: db.prepare('SELECT count(*) AS n FROM events WHERE uid = ? AND type = \'unlock\' AND ts >= ? AND ts < ?'),
    // 日聚合：直接按本地日期字符串分组，避免时区换算
    dailyStats: db.prepare(
      'SELECT date(ts / 1000, \'unixepoch\', \'localtime\') AS d, sum(ms) AS ms FROM stats WHERE uid = ? AND ts >= ? GROUP BY d'
    ),
    dailyUnlocks: db.prepare(
      'SELECT date(ts / 1000, \'unixepoch\', \'localtime\') AS d, count(*) AS n FROM events WHERE uid = ? AND type = \'unlock\' AND ts >= ? GROUP BY d'
    ),
    topAppPerDay: db.prepare(
      'SELECT d, pkg, label, ms FROM (SELECT date(ts / 1000, \'unixepoch\', \'localtime\') AS d, pkg, max(label) AS label, sum(ms) AS ms, ' +
      'row_number() OVER (PARTITION BY date(ts / 1000, \'unixepoch\', \'localtime\') ORDER BY sum(ms) DESC) AS rn ' +
      'FROM stats WHERE uid = ? AND ts >= ? GROUP BY d, pkg) WHERE rn = 1'
    ),

    cleanEvents: db.prepare('DELETE FROM events WHERE ts < ?'),
    cleanStats: db.prepare('DELETE FROM stats WHERE ts < ?'),
  };

  return {
    raw: db,
    st,
    close() { try { db.close(); } catch { /* 忽略 */ } },

    // 删除早于保留期的数据，返回删除条数
    cleanup(retentionDays) {
      const cutoff = Date.now() - retentionDays * 86400000;
      const a = st.cleanEvents.run(cutoff);
      const b = st.cleanStats.run(cutoff);
      return { events: Number(a.changes), stats: Number(b.changes) };
    },
  };
}

module.exports = { open };
