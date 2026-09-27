'use strict';
// 高德逆地理编码：坐标四舍五入到 3 位小数后做 10 分钟内存缓存，避免刷爆配额。

const CACHE_TTL_MS = 10 * 60 * 1000;
const cache = new Map(); // "lon,lat" -> { address, at }

function enabled(amapKey) {
  const k = String(amapKey || '').trim();
  return k.length > 0 && !k.startsWith('改成');
}

function round3(n) {
  return Math.round(Number(n) * 1000) / 1000;
}

/**
 * 同步地看一眼缓存。
 * @returns 缓存里的地址字符串 / null（缓存里就是「解析不出来」）/ undefined（缓存里没有，需要去算）
 * 用于 push 响应：能立刻给出地址就给，给不了就先返回 null，同时异步补算。
 */
function peekCached(lat, lon) {
  const key = round3(lon) + ',' + round3(lat);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.address;
  return undefined;
}

// 返回地址字符串或 null（无 key / 请求失败 / 高德返回异常一律 null）
async function reverseGeocode(amapKey, lat, lon) {
  if (!enabled(amapKey)) return null;
  if (!Number.isFinite(Number(lat)) || !Number.isFinite(Number(lon))) return null;

  const rLat = round3(lat);
  const rLon = round3(lon);
  const key = rLon + ',' + rLat;
  const now = Date.now();
  const hit = cache.get(key);
  if (hit && now - hit.at < CACHE_TTL_MS) return hit.address;

  let address = null;
  try {
    const url = 'https://restapi.amap.com/v3/geocode/regeo?key=' + encodeURIComponent(amapKey) +
      '&location=' + encodeURIComponent(key) + '&extensions=base&radius=500';
    const ctrl = AbortSignal.timeout(5000);
    const resp = await fetch(url, { signal: ctrl });
    const data = await resp.json();
    if (data && data.status === '1' && data.regeocode && data.regeocode.formatted_address) {
      const fa = data.regeocode.formatted_address;
      address = typeof fa === 'string' ? fa : null;
    }
  } catch {
    address = null; // 网络/超时/解析失败都不影响主流程
  }
  cache.set(key, { address, at: now });
  if (cache.size > 2000) { // 简单防膨胀：清掉最旧的一批
    let i = 0;
    for (const k of cache.keys()) { cache.delete(k); if (++i >= 1000) break; }
  }
  return address;
}

module.exports = { reverseGeocode, peekCached, enabled, CACHE_TTL_MS };
