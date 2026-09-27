'use strict';
// 读取 config.json（与 package.json 同级），缺失时给出明确指引。
// 支持用环境变量 PORT 覆盖端口。

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..'); // backend/
const CONFIG_PATH = path.join(ROOT, 'config.json');

// 读取并校验配置；configPath 省略时用 backend/config.json
function load(configPath) {
  const file = configPath ? path.resolve(configPath) : CONFIG_PATH;
  if (!fs.existsSync(file)) {
    throw new Error(
      '找不到配置文件：' + file + '\n' +
      '请先复制模板：copy config.example.json config.json （Linux/macOS: cp config.example.json config.json）\n' +
      '然后修改 payloadSecret / jwtSecret（与 App 设置页里填的 payloadSecret 必须完全一致）。'
    );
  }
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    throw new Error('配置文件不是合法 JSON：' + file + '\n' + e.message);
  }

  const cfg = {
    port: Number(raw.port) || 8080,
    host: raw.host || '0.0.0.0',
    tls: {
      enabled: !!(raw.tls && raw.tls.enabled),
      cert: (raw.tls && raw.tls.cert) || '',
      key: (raw.tls && raw.tls.key) || '',
    },
    payloadSecret: raw.payloadSecret || '',
    jwtSecret: raw.jwtSecret || '',
    amapKey: raw.amapKey || '',
    // 相对路径按 backend/ 解析，保证在任何工作目录下启动结果一致
    db: path.isAbsolute(raw.db || '') ? raw.db : path.join(ROOT, raw.db || 'data/ourspace.db'),
    retentionDays: Number(raw.retentionDays) > 0 ? Math.floor(Number(raw.retentionDays)) : 7,
    liveIntervalSec: Number(raw.liveIntervalSec) > 0 ? Number(raw.liveIntervalSec) : 60,
    statsIntervalSec: Number(raw.statsIntervalSec) > 0 ? Number(raw.statsIntervalSec) : 600,
  };

  if (process.env.PORT) cfg.port = Number(process.env.PORT) || cfg.port;

  if (!cfg.payloadSecret || cfg.payloadSecret.startsWith('改成')) {
    throw new Error('config.json 里的 payloadSecret 还没设置，请填一串长随机字符（与 App 里填的完全一致）。');
  }
  if (!cfg.jwtSecret || cfg.jwtSecret.startsWith('另一串')) {
    throw new Error('config.json 里的 jwtSecret 还没设置，请填一串长随机字符。');
  }
  if (cfg.tls.enabled && (!cfg.tls.cert || !cfg.tls.key)) {
    throw new Error('config.json 里 tls.enabled=true，但 tls.cert / tls.key 为空。');
  }
  return cfg;
}

module.exports = { load, CONFIG_PATH, ROOT };
