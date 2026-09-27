'use strict';
// 端到端自测：临时端口 + 临时 db 文件自起服务，跑完整链路后打印 SMOKE PASS。
// 运行：node test/smoke.js
// 说明：服务与 CLI 都在本进程内启动（不 spawn 子进程），只依赖 node 内置模块。

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');

const ROOT = path.resolve(__dirname, '..');
const crypto = require(path.join(ROOT, 'src', 'crypto.js'));
const { createApp } = require(path.join(ROOT, 'src', 'app.js'));
const cli = require(path.join(ROOT, 'src', 'cli.js'));

const SECRET = 'smoke-test-payload-secret-0123456789';
const KEY = crypto.deriveKey(SECRET);

let passed = 0;
let tmpDir = null;
let server = null;
let app = null;

function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log('  ok   ' + name);
  } else {
    console.error('  FAIL ' + name + (extra !== undefined ? '  ->  ' + JSON.stringify(extra) : ''));
    throw new Error('断言失败：' + name);
  }
}

// 极简 HTTP 客户端：plain=true 时收明文 JSON，否则收信封；raw 为原始字符串（用于坏信封用例）
function call(port, method, urlPath, body, opts) {
  const o = Object.assign({}, opts);
  const isRaw = typeof body === 'string';
  let payload = null;
  if (method === 'POST') {
    if (isRaw) payload = Buffer.from(body, 'utf8');
    else if (o.plain) payload = Buffer.from(JSON.stringify(body || {}), 'utf8');
    else payload = Buffer.from(JSON.stringify(crypto.seal(KEY, body || {})), 'utf8');
  }
  return send(port, method, urlPath, payload, o.token, o.plain);
}

// 发送原始请求体并解析响应（信封自动用 payloadSecret 解开）
function send(port, method, urlPath, payload, token, plain) {
  return new Promise((resolve, reject) => {
    const headers = { 'Content-Type': 'application/json' };
    if (payload) headers['Content-Length'] = payload.length;
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const req = http.request({ host: '127.0.0.1', port, method, path: urlPath, headers }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let parsed;
        try { parsed = JSON.parse(text); } catch { parsed = text; }
        let data = parsed;
        if (!plain && parsed && typeof parsed === 'object' && parsed.e) {
          try { data = crypto.open(KEY, parsed); } catch (e) { data = { __open_failed: e.message }; }
        }
        resolve({ status: res.statusCode, data, raw: text });
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function ymd(d) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function main() {
  // ---------- 准备临时环境 ----------
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ourspace-smoke-'));
  const dbFile = path.join(tmpDir, 'smoke.db');
  const cfgPath = path.join(tmpDir, 'config.json');
  fs.writeFileSync(cfgPath, JSON.stringify({
    port: 0,
    host: '127.0.0.1',
    tls: { enabled: false, cert: '', key: '' },
    payloadSecret: SECRET,
    jwtSecret: 'smoke-jwt-secret-abcdef',
    amapKey: '', // 空 -> 不访问高德，address 应为 null
    db: dbFile,
    retentionDays: 7,
    liveIntervalSec: 60,
    statsIntervalSec: 600,
  }, null, 2));

  const cfg = require(path.join(ROOT, 'src', 'config.js')).load(cfgPath);

  // ---------- 1. CLI 建号 / 配对 ----------
  console.log('== 1. CLI 建号 / 配对 ==');
  const runCli = (args) => {
    const r = cli.run(args.concat(['--config', cfgPath]));
    check('CLI ' + args.join(' ') + ' 成功', r.code === 0, r.lines);
    return r.lines.join('\n');
  };
  console.log('  ' + runCli(['adduser', 'alice', 'pw1']));
  console.log('  ' + runCli(['adduser', 'bob', 'pw2']));
  console.log('  ' + runCli(['link', 'alice', 'bob']));
  console.log('   list 输出:');
  console.log(runCli(['list']).split('\n').map((l) => '     ' + l).join('\n'));

  // ---------- 2. 起服务（临时端口） ----------
  app = createApp(cfg);
  await new Promise((resolve, reject) => {
    server = http.createServer(app.onRequest);
    server.on('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const port = server.address().port;
  console.log('\n== 2. 接口链路（端口 ' + port + '）==');

  try {
    // health（唯一明文接口）
    const health = await call(port, 'GET', '/api/v1/health', null, { plain: true });
    check('health 200 且明文 ok', health.status === 200 && health.data.ok === true && health.data.service === 'ourspace', health);
    check('health 带 protocolVersion / serverVersion',
      Number.isInteger(health.data.protocolVersion) && typeof health.data.serverVersion === 'string', health.data);
    check('health.retentionDays=7', health.data.retentionDays === 7, health.data);
    check('health.amap=false（未配置 key）', health.data.amap === false, health.data);

    // login（信封）
    const la = await call(port, 'POST', '/api/v1/auth/login', { username: 'alice', password: 'pw1', device: 'Smoke' });
    check('alice 登录 200 且拿到 token', la.status === 200 && typeof la.data.token === 'string' && la.data.token.split('.').length === 3, la);
    check('登录响应带 protocolVersion（供客户端做版本校验）',
      Number.isInteger(la.data.protocolVersion) && la.data.protocolVersion > 0, la.data);
    check('alice 响应 partner=bob(sharing=true)', la.data.partner && la.data.partner.username === 'bob' && la.data.partner.sharing === true, la.data);
    check('alice uid/username/serverTime/retentionDays 齐全',
      la.data.uid === 1 && la.data.username === 'alice' && typeof la.data.serverTime === 'number' && la.data.retentionDays === 7, la.data);

    const lb = await call(port, 'POST', '/api/v1/auth/login', { username: 'bob', password: 'pw2' });
    check('bob 登录 200', lb.status === 200 && typeof lb.data.token === 'string', lb);
    const aTok = la.data.token;
    const bTok = lb.data.token;

    // bob 上报
    const now = Date.now();
    const bucket = Math.floor(now / 600000) * 600000;
    const events = [
      { ts: now - 5000, type: 'unlock', pkg: null, label: null },
      { ts: now - 4000, type: 'app_open', pkg: 'com.tencent.mm', label: '微信' },
      { ts: now - 3000, type: 'app_close', pkg: 'com.tencent.mm', label: '微信' },
      { ts: now - 2000, type: 'screen_on', pkg: null, label: null },
      { ts: now - 5000, type: 'unlock', pkg: null, label: null }, // 与第 1 条完全相同 -> 幂等忽略
    ];
    const stats = [
      { ts: bucket + 1000, pkg: 'com.tencent.mm', label: '微信', ms: 60000 },
      { ts: bucket + 1000, pkg: 'com.tencent.mm', label: '微信', ms: 62000 }, // 同桶 -> max
      { ts: bucket + 1000, pkg: 'com.tencent.mm', label: '微信', ms: 30000 }, // 更小 -> 不覆盖
      { ts: bucket + 600000, pkg: 'com.zhihu.android', label: '知乎', ms: 12500 },
    ];
    const push = await call(port, 'POST', '/api/v1/push', {
      events,
      stats,
      live: {
        battery: 82, charging: true, wifi: 'HomeWiFi', net: 'wifi',
        lat: 31.2304, lon: 121.4737, locAcc: 25.0, screenOn: true,
        currentApp: '微信', updatedAt: Date.now(),
      },
      sharing: true,
    }, { token: bTok });
    check('push 200 且 ok', push.status === 200 && push.data.ok === true, push);
    check('push accepted.events=4（重复被去重）', push.data.accepted.events === 4, push.data);
    check('push accepted.stats=4', push.data.accepted.stats === 4, push.data);
    check('push 响应带逆地理编码字段（address/addressAt）',
      'address' in push.data && 'addressAt' in push.data, push.data);
    check('push serverTime 为毫秒整数', Number.isInteger(push.data.serverTime), push.data);

    // alice 读 peer/live
    const live = await call(port, 'GET', '/api/v1/peer/live', null, { token: aTok });
    check('peer/live 200', live.status === 200, live);
    check('peer/live sharing=true', live.data.sharing === true, live.data);
    check('peer/live battery/charging/net 透传', live.data.battery === 82 && live.data.charging === true && live.data.net === 'wifi', live.data);
    check('peer/live wifi/screenOn 透传', live.data.wifi === 'HomeWiFi' && live.data.screenOn === true, live.data);
    check('peer/live currentApp 透传（当前前台应用）', live.data.currentApp === '微信', live.data);
    check('peer/live 坐标透传', live.data.lat === 31.2304 && live.data.lon === 121.4737 && live.data.locAcc === 25, live.data);
    check('peer/live todayScreenMs=74500（max 去重后求和）', live.data.todayScreenMs === 74500, live.data.todayScreenMs);
    check('peer/live todayUnlocks=1', live.data.todayUnlocks === 1, live.data.todayUnlocks);
    check('peer/live todayTopApps 倒序且最多 5',
      live.data.todayTopApps.length === 2 && live.data.todayTopApps[0].pkg === 'com.tencent.mm' &&
      live.data.todayTopApps[0].ms === 62000 && live.data.todayTopApps[1].pkg === 'com.zhihu.android', live.data.todayTopApps);
    check('peer/live address=null（无高德 key）且带 addressAt',
      live.data.address === null && typeof live.data.addressAt === 'number', live.data);
    check('peer/live lastSeenAt/updatedAt 已更新',
      typeof live.data.lastSeenAt === 'number' && typeof live.data.updatedAt === 'number', live.data);

    const today = ymd(new Date());

    // alice 读 peer/events
    const pe = await call(port, 'GET', '/api/v1/peer/events?date=' + today, null, { token: aTok });
    check('peer/events 200 且 date 正确', pe.status === 200 && pe.data.date === today, pe.data);
    check('peer/events 4 条', pe.data.items.length === 4, pe.data.items);
    check('peer/events 按 ts 倒序', pe.data.items[0].ts >= pe.data.items[3].ts, pe.data.items.map((i) => i.ts));
    check('peer/events 字段齐全（ts/type/pkg/label）',
      pe.data.items.every((i) => Number.isInteger(i.ts) && typeof i.type === 'string' && 'pkg' in i && 'label' in i), pe.data.items);

    // ===== 增量同步游标 since =====
    // 取最大 ts 当游标：应当只回 ts >= 它的那几条（边界会重复，客户端按唯一约束去重）
    const maxTs = Math.max(...pe.data.items.map((i) => i.ts));
    const inc1 = await call(port, 'GET', '/api/v1/peer/events?date=' + today + '&since=' + maxTs, null, { token: aTok });
    check('since 增量：只回边界那条', inc1.status === 200 && inc1.data.items.length === 1 && inc1.data.items[0].ts === maxTs, inc1.data);
    check('since 增量：回显 since', inc1.data.since === maxTs, inc1.data);

    const inc2 = await call(port, 'GET', '/api/v1/peer/events?date=' + today + '&since=' + (maxTs + 1), null, { token: aTok });
    check('since 增量：比最新还新 -> 空数组', inc2.status === 200 && inc2.data.items.length === 0, inc2.data);

    const incBad = await call(port, 'GET', '/api/v1/peer/events?date=' + today + '&since=abc', null, { token: aTok });
    check('since 非法值退化为全量', incBad.status === 200 && incBad.data.items.length === 4, incBad.data);

    // alice 读 peer/stats
    const ps = await call(port, 'GET', '/api/v1/peer/stats?date=' + today, null, { token: aTok });
    check('peer/stats 200', ps.status === 200, ps.data);
    check('peer/stats totalMs=74500', ps.data.totalMs === 74500, ps.data);
    check('peer/stats apps 倒序 2 项', ps.data.apps.length === 2 && ps.data.apps[0].ms >= ps.data.apps[1].ms, ps.data.apps);
    check('peer/stats hourly 24 项且合计=totalMs',
      ps.data.hourly.length === 24 && ps.data.hourly.reduce((a, x) => a + x.ms, 0) === 74500 &&
      ps.data.hourly.every((h, i) => h.hour === i), ps.data.hourly);

    // alice 读 peer/daily
    const pd = await call(port, 'GET', '/api/v1/peer/daily?days=7', null, { token: aTok });
    check('peer/daily 200', pd.status === 200, pd.data);
    check('peer/daily 只含今天', pd.data.days.length === 1 && pd.data.days[0].date === today, pd.data.days);
    check('peer/daily 聚合正确（totalMs/unlocks/topAppLabel）',
      pd.data.days[0].totalMs === 74500 && pd.data.days[0].unlocks === 1 && pd.data.days[0].topAppLabel === '微信', pd.data.days);

    // me 系列
    const md = await call(port, 'GET', '/api/v1/me/daily?days=7', null, { token: aTok });
    check('me/daily 无数据返回空数组', md.status === 200 && md.data.days.length === 0, md.data);
    const me = await call(port, 'GET', '/api/v1/me', null, { token: aTok });
    check('me 200 且 partner=bob', me.status === 200 && me.data.username === 'alice' && me.data.partner.username === 'bob', me.data);
    const mev = await call(port, 'GET', '/api/v1/me/events?date=' + today, null, { token: aTok });
    check('me/events 200 空列表', mev.status === 200 && mev.data.items.length === 0, mev.data);

    // me/stats：形状与 peer/stats 完全相同，bob 自己读应拿到同一份聚合
    const mst = await call(port, 'GET', '/api/v1/me/stats?date=' + today, null, { token: bTok });
    check('me/stats 200', mst.status === 200, mst.data);
    check('me/stats 与 peer/stats 一致（totalMs/apps/hourly）',
      mst.data.totalMs === 74500 && mst.data.apps.length === 2 && mst.data.hourly.length === 24, mst.data);

    // 暂停共享
    const share = await call(port, 'POST', '/api/v1/share', { sharing: false }, { token: bTok });
    check('share 200 {sharing:false}', share.status === 200 && share.data.sharing === false, share.data);
    const live2 = await call(port, 'GET', '/api/v1/peer/live', null, { token: aTok });
    check('暂停后 alice 看到 peer/live sharing=false', live2.data.sharing === false, live2.data);
    const meB = await call(port, 'GET', '/api/v1/me', null, { token: bTok });
    check('暂停后 bob 自己 sharing=false', meB.data.sharing === false, meB.data);
    const reShare = await call(port, 'POST', '/api/v1/share', { sharing: true }, { token: bTok });
    check('恢复共享 200 {sharing:true}', reShare.data.sharing === true, reShare.data);

    // 保留期外截断
    const old = await call(port, 'GET', '/api/v1/peer/events?date=2000-01-01', null, { token: aTok });
    check('超期日期返回空列表且不报错', old.status === 200 && old.data.items.length === 0, old.data);
    const oldDaily = await call(port, 'GET', '/api/v1/peer/daily?days=999', null, { token: aTok });
    check('days=999 被截断到保留期（<=7）', oldDaily.data.days.length <= 7, oldDaily.data.days.length);

    // ---------- 3. 错误用例 ----------
    console.log('\n== 3. 错误用例 ==');
    const badPw = await call(port, 'POST', '/api/v1/auth/login', { username: 'alice', password: 'wrong' });
    check('错密码 401 unauthorized', badPw.status === 401 && badPw.data.error === 'unauthorized', badPw);

    // 坏信封：请求体是合法 JSON 形状，但 e 不是合法密文
    const badEnvText = JSON.stringify({ e: '这不是合法信封' });
    const badEnv = await call(port, 'POST', '/api/v1/push', badEnvText, { token: bTok });
    check('坏信封 400 bad_envelope', badEnv.status === 400 && badEnv.data.error === 'bad_envelope', badEnv);
    check('错误响应本身也被信封加密（客户端能解开）', typeof badEnv.data.error === 'string', badEnv.data);

    const noTok = await call(port, 'GET', '/api/v1/me', null, {});
    check('无 token 401 unauthorized', noTok.status === 401 && noTok.data.error === 'unauthorized', noTok);

    const badTok = await call(port, 'GET', '/api/v1/me', null, { token: 'aaa.bbb.ccc' });
    check('伪造 token 401 unauthorized', badTok.status === 401 && badTok.data.error === 'unauthorized', badTok);

    // 用别的 payloadSecret 加密 -> 解不开
    const otherKey = crypto.deriveKey('另一个 payloadSecret');
    const wrongKeyBody = JSON.stringify(crypto.seal(otherKey, { sharing: false }));
    const badKey = await call(port, 'POST', '/api/v1/share', wrongKeyBody, { token: bTok });
    check('用错 payloadSecret 加密 -> 400 bad_envelope', badKey.status === 400 && badKey.data.error === 'bad_envelope', badKey);

    // 请求体根本不是 JSON
    const badJson = await call(port, 'POST', '/api/v1/auth/login', 'not-json-at-all');
    check('请求体不是 JSON -> 400 bad_envelope', badJson.status === 400 && badJson.data.error === 'bad_envelope', badJson);

    const notFound = await call(port, 'GET', '/api/v1/nope', null, { token: aTok });
    check('未知接口 404 not_found', notFound.status === 404 && notFound.data.error === 'not_found', notFound);

    // logout 让旧 token 失效
    const lo = await call(port, 'POST', '/api/v1/auth/logout', {}, { token: bTok });
    check('logout 200 且 ok=true', lo.status === 200 && lo.data.ok === true, lo.data);
    const afterLo = await call(port, 'GET', '/api/v1/me', null, { token: bTok });
    check('logout 后旧 token 401', afterLo.status === 401 && afterLo.data.error === 'unauthorized', afterLo);

    console.log('\nSMOKE PASS（共 ' + passed + ' 条断言全部通过）');
  } finally {
    await new Promise((r) => server.close(r));
    try { app.db.close(); } catch { /* 忽略 */ }
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* 忽略 */ }
  }
}

main().then(() => {
  process.exitCode = 0;
  setTimeout(() => process.exit(0), 50).unref();
}).catch((e) => {
  console.error('\nSMOKE FAIL: ' + (e && e.stack ? e.stack : e));
  process.exitCode = 1;
  setTimeout(() => process.exit(1), 50);
});
