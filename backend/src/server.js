'use strict';
// 入口：按 config.tls 决定 http 或 https；启动时与每小时各清理一次过期数据。

process.removeAllListeners('warning'); // 抑制 node:sqlite 的 ExperimentalWarning

const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');

const config = require('./config');
const { createApp } = require('./app');

function main() {
  const cfg = config.load();
  const app = createApp(cfg);

  // 启动即清理一次，之后每小时一次
  const doCleanup = () => {
    try {
      const r = app.db.cleanup(cfg.retentionDays);
      if (r.events || r.stats) {
        console.log('[cleanup] 删除过期 events=' + r.events + ' stats=' + r.stats +
          '（保留 ' + cfg.retentionDays + ' 天）');
      }
    } catch (e) {
      console.error('[cleanup] 失败：' + e.message);
    }
  };
  doCleanup();
  const timer = setInterval(doCleanup, 3600 * 1000);
  timer.unref?.();

  let server;
  if (cfg.tls.enabled) {
    const opt = { cert: fs.readFileSync(cfg.tls.cert), key: fs.readFileSync(cfg.tls.key) };
    server = https.createServer(opt, app.onRequest);
  } else {
    server = http.createServer(app.onRequest);
  }

  server.listen(cfg.port, cfg.host, () => {
    const scheme = cfg.tls.enabled ? 'https' : 'http';
    console.log('OurSpace 后端已启动: ' + scheme + '://' + cfg.host + ':' + cfg.port);
    console.log('  数据库: ' + cfg.db);
    console.log('  保留期: ' + cfg.retentionDays + ' 天  TLS: ' + (cfg.tls.enabled ? 'on' : 'off') +
      '  高德: ' + (require('./amap').enabled(cfg.amapKey) ? 'on' : 'off'));
  });

  server.on('error', (e) => {
    console.error('监听失败：' + e.message);
    process.exit(1);
  });

  const shutdown = () => {
    console.log('正在关闭…');
    server.close(() => {
      try { app.db.close(); } catch { /* 忽略 */ }
      process.exit(0);
    });
    setTimeout(() => process.exit(0), 3000).unref?.();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (require.main === module) main();

module.exports = { main };
