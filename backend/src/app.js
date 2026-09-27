'use strict';
// 应用装配：配置 + 数据库 + 路由 + 鉴权，供 server.js 和 cli.js 复用。零第三方依赖。

const crypto = require('./crypto');
const { createRouter, makeHandler, HttpError } = require('./http');
const dbmod = require('./db');
const amap = require('./amap');
const routes = require('./routes');

function createApp(cfg) {
  const db = dbmod.open(cfg.db);
  const key = crypto.deriveKey(cfg.payloadSecret);

  const deps = { cfg, db, crypto, amap };

  // 鉴权：Authorization: Bearer <jwt>，校验签名/过期/密码版本
  function authenticate(req) {
    const h = req.headers['authorization'] || '';
    const m = /^Bearer\s+(.+)$/i.exec(h.trim());
    if (!m) throw new HttpError(401, 'unauthorized', '缺少 Bearer token');
    const payload = crypto.verifyToken(cfg.jwtSecret, m[1].trim());
    if (!payload) throw new HttpError(401, 'unauthorized', 'token 无效或已过期');
    const u = db.st.userById.get(payload.uid);
    if (!u || Number(u.pv) !== (Number(payload.pv) || 0)) {
      throw new HttpError(401, 'unauthorized', 'token 已失效，请重新登录');
    }
    return u;
  }

  const router = createRouter();
  routes.register(router, deps);

  const onRequest = makeHandler({ key, router, authenticate, ...deps });

  return { cfg, db, key, onRequest };
}

module.exports = { createApp };
