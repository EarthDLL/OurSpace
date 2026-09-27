const { DatabaseSync } = require('node:sqlite');
const db = new DatabaseSync('D:/DSH/OurSpace/.tmp-local.db');
const t = (sql, ...a) => { try { return db.prepare(sql).all(...a); } catch (e) { return [{ err: e.message }]; } };

console.log('=== events 概览 ===');
for (const r of t("SELECT owner, type, count(*) n, max(ts) mx FROM events GROUP BY owner, type ORDER BY owner, type")) {
  console.log(`  ${r.owner}/${r.type}: ${r.n} 条  最新 ${r.mx ? new Date(r.mx).toLocaleTimeString('zh-CN') : '-'}`);
}

console.log('\n=== 会话配对检查（自己）===');
const rows = t("SELECT ts, type, pkg FROM events WHERE owner='me' AND type IN ('app_open','app_close') ORDER BY ts ASC");
let opens = 0, closes = 0, orphan = 0;
const openAt = new Map();
const durList = [];
for (const r of rows) {
  if (r.type === 'app_open') { opens++; openAt.set(r.pkg, r.ts); }
  else {
    closes++;
    const st = openAt.get(r.pkg);
    if (st == null) orphan++;
    else { durList.push(r.ts - st); openAt.delete(r.pkg); }
  }
}
console.log(`  app_open=${opens}  app_close=${closes}  孤儿(无配对 open)=${orphan}`);
if (durList.length) {
  const short = durList.filter((d) => d < 1000).length;
  console.log(`  配对成功 ${durList.length} 个，其中不足 1 秒的 ${short} 个`);
  console.log(`  时长(秒) 最近 12 个: ${durList.slice(-12).map((d) => (d / 1000).toFixed(1)).join(', ')}`);
}

console.log('\n=== day_app 表（服务端天聚合，新表）===');
const da = t("SELECT owner, date, pkg, label, ms FROM day_app ORDER BY owner, ms DESC LIMIT 12");
if (!da.length) console.log('  (空)');
for (const r of da) console.log(`  ${r.owner} ${r.date}  ${r.label || r.pkg}  ${Math.round(r.ms / 1000)}s`);

console.log('\n=== 当天总时长：本地桶 vs 服务端全天合计（应为同一量级，不再翻倍）===');
const d = new Date();
const start = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
const end = start + 86400000;
for (const owner of ['me', 'peer']) {
  const bk = t("SELECT COALESCE(SUM(ms),0) ms FROM stats WHERE owner=? AND ts>=? AND ts<? AND pkg<>?", owner, start, end, '__day_total__')[0].ms;
  const dt = t("SELECT COALESCE(MAX(ms),0) ms FROM stats WHERE owner=? AND ts>=? AND ts<? AND pkg='__day_total__'", owner, start, end)[0].ms;
  const dapp = t("SELECT COALESCE(SUM(ms),0) ms FROM day_app WHERE owner=? AND date=?", owner, d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'))[0].ms;
  console.log(`  [${owner}] 本地10分钟桶合计=${Math.round(bk / 1000)}s  服务端全天合计=${Math.round(dt / 1000)}s  服务端应用聚合合计=${Math.round(dapp / 1000)}s`);
}
