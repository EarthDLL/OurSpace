// 验证 SQLite 对 UNIQUE 约束里 NULL 的处理（决定去重到底有没有生效）
const { DatabaseSync } = require('node:sqlite');
const db = new DatabaseSync(':memory:');
db.exec('CREATE TABLE t(a INTEGER, b INTEGER, c TEXT, d TEXT, UNIQUE(a,b,c,d))');

// 1) INSERT OR IGNORE，d 为 NULL，两次完全相同的行
db.exec("INSERT OR IGNORE INTO t VALUES (1,1,'x',NULL)");
db.exec("INSERT OR IGNORE INTO t VALUES (1,1,'x',NULL)");
console.log('INSERT OR IGNORE + NULL      ->', db.prepare('SELECT count(*) n FROM t').get().n, '(期望 1)');

// 2) ON CONFLICT 指定目标
const db2 = new DatabaseSync(':memory:');
db2.exec('CREATE TABLE t(a INTEGER, b INTEGER, c TEXT, d TEXT, UNIQUE(a,b,c,d))');
const ins = db2.prepare('INSERT INTO t VALUES (?,?,?,?) ON CONFLICT(a,b,c,d) DO NOTHING');
ins.run(1, 1, 'x', null);
ins.run(1, 1, 'x', null);
console.log('ON CONFLICT(...) + NULL      ->', db2.prepare('SELECT count(*) n FROM t').get().n, '(期望 1)');

// 3) 加表达式唯一索引后同样测试
const db3 = new DatabaseSync(':memory:');
db3.exec('CREATE TABLE t(a INTEGER, b INTEGER, c TEXT, d TEXT)');
db3.exec("CREATE UNIQUE INDEX ix ON t(a,b,c,IFNULL(d,''))");
db3.exec("INSERT OR IGNORE INTO t VALUES (1,1,'x',NULL)");
db3.exec("INSERT OR IGNORE INTO t VALUES (1,1,'x',NULL)");
console.log("表达式唯一索引 + NULL        ->", db3.prepare('SELECT count(*) n FROM t').get().n, '(期望 1)');

// 4) 非 NULL 的情况做对照
const db4 = new DatabaseSync(':memory:');
db4.exec('CREATE TABLE t(a INTEGER, b INTEGER, c TEXT, d TEXT, UNIQUE(a,b,c,d))');
db4.exec("INSERT OR IGNORE INTO t VALUES (1,1,'x','pkg')");
db4.exec("INSERT OR IGNORE INTO t VALUES (1,1,'x','pkg')");
console.log('INSERT OR IGNORE + 非 NULL   ->', db4.prepare('SELECT count(*) n FROM t').get().n, '(期望 1)');
