// 下载 Temurin JDK 21（Windows x64）到工作区，避免依赖本机 JDK 25。
// 用法： node tools/fetch-jdk21.mjs
import fs from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';

const url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse';
const dir = path.resolve('tools');
fs.mkdirSync(dir, { recursive: true });
const out = path.join(dir, 'jdk21.zip');

console.log('GET', url);
const res = await fetch(url, { redirect: 'follow' });
if (!res.ok) throw new Error('HTTP ' + res.status + ' ' + res.statusText);

const total = Number(res.headers.get('content-length') || 0);
let got = 0;
let last = 0;
const body = Readable.fromWeb(res.body);
body.on('data', (c) => {
  got += c.length;
  const pct = total ? ((got / total) * 100).toFixed(1) : '?';
  if (got - last > 20 * 1024 * 1024) {
    last = got;
    console.log(`  ...${pct}%  ${(got / 1048576).toFixed(0)}MB`);
  }
});
await pipeline(body, fs.createWriteStream(out));
console.log('saved', out, (fs.statSync(out).size / 1048576).toFixed(1) + 'MB');
