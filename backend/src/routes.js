'use strict';
// 所有 /api/v1 业务接口。
// 契约见 docs/API.md：除 /api/v1/health 外，请求体与响应体一律是信封 {"e":"..."}（在 http.js 里统一处理）。

const { HttpError } = require('./http');
const { PROTOCOL_VERSION, SERVER_VERSION } = require('./version');

const EVENT_TYPES = new Set(['unlock', 'screen_on', 'screen_off', 'app_open', 'app_close', 'boot']);
const MAX_EVENTS_PER_PUSH = 500;
const MAX_EVENTS_QUERY = 2000;
const MAX_APPS_QUERY = 50;
const MAX_TOP_APPS = 5;
const BUCKET_MS = 600000; // 10 分钟统计桶

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

// ---------- 时间/日期工具（一律按服务端本地时区） ----------

function ymd(d) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

function rangeOfDay(dateStr) {
  const [y, m, d] = dateStr.split('-').map(Number);
  const start = new Date(y, m - 1, d).getTime();
  return [start, start + 86400000];
}

function lastNDates(n) {
  const out = [];
  const now = new Date();
  for (let i = n - 1; i >= 0; i--) {
    out.push(ymd(new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)));
  }
  return out;
}

function int(v) {
  return Number.isInteger(v) ? v : null;
}

function str(v, max) {
  if (typeof v !== 'string') return null;
  const t = v.trim();
  if (!t) return null;
  return max && t.length > max ? t.slice(0, max) : t;
}

// ---------- 公共小工具 ----------

function userPartner(ctx, user) {
  if (!user || !user.partner_id) return null;
  return ctx.st.userById.get(user.partner_id) || null;
}

function partnerInfo(u) {
  if (!u) return null;
  return { uid: u.id, username: u.username, sharing: !!u.sharing };
}

// 未被配对时的 peer 响应兜底（仍然 200，不报 500）
function noPeer(extra) {
  return Object.assign({
    sharing: false,
    updatedAt: null,
    lastSeenAt: null,
    serverTime: Date.now(),
  }, extra || {});
}

// 当天屏幕时长 / 解锁次数 / 使用最多的 App（从 stats + events 聚合）
function todayAggregates(ctx, uid) {
  const [s, e] = rangeOfDay(ymd(new Date()));
  const total = ctx.st.statTotal.get(uid, s, e);
  const unlocks = ctx.st.countUnlocks.get(uid, s, e);
  const apps = ctx.st.statApps.all(uid, s, e, MAX_TOP_APPS);
  return {
    todayScreenMs: Number(total ? total.ms : 0) || 0,
    todayUnlocks: Number(unlocks ? unlocks.n : 0) || 0,
    todayTopApps: apps.map((a) => ({ pkg: a.pkg, label: a.label, ms: Number(a.ms) || 0 })),
  };
}

// 日聚合（自己或对方）：按服务端本地日期切分，保留期外直接截断
function dailyFor(ctx, uid, days) {
  const oldest = Date.now() - ctx.cfg.retentionDays * 86400000;
  const total = new Map();
  const unlocks = new Map();
  const top = new Map();

  for (const r of ctx.st.dailyStats.all(uid, oldest)) total.set(r.d, Number(r.ms) || 0);
  for (const r of ctx.st.dailyUnlocks.all(uid, oldest)) unlocks.set(r.d, Number(r.n) || 0);
  for (const r of ctx.st.topAppPerDay.all(uid, oldest)) top.set(r.d, r.label || r.pkg || null);

  // 只返回保留期内、且有数据的日期，按日期升序，最多 days 天
  return lastNDates(ctx.cfg.retentionDays)
    .filter((d) => total.has(d) || unlocks.has(d))
    .slice(-days)
    .map((d) => ({
      date: d,
      totalMs: total.get(d) || 0,
      unlocks: unlocks.get(d) || 0,
      topAppLabel: top.get(d) || null,
    }));
}

// 归一化一条事件，非法返回 null
function normalizeEvent(e) {
  if (!e || typeof e !== 'object') return null;
  const ts = int(e.ts);
  const type = str(e.type, 32);
  if (ts === null || ts <= 0 || !type || !EVENT_TYPES.has(type)) return null;
  const needPkg = type === 'app_open' || type === 'app_close';
  const pkg = str(e.pkg, 200);
  const label = str(e.label, 200);
  if (needPkg && !pkg) return null;
  // SQLite 的 UNIQUE 索引把 NULL 视为互不相等，所以用空串代替 NULL 存储无包事件，幂等去重才生效
  return { ts, type, pkg: pkg || '', label: label || null };
}

// 归一化一个统计桶，非法返回 null；ts 对齐到统计间隔整点
function normalizeStat(s, statsIntervalSec) {
  if (!s || typeof s !== 'object') return null;
  const ts = int(s.ts);
  const pkg = str(s.pkg, 200);
  const ms = int(s.ms);
  if (ts === null || ts <= 0 || !pkg || ms === null || ms < 0) return null;
  const step = Math.max(1, Number(statsIntervalSec) || 600) * 1000;
  return { ts: Math.floor(ts / step) * step, pkg, label: str(s.label, 200) || null, ms };
}

// ---------- 路由注册 ----------

function register(router, deps) {
  const { db, cfg, crypto, amap } = deps;
  const st = db.st;

  // 4.12 健康检查（唯一明文接口）
  router.get('/api/v1/health', () => ({
    ok: true,
    service: 'ourspace',
    retentionDays: cfg.retentionDays,
    amap: amap.enabled(cfg.amapKey),
    // 版本信息：客户端用它判断前后端是否配套
    protocolVersion: PROTOCOL_VERSION,
    serverVersion: SERVER_VERSION,
  }), { plain: true, open: true });

  // 4.1 登录
  router.post('/api/v1/auth/login', (ctx) => {
    const username = str(ctx.body.username, 64);
    const password = typeof ctx.body.password === 'string' ? ctx.body.password : '';
    if (!username || !password) throw new HttpError(400, 'bad_request', '缺少 username 或 password');
    const u = st.userByName.get(username);
    if (!u || !crypto.verifyPassword(password, u.pass_hash)) {
      throw new HttpError(401, 'unauthorized', '用户名或密码错误');
    }
    const now = Date.now();
    st.touchSeen.run(now, u.id);
    return {
      token: crypto.signToken(cfg.jwtSecret, { uid: u.id, username: u.username, pv: Number(u.pv) || 0 }),
      uid: u.id,
      username: u.username,
      partner: partnerInfo(userPartner(ctx, u)),
      serverTime: now,
      retentionDays: cfg.retentionDays,
      // 版本信息：客户端登录时比对，不匹配会在登录页明确报错而不是默默出错
      protocolVersion: PROTOCOL_VERSION,
      serverVersion: SERVER_VERSION,
    };
  }, { open: true });

  // 4.2 登出（简单实现：pv+1 让旧 token 全部失效）
  router.post('/api/v1/auth/logout', (ctx) => {
    st.bumpPv.run(Date.now(), ctx.user.id);
    return { ok: true };
  });

  // 4.3 上传（离线可攒批）
  router.post('/api/v1/push', (ctx) => {
    const uid = ctx.user.id;
    const now = Date.now();
    const body = ctx.body;

    const rawEvents = Array.isArray(body.events) ? body.events.slice(0, MAX_EVENTS_PER_PUSH) : [];
    const rawStats = Array.isArray(body.stats) ? body.stats : [];
    let acceptedEvents = 0;
    let acceptedStats = 0;

    // 批量写入放进一个事务，快很多
    db.raw.exec('BEGIN');
    try {
      for (const e of rawEvents) {
        const ev = normalizeEvent(e);
        if (!ev) continue;
        const r = st.putEvent.run(uid, ev.ts, ev.type, ev.pkg, ev.label);
        if (Number(r.changes) > 0) acceptedEvents++;
      }
      for (const s of rawStats) {
        const sb = normalizeStat(s, cfg.statsIntervalSec);
        if (!sb) continue;
        st.putStat.run(uid, sb.ts, sb.pkg, sb.label, sb.ms);
        acceptedStats++;
      }
      if (body.sharing !== undefined) st.setSharing.run(body.sharing ? 1 : 0, uid);
      st.touchSeen.run(now, uid);
      if (body.live && typeof body.live === 'object' && !Array.isArray(body.live)) {
        st.putLive.run(uid, JSON.stringify(body.live), now);
      }
      db.raw.exec('COMMIT');
    } catch (e) {
      try { db.raw.exec('ROLLBACK'); } catch { /* 忽略 */ }
      throw e;
    }

    // 逆地理编码。
    //
    // 之前只在 /peer/live 里算地址，结果「自己的位置」永远没有地址——因为只有对方来读你的
    // live 时才会触发计算，一个手机自己测试时永远不会有人来读。
    // 现在 push 的时候也顺带给出地址：
    //   - 命中缓存（含「算不出来」的否定缓存）就同步返回；
    //   - 没命中就先返回 null，同时异步去算，把结果放进缓存，下一次 push 或对方读 live 就有了。
    let address = null;
    let addressAt = 0;
    if (body.live && typeof body.live === 'object') {
      const lat = Number(body.live.lat);
      const lon = Number(body.live.lon);
      // 0,0 是「没有定位」的占位值，别拿去问高德
      if (Number.isFinite(lat) && Number.isFinite(lon) && (lat !== 0 || lon !== 0)) {
        const cached = amap.peekCached(lat, lon);
        if (cached !== undefined) {
          address = cached;
          addressAt = now;
        } else {
          // 不阻塞响应，算完自然会进缓存
          amap.reverseGeocode(cfg.amapKey, lat, lon).catch(() => {});
        }
      }
    }

    return {
      ok: true,
      serverTime: now,
      accepted: { events: acceptedEvents, stats: acceptedStats },
      address,
      addressAt,
    };
  });

  // 4.11 我的信息
  router.get('/api/v1/me', (ctx) => {
    const u = ctx.user;
    return {
      uid: u.id,
      username: u.username,
      sharing: !!u.sharing,
      partner: partnerInfo(userPartner(ctx, u)),
      retentionDays: cfg.retentionDays,
      serverTime: Date.now(),
    };
  });

  // 4.6 我的事件
  router.get('/api/v1/me/events', (ctx) => eventsFor(ctx, ctx.user.id, ctx.query.date, ctx.query.since));

  // 4.9 我的日汇总
  router.get('/api/v1/me/daily', (ctx) => ({
    days: dailyFor(ctx, ctx.user.id, clampDays(ctx.query.days, cfg.retentionDays)),
    serverTime: Date.now(),
  }));

  // 4.7b 我的统计（形状与 peer/stats 完全相同；客户端重装后用它回填本地库）
  router.get('/api/v1/me/stats', (ctx) => statsFor(ctx, ctx.user.id, ctx.query.date));

  // 4.4 对方实时状态
  router.get('/api/v1/peer/live', async (ctx) => {
    const partner = userPartner(ctx, ctx.user);
    if (!partner) return noPeer();
    const now = Date.now();
    const row = st.getLive.get(partner.id);
    let live = null;
    if (row) {
      try { live = JSON.parse(row.json); } catch { live = null; }
    }
    const agg = todayAggregates(ctx, partner.id);

    // 高德逆地理编码：无 key / 失败 -> null
    let address = null;
    if (live && live.lat != null && live.lon != null) {
      address = await amap.reverseGeocode(cfg.amapKey, live.lat, live.lon);
    }

    return {
      sharing: !!partner.sharing,
      updatedAt: row ? Number(row.updated_at) : null,
      lastSeenAt: partner.last_seen ? Number(partner.last_seen) : null,
      battery: live && live.battery != null ? live.battery : null,
      charging: live ? !!live.charging : false,
      wifi: live && live.wifi != null ? live.wifi : null,
      net: live && live.net != null ? live.net : 'none',
      lat: live && live.lat != null ? live.lat : null,
      lon: live && live.lon != null ? live.lon : null,
      locAcc: live && live.locAcc != null ? live.locAcc : null,
      address,
      addressAt: live ? now : null,
      screenOn: live ? !!live.screenOn : false,
      // 当前正在前台使用的应用名（客户端读 UsageStats 得到，后端只做透传）
      currentApp: live && live.currentApp ? String(live.currentApp) : null,
      todayScreenMs: agg.todayScreenMs,
      todayUnlocks: agg.todayUnlocks,
      todayTopApps: agg.todayTopApps,
      serverTime: now,
    };
  });

  // 4.5 对方事件
  router.get('/api/v1/peer/events', (ctx) => {
    const partner = userPartner(ctx, ctx.user);
    if (!partner) return noPeer({ date: ctx.query.date || ymd(new Date()), since: 0, items: [] });
    return eventsFor(ctx, partner.id, ctx.query.date, ctx.query.since);
  });

  // 4.7 对方统计
  router.get('/api/v1/peer/stats', (ctx) => {
    const partner = userPartner(ctx, ctx.user);
    if (!partner) return noPeer({ date: ctx.query.date || ymd(new Date()), totalMs: 0, apps: [], hourly: emptyHourly() });
    return statsFor(ctx, partner.id, ctx.query.date);
  });

  // 4.8 对方日汇总
  router.get('/api/v1/peer/daily', (ctx) => {
    const partner = userPartner(ctx, ctx.user);
    if (!partner) return noPeer({ days: [] });
    return { days: dailyFor(ctx, partner.id, clampDays(ctx.query.days, cfg.retentionDays)), serverTime: Date.now() };
  });

  // 4.10 暂停 / 恢复共享
  router.post('/api/v1/share', (ctx) => {
    const sharing = ctx.body.sharing ? 1 : 0;
    st.setSharing.run(sharing, ctx.user.id);
    return { sharing: !!sharing };
  });

  return router;
}

function clampDays(v, retentionDays) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return retentionDays;
  return Math.min(Math.floor(n), retentionDays);
}

function parseDate(q) {
  return q && DATE_RE.test(q) ? q : ymd(new Date());
}

function emptyHourly() {
  const out = [];
  for (let h = 0; h < 24; h++) out.push({ hour: h, ms: 0 });
  return out;
}

// 事件列表（自己/对方共用），按 ts 倒序，最多 2000 条。
// since：增量同步游标——客户端告诉我们「这一天的瞬间我已经拿到哪一条了」，
//        只回比它新的即可（含等于，边界上重复的那条由客户端按唯一约束去重）。
//        不传或传 0 就是全量拉这一天。
function eventsFor(ctx, uid, datedQuery, sinceQuery) {
  const date = parseDate(datedQuery);
  const [dayStart, dayEnd] = rangeOfDay(date);
  const cutoff = Date.now() - ctx.cfg.retentionDays * 86400000; // 保留期外直接截断
  const since = Math.max(0, Number(sinceQuery) || 0);
  const start = Math.max(dayStart, cutoff, since);
  const end = Math.min(dayEnd, Date.now() + 86400000);
  const rows = start >= end ? [] : ctx.st.eventsRange.all(uid, start, end, MAX_EVENTS_QUERY);
  return {
    date,
    since,
    items: rows.map((r) => ({ ts: Number(r.ts), type: r.type, pkg: r.pkg || null, label: r.label })),
    serverTime: Date.now(),
  };
}

// 统计汇总（自己/对方共用）
function statsFor(ctx, uid, datedQuery) {
  const date = parseDate(datedQuery);
  const [dayStart, dayEnd] = rangeOfDay(date);
  const cutoff = Date.now() - ctx.cfg.retentionDays * 86400000;
  const start = Math.max(dayStart, cutoff);
  const end = Math.min(dayEnd, Date.now() + 86400000);

  const hourly = emptyHourly();
  if (start >= end) return { date, totalMs: 0, apps: [], hourly, serverTime: Date.now() };

  const apps = ctx.st.statApps.all(uid, start, end, MAX_APPS_QUERY);
  for (const h of ctx.st.statHourly.all(uid, start, end)) {
    const idx = Number(h.hour);
    if (idx >= 0 && idx < 24) hourly[idx].ms = Number(h.ms) || 0;
  }
  return {
    date,
    totalMs: apps.reduce((a, x) => a + (Number(x.ms) || 0), 0),
    apps: apps.map((a) => ({ pkg: a.pkg, label: a.label, ms: Number(a.ms) || 0 })),
    hourly,
    serverTime: Date.now(),
  };
}

module.exports = {
  register, BUCKET_MS, MAX_EVENTS_PER_PUSH, MAX_EVENTS_QUERY, MAX_APPS_QUERY, MAX_TOP_APPS,
  ymd, rangeOfDay, lastNDates, normalizeEvent, normalizeStat, dailyFor, todayAggregates,
};
