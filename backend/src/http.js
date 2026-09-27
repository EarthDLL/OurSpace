'use strict';
// 极简 HTTP 层：路由匹配、请求体读取、信封解密/加密、统一错误响应。

const MAX_BODY = 2 * 1024 * 1024; // 正文上限 2MB，超出 413

class HttpError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.status = status;
    this.code = code;
  }
}

function createRouter() {
  const routes = [];
  const add = (method, pattern, handler, opts) => {
    routes.push({
      method,
      parts: pattern.split('/').filter(Boolean),
      handler,
      plain: !!(opts && opts.plain),   // plain: 响应明文（不加密信封）
      open: !!(opts && opts.open),     // open: 不需要 Authorization
    });
  };
  return {
    get: (p, h, o) => add('GET', p, h, o),
    post: (p, h, o) => add('POST', p, h, o),
    match(method, pathname) {
      const segs = pathname.split('/').filter(Boolean);
      for (const r of routes) {
        if (r.method !== method || r.parts.length !== segs.length) continue;
        const params = {};
        let ok = true;
        for (let i = 0; i < r.parts.length; i++) {
          const p = r.parts[i];
          if (p.startsWith(':')) params[p.slice(1)] = decodeURIComponent(segs[i]);
          else if (p !== segs[i]) { ok = false; break; }
        }
        if (ok) return { handler: r.handler, params, plain: r.plain, open: r.open };
      }
      return null; // 未命中（含方法不匹配）
    },
  };
}

// 读取请求体（超过 2MB 抛 413）
function readBody(req) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length'] || 0);
    if (declared > MAX_BODY) {
      reject(new HttpError(413, 'bad_request', '请求体超过 2MB 上限'));
      req.resume();
      return;
    }
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new HttpError(413, 'bad_request', '请求体超过 2MB 上限'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', (e) => reject(new HttpError(400, 'bad_request', '读取请求失败: ' + e.message)));
  });
}

// 明文 JSON（仅 /auth/login 使用）
async function readPlain(req) {
  const raw = await readBody(req);
  if (!raw.length) return {};
  try {
    const obj = JSON.parse(raw.toString('utf8'));
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) throw new Error('not object');
    return obj;
  } catch {
    throw new HttpError(400, 'bad_request', '请求体不是合法 JSON 对象');
  }
}

// 信封 JSON（其余所有业务接口）
async function readEnvelope(req, key) {
  const raw = await readBody(req);
  if (!raw.length) return {};
  let envelope;
  try {
    envelope = JSON.parse(raw.toString('utf8'));
  } catch {
    throw new HttpError(400, 'bad_envelope', '信封解析失败');
  }
  try {
    return require('./crypto').open(key, envelope);
  } catch {
    throw new HttpError(400, 'bad_envelope', '信封解密失败');
  }
}

function sendJson(res, status, obj) {
  const buf = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': buf.length,
    'Cache-Control': 'no-store',
  });
  res.end(buf);
}

// 把业务异常统一成契约里的错误码
function toError(e) {
  if (e instanceof HttpError) return e;
  if (process.env.OURSPACE_DEBUG) console.error('[ourspace] 未捕获异常:', e && e.stack ? e.stack : e);
  return new HttpError(500, 'server_error', (e && e.message) || '服务器内部错误');
}

// 统一的请求处理器：ctx = { params, body, user, plain, db, cfg, key, st, ...deps }
function makeHandler(deps) {
  const { key, router, authenticate } = deps;
  return async function onRequest(req, res) {
    let pathname;
    let query = {};
    try {
      const u = new URL(req.url, 'http://localhost');
      pathname = u.pathname.replace(/\/+$/, '') || '/';
      query = Object.fromEntries(u.searchParams);
    } catch {
      sendJson(res, 400, { error: 'bad_request', message: '非法 URL' });
      return;
    }

    const hit = router.match(req.method, pathname);
    if (!hit) {
      try { await readBody(req); } catch { /* 忽略超限 */ }
      sendJson(res, 404, require('./crypto').seal(key, { error: 'not_found', message: '接口不存在' }));
      return;
    }
    try {
      const body = req.method === 'POST' ? await readEnvelope(req, key) : {};
      // open 路由（health / login）不需要 Bearer token；其余一律要求
      const user = !hit.open && authenticate ? authenticate(req) : null;
      // db.st 是预编译语句集合，业务路由统一从 ctx.st 取用
      const out = await hit.handler({
        ...deps, st: deps.db.st, params: hit.params, query, body, user, plain: hit.plain,
      });
      const payload = out === undefined ? { ok: true } : out;
      sendJson(res, 200, hit.plain ? payload : require('./crypto').seal(key, payload));
    } catch (e) {
      // 错误响应同样走信封（health 除外，它本来就不会抛错）
      const err = toError(e);
      const payload = { error: err.code, message: err.message };
      sendJson(res, err.status, hit.plain ? payload : require('./crypto').seal(key, payload));
    }
  };
}

module.exports = {
  MAX_BODY, HttpError, createRouter, makeHandler, readPlain, sendJson,
};
