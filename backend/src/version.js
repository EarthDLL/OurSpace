'use strict';

/**
 * 前后端版本信息。
 *
 * PROTOCOL_VERSION：接口协议版本。**只要改了字段、语义或加密信封格式，就必须 +1。**
 *   客户端内置同一个常量，登录时会比对；不一致会在登录页明确提示「前后端版本不匹配」，
 *   避免以后升级时出现「字段对不上、数据默默出错」这种最难查的问题。
 *
 * SERVER_VERSION：后端软件版本，只是给人看的，取自 package.json。
 */
const PROTOCOL_VERSION = 1;

let SERVER_VERSION = '1.0.0';
try {
  const pkg = require('../package.json');
  if (pkg && pkg.version) SERVER_VERSION = String(pkg.version);
} catch {
  // package.json 读不到就用默认值，不影响运行
}

module.exports = { PROTOCOL_VERSION, SERVER_VERSION };
