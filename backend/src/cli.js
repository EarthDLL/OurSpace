'use strict';
// 命令行工具：
//   node src/cli.js adduser <username> <password>
//   node src/cli.js passwd  <username> <password>
//   node src/cli.js link    <usernameA> <usernameB>
//   node src/cli.js unlink  <username>
//   node src/cli.js list
// 可选参数：--config <配置文件路径>（默认 backend/config.json，方便测试或多实例）

process.removeAllListeners('warning'); // 抑制 node:sqlite 的 ExperimentalWarning

const config = require('./config');
const dbmod = require('./db');
const crypto = require('./crypto');

const LOG = console.log;
const ERR = console.error;

function usage() {
  LOG([
    '用法：',
    '  node src/cli.js adduser <username> <password>',
    '  node src/cli.js passwd  <username> <password>',
    '  node src/cli.js link    <usernameA> <usernameB>',
    '  node src/cli.js unlink  <username>',
    '  node src/cli.js list',
    '',
    '可选：--config <配置文件路径>（默认 backend/config.json）',
  ].join('\n'));
}

function fmtTime(ms) {
  if (!ms) return '-';
  return new Date(Number(ms)).toLocaleString();
}

// 执行一条命令；返回 { code, lines }（不直接退出进程，便于被测试复用）
function run(argv) {
  const args = argv.slice();
  let configPath = null;
  const rest = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--config') configPath = args[++i];
    else rest.push(args[i]);
  }
  const [cmd, a1, a2] = rest;
  const lines = [];
  const out = (s) => { lines.push(s); LOG(s); };
  const bad = (s) => { lines.push(s); ERR(s); return { code: 1, lines }; };

  if (!cmd || cmd === '-h' || cmd === '--help' || cmd === 'help') {
    usage();
    return { code: 0, lines };
  }

  let cfg;
  try {
    cfg = config.load(configPath);
  } catch (e) {
    return bad(e.message);
  }
  let db;
  try {
    db = dbmod.open(cfg.db);
  } catch (e) {
    return bad('打开数据库失败：' + e.message);
  }
  const st = db.st;

  const requireUser = (name) => {
    const u = st.userByName.get(name);
    if (!u) throw new Error('用户不存在：' + name);
    return u;
  };

  try {
    switch (cmd) {
      case 'adduser': {
        if (!a1 || !a2) return bad('用法：node src/cli.js adduser <username> <password>');
        if (st.userByName.get(a1)) return bad('用户名已存在：' + a1);
        st.insertUser.run(a1, crypto.hashPassword(a2), Date.now());
        const u = st.userByName.get(a1);
        out('已创建用户 #' + u.id + ' ' + a1);
        return { code: 0, lines };
      }
      case 'passwd': {
        if (!a1 || !a2) return bad('用法：node src/cli.js passwd <username> <password>');
        const u = requireUser(a1);
        // pv 递增 -> 该用户旧 token 立即失效
        st.setPass.run(crypto.hashPassword(a2), Date.now(), u.id);
        out('已更新 ' + a1 + ' 的密码（该用户原有登录 token 全部失效）');
        return { code: 0, lines };
      }
      case 'link': {
        if (!a1 || !a2) return bad('用法：node src/cli.js link <usernameA> <usernameB>');
        if (a1 === a2) return bad('不能和自己配对');
        const ua = requireUser(a1);
        const ub = requireUser(a2);
        if (ua.partner_id && ua.partner_id !== ub.id) {
          return bad(a1 + ' 已与 #' + ua.partner_id + ' 配对，请先 unlink ' + a1);
        }
        if (ub.partner_id && ub.partner_id !== ua.id) {
          return bad(a2 + ' 已与 #' + ub.partner_id + ' 配对，请先 unlink ' + a2);
        }
        st.setPartner.run(ub.id, ua.id);
        st.setPartner.run(ua.id, ub.id);
        out('已配对：' + a1 + ' <-> ' + a2);
        return { code: 0, lines };
      }
      case 'unlink': {
        if (!a1) return bad('用法：node src/cli.js unlink <username>');
        const u = requireUser(a1);
        if (!u.partner_id) {
          out(a1 + ' 当前没有配对');
          return { code: 0, lines };
        }
        const p = st.userById.get(u.partner_id);
        st.setPartner.run(null, u.id);
        if (p && p.partner_id === u.id) st.setPartner.run(null, p.id);
        out('已解除配对：' + a1 + (p ? ' <-> ' + p.username : ''));
        return { code: 0, lines };
      }
      case 'list': {
        const users = st.listUsers.all();
        if (!users.length) {
          out('（还没有用户，先用 adduser 创建）');
          return { code: 0, lines };
        }
        out('ID   用户名         共享   配对对象         最后活跃');
        for (const u of users) {
          const p = u.partner_id ? st.userById.get(u.partner_id) : null;
          out(
            String(u.id).padEnd(5) +
            u.username.padEnd(15) +
            (u.sharing ? 'on' : 'off').padEnd(7) +
            (p ? p.username : '-').padEnd(17) +
            fmtTime(u.last_seen)
          );
        }
        return { code: 0, lines };
      }
      default:
        return bad('未知命令：' + cmd);
    }
  } catch (e) {
    return bad(e.message);
  } finally {
    db.close();
  }
}

function main() {
  const r = run(process.argv.slice(2));
  process.exitCode = r.code;
}

if (require.main === module) main();

module.exports = { run, usage };
