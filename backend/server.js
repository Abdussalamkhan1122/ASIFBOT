'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');

loadEnvFile(path.join(__dirname, '.env'));

const PORT = Number(process.env.PORT || process.env.ASIFBOT_PORT || 8080);
const DATA_DIR = path.resolve(process.env.ASIFBOT_DATA_DIR || path.join(__dirname, 'data'));
const DB_FILE = path.join(DATA_DIR, 'db.json');
const TOKEN_SECRET = process.env.ASIFBOT_TOKEN_SECRET || 'dev-only-change-this-secret-before-production';
const CORS_ORIGIN = process.env.ASIFBOT_CORS_ORIGIN || '*';
const BILLING_MODE = process.env.ASIFBOT_BILLING_MODE || 'mock';
const TOKEN_TTL_DAYS = Number(process.env.ASIFBOT_TOKEN_TTL_DAYS || 30);
const RESET_CODE_TTL_MINUTES = Number(process.env.ASIFBOT_RESET_CODE_TTL_MINUTES || 15);
const SMTP_HOST = process.env.ASIFBOT_SMTP_HOST || 'smtp.gmail.com';
const SMTP_PORT = Number(process.env.ASIFBOT_SMTP_PORT || 465);
const SMTP_USER = process.env.ASIFBOT_SMTP_USER || '';
const SMTP_PASS = String(process.env.ASIFBOT_SMTP_PASS || '').replace(/\s+/g, '');
const SMTP_FROM = process.env.ASIFBOT_SMTP_FROM || SMTP_USER;
const BRIDGE_ONLINE_WINDOW_MS = 45 * 1000;
const MAX_BODY_BYTES = 1024 * 1024;

if (TOKEN_SECRET === 'dev-only-change-this-secret-before-production') {
  console.warn('WARNING: using development TOKEN_SECRET. Set ASIFBOT_TOKEN_SECRET before production deployment.');
}

ensureDb();

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((error) => sendError(res, error));
});

server.listen(PORT, () => {
  console.log(`ASIFBOT backend running on http://0.0.0.0:${PORT}`);
  console.log(`Data file: ${DB_FILE}`);
});

async function handleRequest(req, res) {
  setCommonHeaders(res);

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = normalizePath(url.pathname);
  const method = req.method || 'GET';

  if (method === 'GET' && pathname === '/') {
    return json(res, 200, {
      service: 'ASIFBOT Backend',
      status: 'online',
      mode: BILLING_MODE,
      docs: 'See API_CONTRACT.md and backend/README.md'
    });
  }

  if (method === 'GET' && pathname === '/health') {
    return json(res, 200, { ok: true, time: new Date().toISOString() });
  }

  const body = await readJsonBody(req);

  if (method === 'POST' && pathname === '/auth/register') {
    return registerUser(res, body);
  }

  if (method === 'POST' && pathname === '/auth/login') {
    return loginUser(req, res, body);
  }

  if (method === 'POST' && pathname === '/auth/password/forgot') {
    return forgotPassword(res, body);
  }

  if (method === 'POST' && pathname === '/auth/password/reset') {
    return resetPassword(res, body);
  }

  if (method === 'GET' && pathname === '/me') {
    return withDb((db) => {
      const user = requireUser(req, db);
      return json(res, 200, accountState(db, user));
    });
  }

  if (method === 'POST' && pathname === '/billing/google/verify') {
    return verifyBilling(req, res, body);
  }

  if (method === 'GET' && pathname === '/accounts') {
    return withDb((db) => {
      const user = requireUser(req, db);
      return json(res, 200, { accounts: userAccounts(db, user).map(publicAccount) });
    });
  }

  if (method === 'POST' && pathname === '/accounts') {
    return createTradingAccount(req, res, body);
  }

  const accountRoot = pathname.match(/^\/accounts\/([^/]+)$/);
  if (accountRoot && method === 'GET') {
    return withDb((db) => {
      const user = requireUser(req, db);
      const account = requireOwnedAccount(db, user, accountRoot[1]);
      return json(res, 200, { account: publicAccount(account) });
    });
  }

  if (accountRoot && method === 'DELETE') {
    return deleteTradingAccount(req, res, accountRoot[1]);
  }

  const rotateBridge = pathname.match(/^\/accounts\/([^/]+)\/bridge-token\/rotate$/);
  if (rotateBridge && method === 'POST') {
    return rotateBridgeToken(req, res, rotateBridge[1]);
  }

  const dashboard = pathname.match(/^\/accounts\/([^/]+)\/dashboard$/);
  if (dashboard && method === 'GET') {
    return withDb((db) => {
      const user = requireUser(req, db);
      const account = requireOwnedAccount(db, user, dashboard[1]);
      return json(res, 200, dashboardState(db, account));
    });
  }

  const openTrades = pathname.match(/^\/accounts\/([^/]+)\/trades\/open$/);
  if (openTrades && method === 'GET') {
    return withDb((db) => {
      const user = requireUser(req, db);
      const account = requireOwnedAccount(db, user, openTrades[1]);
      const snapshot = db.snapshots[account.id] || {};
      return json(res, 200, { trades: normalizeTrades(snapshot.trades || []) });
    });
  }

  const commandRoute = pathname.match(/^\/accounts\/([^/]+)\/bot\/(on|off|emergency-close)$/);
  if (commandRoute && method === 'POST') {
    return createBotCommand(req, res, commandRoute[1], commandRoute[2], body);
  }

  if (method === 'POST' && pathname === '/bridge/heartbeat') {
    return bridgeHeartbeat(req, res, body);
  }

  const completeCommand = pathname.match(/^\/bridge\/commands\/([^/]+)\/complete$/);
  if (completeCommand && method === 'POST') {
    return bridgeCommandComplete(req, res, completeCommand[1], body);
  }

  throw httpError(404, 'Endpoint not found');
}

function registerUser(res, body) {
  const email = normalizeEmail(body.email);
  const password = String(body.password || '');
  const trialDays = clampInt(body.trialDays, 3, 1, 30);

  if (!isValidEmail(email)) {
    throw httpError(400, 'Enter a valid email address.');
  }
  if (password.length < 6) {
    throw httpError(400, 'Password must be at least 6 characters.');
  }

  return withDb((db) => {
    if (db.users.some((user) => user.email === email)) {
      throw httpError(409, 'This email is already registered.');
    }

    const now = Date.now();
    const user = {
      id: makeId('usr'),
      email,
      passwordHash: hashPassword(password),
      trialStartedAtMs: now,
      trialEndsAtMs: now + trialDays * 24 * 60 * 60 * 1000,
      subscriptionActive: false,
      subscriptionExpiresAtMs: 0,
      createdAt: nowIso(),
      updatedAt: nowIso()
    };

    db.users.push(user);
    audit(db, user.id, null, 'USER_REGISTERED', { email });
    return json(res, 201, accountState(db, user));
  });
}

function loginUser(req, res, body) {
  const email = normalizeEmail(body.email);
  const password = String(body.password || '');

  return withDb((db) => {
    const user = db.users.find((item) => item.email === email);
    if (!user || !verifyPassword(password, user.passwordHash)) {
      throw httpError(401, 'Invalid email or password.');
    }

    user.updatedAt = nowIso();
    audit(db, user.id, null, 'USER_LOGIN', { ip: req.socket.remoteAddress || '' });
    return json(res, 200, accountState(db, user));
  });
}

async function forgotPassword(res, body) {
  const email = normalizeEmail(body.email);
  if (!isValidEmail(email)) {
    throw httpError(400, 'Enter a valid email address.');
  }

  let reset = null;
  withDb((db) => {
    const user = db.users.find((item) => item.email === email);
    if (!user) {
      return null;
    }

    const code = randomDigits(6);
    reset = {
      id: makeId('rst'),
      userId: user.id,
      email: user.email,
      code,
      codeHash: hashResetCode(user.id, code),
      expiresAtMs: Date.now() + RESET_CODE_TTL_MINUTES * 60 * 1000,
      attempts: 0,
      usedAt: null,
      createdAt: nowIso()
    };
    db.passwordResets.push(withoutSecretCode(reset));
    audit(db, user.id, null, 'PASSWORD_RESET_REQUESTED', { email: user.email });
    return null;
  });

  if (reset) {
    await sendPasswordResetEmail(reset.email, reset.code);
  }

  return json(res, 200, {
    ok: true,
    message: 'If this email exists, a reset code has been sent.'
  });
}

function resetPassword(res, body) {
  const email = normalizeEmail(body.email);
  const code = String(body.code || '').trim().replace(/\s+/g, '');
  const password = String(body.password || '');

  if (!isValidEmail(email)) {
    throw httpError(400, 'Enter a valid email address.');
  }
  if (!/^\d{6}$/.test(code)) {
    throw httpError(400, 'Enter the 6 digit reset code.');
  }
  if (password.length < 6) {
    throw httpError(400, 'Password must be at least 6 characters.');
  }

  return withDb((db) => {
    const user = db.users.find((item) => item.email === email);
    if (!user) {
      throw httpError(400, 'Invalid or expired reset code.');
    }

    const now = Date.now();
    const reset = db.passwordResets
      .filter((item) => item.userId === user.id && !item.usedAt)
      .sort((a, b) => Number(b.expiresAtMs || 0) - Number(a.expiresAtMs || 0))[0];

    if (!reset || now > Number(reset.expiresAtMs || 0)) {
      throw httpError(400, 'Invalid or expired reset code.');
    }
    if (Number(reset.attempts || 0) >= 5) {
      throw httpError(429, 'Too many reset attempts. Request a new code.');
    }

    reset.attempts = Number(reset.attempts || 0) + 1;
    if (!safeEqual(reset.codeHash, hashResetCode(user.id, code))) {
      throw httpError(400, 'Invalid or expired reset code.');
    }

    user.passwordHash = hashPassword(password);
    user.updatedAt = nowIso();
    reset.usedAt = nowIso();
    audit(db, user.id, null, 'PASSWORD_RESET_COMPLETED', { email: user.email });

    return json(res, 200, accountState(db, user));
  });
}

function verifyBilling(req, res, body) {
  return withDb((db) => {
    const user = requireUser(req, db);
    const productId = String(body.productId || '');
    const purchaseToken = String(body.purchaseToken || '');

    if (!productId || !purchaseToken) {
      throw httpError(400, 'Missing productId or purchaseToken.');
    }

    if (BILLING_MODE !== 'mock') {
      throw httpError(501, 'Google Play verification is not connected yet. Set ASIFBOT_BILLING_MODE=mock for staging only.');
    }

    user.subscriptionActive = true;
    user.subscriptionExpiresAtMs = Date.now() + 30 * 24 * 60 * 60 * 1000;
    user.updatedAt = nowIso();
    audit(db, user.id, null, 'SUBSCRIPTION_VERIFIED_MOCK', {
      productId,
      purchaseTokenHash: sha256(purchaseToken)
    });

    return json(res, 200, accountState(db, user));
  });
}

function createTradingAccount(req, res, body) {
  return withDb((db) => {
    const user = requireUser(req, db);
    const bridgeToken = makeBridgeToken();
    const account = {
      id: makeAccountId(db),
      userId: user.id,
      bridgeId: makeBridgeId(db),
      label: cleanText(body.label, 'Trading Account', 80),
      platform: cleanText(body.platform, 'MT4', 10).toUpperCase(),
      broker: cleanText(body.broker, '', 100),
      accountNumber: cleanText(body.accountNumber, '', 40),
      symbol: cleanText(body.symbol, 'XAUUSDc', 30),
      magicNumber: Number.parseInt(body.magicNumber, 10) || 0,
      bridgeTokenHash: hashBridgeToken(bridgeToken),
      bridgeTokenLast4: bridgeToken.slice(-4),
      connected: false,
      lastSeenAtMs: 0,
      botStatus: 'OFF',
      deletedAt: null,
      createdAt: nowIso(),
      updatedAt: nowIso()
    };

    if (account.platform !== 'MT4' && account.platform !== 'MT5') {
      throw httpError(400, 'Platform must be MT4 or MT5.');
    }
    if (!account.accountNumber) {
      throw httpError(400, 'Trading account number is required.');
    }

    db.accounts.push(account);
    audit(db, user.id, account.id, 'TRADING_ACCOUNT_CREATED', {
      platform: account.platform,
      accountNumber: account.accountNumber
    });

    return json(res, 201, {
      account: {
        ...publicAccount(account),
        bridgeToken
      }
    });
  });
}

function deleteTradingAccount(req, res, rawAccountId) {
  return withDb((db) => {
    const user = requireUser(req, db);
    const account = requireOwnedAccount(db, user, rawAccountId);

    account.deletedAt = nowIso();
    account.botStatus = 'OFF';
    account.connected = false;
    account.bridgeTokenHash = '';
    account.updatedAt = nowIso();

    db.commands.forEach((command) => {
      if (command.accountId === account.id && command.status !== 'COMPLETED' && command.status !== 'FAILED') {
        command.status = 'CANCELLED';
        command.completedAt = nowIso();
      }
    });

    audit(db, user.id, account.id, 'TRADING_ACCOUNT_DELETED', {});
    return json(res, 200, { accounts: userAccounts(db, user).map(publicAccount) });
  });
}

function rotateBridgeToken(req, res, rawAccountId) {
  return withDb((db) => {
    const user = requireUser(req, db);
    const account = requireOwnedAccount(db, user, rawAccountId);
    const bridgeToken = makeBridgeToken();

    if (!account.bridgeId) {
      account.bridgeId = makeBridgeId(db);
    }
    account.bridgeTokenHash = hashBridgeToken(bridgeToken);
    account.bridgeTokenLast4 = bridgeToken.slice(-4);
    account.updatedAt = nowIso();
    audit(db, user.id, account.id, 'BRIDGE_TOKEN_ROTATED', {});

    return json(res, 200, {
      account: {
        ...publicAccount(account),
        bridgeToken
      }
    });
  });
}

function createBotCommand(req, res, rawAccountId, action, body) {
  return withDb((db) => {
    const user = requireUser(req, db);
    const account = requireOwnedAccount(db, user, rawAccountId);
    let commandType = 'TURN_ON';
    if (action === 'off') commandType = 'TURN_OFF_CLOSE_TRADES';
    if (action === 'emergency-close') commandType = 'EMERGENCY_CLOSE';

    const command = {
      id: makeId('cmd'),
      userId: user.id,
      accountId: account.id,
      type: commandType,
      payload: {
        closeOpenTrades: Boolean(body.closeOpenTrades),
        deletePendingOrders: Boolean(body.deletePendingOrders),
        scope: body.scope || 'asifbot_only'
      },
      status: 'PENDING',
      result: null,
      createdAt: nowIso(),
      claimedAt: null,
      completedAt: null
    };

    db.commands.push(command);
    if (commandType === 'TURN_ON') {
      account.botStatus = 'ON_PENDING';
    } else {
      account.botStatus = 'CLOSING';
    }
    account.updatedAt = nowIso();

    audit(db, user.id, account.id, commandType, command.payload);
    return json(res, 202, dashboardState(db, account));
  });
}

function bridgeHeartbeat(req, res, body) {
  return withDb((db) => {
    const { account } = requireBridge(req, body, db);

    account.connected = true;
    account.lastSeenAtMs = Date.now();
    account.updatedAt = nowIso();
    if (body.botStatus) {
      account.botStatus = cleanText(body.botStatus, account.botStatus, 40);
    }

    upsertSnapshot(db, account, body);

    const commands = db.commands
      .filter((command) => command.accountId === account.id && (command.status === 'PENDING' || command.status === 'SENT'))
      .sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)));

    commands.forEach((command) => {
      if (command.status === 'PENDING') {
        command.status = 'SENT';
        command.claimedAt = nowIso();
      }
    });

    return json(res, 200, {
      ok: true,
      accountId: account.id,
      serverTime: nowIso(),
      desiredBotStatus: account.botStatus,
      commands: commands.map(publicCommand)
    });
  });
}

function bridgeCommandComplete(req, res, rawCommandId, body) {
  return withDb((db) => {
    const { account } = requireBridge(req, body, db);
    const commandId = decodeURIComponent(rawCommandId);
    const command = db.commands.find((item) => item.id === commandId && item.accountId === account.id);
    if (!command) {
      throw httpError(404, 'Command not found for this bridge.');
    }

    const success = body.success !== false;
    command.status = success ? 'COMPLETED' : 'FAILED';
    command.result = {
      message: body.message || '',
      closedTrades: body.closedTrades || 0,
      deletedPendingOrders: body.deletedPendingOrders || 0,
      raw: body.result || null
    };
    command.completedAt = nowIso();

    if (success) {
      account.botStatus = command.type === 'TURN_ON' ? 'ON' : 'OFF';
    } else {
      account.botStatus = 'ERROR';
    }
    account.connected = true;
    account.lastSeenAtMs = Date.now();
    account.updatedAt = nowIso();

    upsertSnapshot(db, account, body);
    audit(db, command.userId, account.id, `COMMAND_${command.status}`, {
      commandId: command.id,
      type: command.type
    });

    return json(res, 200, {
      ok: true,
      command: publicCommand(command),
      dashboard: dashboardState(db, account)
    });
  });
}

function requireBridge(req, body, db) {
  const accountId = cleanText(body.accountId, '', 120).toUpperCase();
  const token = bridgeTokenFromRequest(req, body);
  if (!accountId || !token) {
    throw httpError(401, 'Bridge accountId and bridgeToken are required.');
  }

  const account = db.accounts.find((item) => (String(item.id).toUpperCase() === accountId || String(item.bridgeId || '').toUpperCase() === accountId) && !item.deletedAt);
  if (!account || !account.bridgeTokenHash || !safeEqual(account.bridgeTokenHash, hashBridgeToken(token))) {
    throw httpError(401, 'Invalid bridge credentials.');
  }

  return { account };
}

function bridgeTokenFromRequest(req, body) {
  const auth = req.headers.authorization || '';
  if (auth.startsWith('Bridge ')) {
    return auth.slice('Bridge '.length).trim();
  }
  if (req.headers['x-bridge-token']) {
    return String(req.headers['x-bridge-token']);
  }
  return String(body.bridgeToken || '');
}

function accountState(db, user) {
  return {
    token: signToken(user),
    email: user.email,
    subscriptionActive: isSubscriptionActive(user),
    trialEndsAtMs: Number(user.trialEndsAtMs || 0),
    botEnabled: userAccounts(db, user).some((account) => String(account.botStatus || '').startsWith('ON'))
  };
}

function dashboardState(db, account) {
  const snapshot = db.snapshots[account.id] || {};
  const latestCommand = latestAccountCommand(db, account.id);
  const metrics = normalizeMetrics(snapshot.metrics || {});
  metrics.commandStatus = latestCommand ? `${latestCommand.type}: ${latestCommand.status}` : (metrics.commandStatus || 'Ready');
  metrics.lastUpdate = snapshot.capturedAt || metrics.lastUpdate || nowIso();

  return {
    account: publicAccount(account),
    metrics,
    trades: normalizeTrades(snapshot.trades || [])
  };
}

function upsertSnapshot(db, account, body) {
  const metrics = normalizeMetrics(body.metrics || body);
  const trades = normalizeTrades(body.trades || []);
  if (trades.length > 0 || Object.keys(metrics).length > 0) {
    db.snapshots[account.id] = {
      metrics,
      trades,
      capturedAt: nowIso()
    };
  }
}

function publicAccount(account) {
  const connected = Boolean(account.lastSeenAtMs && Date.now() - Number(account.lastSeenAtMs) <= BRIDGE_ONLINE_WINDOW_MS);
  return {
    id: account.id,
    bridgeAccountId: account.bridgeId || account.id,
    label: account.label || 'Trading Account',
    platform: account.platform || 'MT4',
    broker: account.broker || '',
    accountNumber: account.accountNumber || '',
    symbol: account.symbol || 'XAUUSDc',
    magicNumber: Number(account.magicNumber || 0),
    connected,
    botStatus: account.botStatus || 'OFF'
  };
}

function publicCommand(command) {
  return {
    id: command.id,
    type: command.type,
    payload: command.payload || {},
    status: command.status,
    createdAt: command.createdAt,
    claimedAt: command.claimedAt,
    completedAt: command.completedAt,
    result: command.result
  };
}

function normalizeMetrics(source) {
  return {
    balance: numberOrZero(source.balance),
    equity: numberOrZero(source.equity),
    marginLevel: numberOrZero(source.marginLevel),
    floatingProfit: numberOrZero(source.floatingProfit),
    openTradeCount: Number.parseInt(source.openTradeCount, 10) || 0,
    totalLots: numberOrZero(source.totalLots),
    worstTradeLoss: numberOrZero(source.worstTradeLoss),
    commandStatus: cleanText(source.commandStatus, 'Ready', 120),
    lastUpdate: cleanText(source.lastUpdate, nowIso(), 80)
  };
}

function normalizeTrades(trades) {
  if (!Array.isArray(trades)) return [];
  return trades.slice(0, 200).map((trade) => ({
    ticket: Number.parseInt(trade.ticket, 10) || 0,
    symbol: cleanText(trade.symbol, 'XAUUSDc', 30),
    side: cleanText(trade.side, 'BUY', 10).toUpperCase(),
    lots: numberOrZero(trade.lots),
    openPrice: numberOrZero(trade.openPrice),
    currentPrice: numberOrZero(trade.currentPrice),
    profit: numberOrZero(trade.profit),
    openTime: cleanText(trade.openTime, '', 80)
  }));
}

function requireUser(req, db) {
  const token = bearerToken(req);
  if (!token) {
    throw httpError(401, 'Authorization token is required.');
  }
  const payload = verifyToken(token);
  const user = db.users.find((item) => item.id === payload.sub);
  if (!user) {
    throw httpError(401, 'User no longer exists.');
  }
  return user;
}

function requireOwnedAccount(db, user, rawAccountId) {
  const accountId = decodeURIComponent(rawAccountId);
  const account = db.accounts.find((item) => item.id === accountId && item.userId === user.id && !item.deletedAt);
  if (!account) {
    throw httpError(404, 'Trading account not found.');
  }
  return account;
}

function userAccounts(db, user) {
  return db.accounts.filter((account) => account.userId === user.id && !account.deletedAt);
}

function latestAccountCommand(db, accountId) {
  const commands = db.commands
    .filter((command) => command.accountId === accountId)
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
  return commands[0] || null;
}

function hasAccess(user) {
  // ASIFBOT is distributed to approved users; no trial or paid subscription
  // is required to control a linked trading account.
  return true;
}

function isSubscriptionActive(user) {
  return Boolean(user.subscriptionActive) && (!user.subscriptionExpiresAtMs || Date.now() < Number(user.subscriptionExpiresAtMs));
}

function signToken(user) {
  const payload = {
    sub: user.id,
    email: user.email,
    exp: Date.now() + TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000
  };
  const payloadPart = base64url(JSON.stringify(payload));
  const signature = hmac(payloadPart);
  return `asif.v1.${payloadPart}.${signature}`;
}

function verifyToken(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 4 || parts[0] !== 'asif' || parts[1] !== 'v1') {
    throw httpError(401, 'Invalid authorization token.');
  }
  const payloadPart = parts[2];
  const signature = parts[3];
  if (!safeEqual(signature, hmac(payloadPart))) {
    throw httpError(401, 'Invalid authorization token signature.');
  }
  let payload;
  try {
    payload = JSON.parse(Buffer.from(payloadPart, 'base64url').toString('utf8'));
  } catch (error) {
    throw httpError(401, 'Invalid authorization token payload.');
  }
  if (!payload.exp || Date.now() > Number(payload.exp)) {
    throw httpError(401, 'Authorization token expired.');
  }
  return payload;
}

function bearerToken(req) {
  const auth = req.headers.authorization || '';
  if (!auth.startsWith('Bearer ')) return '';
  return auth.slice('Bearer '.length).trim();
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('base64url');
  const iterations = 210000;
  const hash = crypto.pbkdf2Sync(String(password), salt, iterations, 32, 'sha256').toString('base64url');
  return `pbkdf2_sha256$${iterations}$${salt}$${hash}`;
}

function verifyPassword(password, stored) {
  const parts = String(stored || '').split('$');
  if (parts.length !== 4 || parts[0] !== 'pbkdf2_sha256') return false;
  const iterations = Number(parts[1]);
  const salt = parts[2];
  const expected = parts[3];
  const actual = crypto.pbkdf2Sync(String(password), salt, iterations, 32, 'sha256').toString('base64url');
  return safeEqual(actual, expected);
}

function hashBridgeToken(token) {
  return sha256(`bridge:${TOKEN_SECRET}:${token}`);
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ''));
  const b = Buffer.from(String(right || ''));
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function hmac(value) {
  return crypto.createHmac('sha256', TOKEN_SECRET).update(String(value)).digest('base64url');
}

function sha256(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

function hashResetCode(userId, code) {
  return sha256(`reset:${TOKEN_SECRET}:${userId}:${code}`);
}

function makeId(prefix) {
  return `${prefix}_${crypto.randomUUID ? crypto.randomUUID() : crypto.randomBytes(16).toString('hex')}`;
}

function makeAccountId(db) {
  for (let i = 0; i < 20; i++) {
    const id = `ACC-${randomCode(6)}`;
    if (!db.accounts.some((account) => String(account.id).toUpperCase() === id)) {
      return id;
    }
  }
  return makeId('acc');
}

function makeBridgeId(db) {
  for (let i = 0; i < 20; i++) {
    const id = `BRG-${randomCode(6)}`;
    if (!db.accounts.some((account) => String(account.bridgeId || '').toUpperCase() === id)) {
      return id;
    }
  }
  return `BRG-${randomCode(10)}`;
}

function makeBridgeToken() {
  return `BOT-${randomCode(4)}-${randomCode(4)}-${randomCode(4)}`;
}

function randomCode(length) {
  const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  let out = '';
  for (let i = 0; i < length; i++) {
    out += alphabet[crypto.randomInt(0, alphabet.length)];
  }
  return out;
}

function randomDigits(length) {
  let out = '';
  for (let i = 0; i < length; i++) {
    out += String(crypto.randomInt(0, 10));
  }
  return out;
}

function base64url(value) {
  return Buffer.from(String(value), 'utf8').toString('base64url');
}

function normalizeEmail(email) {
  return String(email || '').trim().toLowerCase();
}

function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function cleanText(value, fallback, maxLength) {
  const text = String(value == null ? fallback : value).trim();
  return text.slice(0, maxLength);
}

function numberOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function clampInt(value, fallback, min, max) {
  const number = Number.parseInt(value, 10);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(min, Math.min(max, number));
}

function nowIso() {
  return new Date().toISOString();
}

async function readJsonBody(req) {
  if (req.method === 'GET' || req.method === 'HEAD') {
    return {};
  }

  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > MAX_BODY_BYTES) {
      throw httpError(413, 'Request body is too large.');
    }
    chunks.push(chunk);
  }

  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return {};

  try {
    return JSON.parse(raw);
  } catch (error) {
    throw httpError(400, 'Invalid JSON request body.');
  }
}

function normalizePath(pathname) {
  if (!pathname || pathname === '/') return '/';
  return pathname.replace(/\/+$/, '') || '/';
}

function json(res, status, payload) {
  if (res.writableEnded) return;
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function sendError(res, error) {
  const status = error.statusCode || 500;
  const message = status >= 500 ? 'Internal server error' : error.message;
  if (status >= 500) {
    console.error(error);
  }
  json(res, status, { error: message });
}

function httpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

function setCommonHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', CORS_ORIGIN);
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Bridge-Token');
  res.setHeader('X-Content-Type-Options', 'nosniff');
}

function ensureDb() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(DB_FILE)) {
    writeDb(initialDb());
  }
}

function initialDb() {
  return {
    schemaVersion: 1,
    users: [],
    accounts: [],
    commands: [],
    passwordResets: [],
    snapshots: {},
    auditLogs: []
  };
}

function readDb() {
  ensureDb();
  const db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  return normalizeDb(db);
}

function writeDb(db) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmp = `${DB_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(normalizeDb(db), null, 2));
  fs.renameSync(tmp, DB_FILE);
}

function withDb(work) {
  const db = readDb();
  const result = work(db);
  writeDb(db);
  return result;
}

function normalizeDb(db) {
  db.schemaVersion = db.schemaVersion || 1;
  db.users = Array.isArray(db.users) ? db.users : [];
  db.accounts = Array.isArray(db.accounts) ? db.accounts : [];
  db.commands = Array.isArray(db.commands) ? db.commands : [];
  db.passwordResets = Array.isArray(db.passwordResets) ? db.passwordResets : [];
  db.snapshots = db.snapshots && typeof db.snapshots === 'object' && !Array.isArray(db.snapshots) ? db.snapshots : {};
  db.auditLogs = Array.isArray(db.auditLogs) ? db.auditLogs : [];
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  db.passwordResets = db.passwordResets.filter((item) => !item.usedAt && Number(item.expiresAtMs || 0) > cutoff);
  return db;
}

function audit(db, userId, accountId, action, details) {
  db.auditLogs.push({
    id: makeId('aud'),
    userId,
    accountId,
    action,
    details: details || {},
    createdAt: nowIso()
  });
  if (db.auditLogs.length > 5000) {
    db.auditLogs = db.auditLogs.slice(db.auditLogs.length - 5000);
  }
}

function loadEnvFile(filePath) {
  if (!fs.existsSync(filePath)) {
    return;
  }
  const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
  lines.forEach((line) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      return;
    }
    const equals = trimmed.indexOf('=');
    if (equals <= 0) {
      return;
    }
    const key = trimmed.slice(0, equals).trim();
    let value = trimmed.slice(equals + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!process.env[key]) {
      process.env[key] = value;
    }
  });
}

function withoutSecretCode(reset) {
  const copy = { ...reset };
  delete copy.code;
  return copy;
}

async function sendPasswordResetEmail(email, code) {
  if (!SMTP_USER || !SMTP_PASS || !SMTP_FROM) {
    throw httpError(501, 'Password reset email is not configured on the server.');
  }

  const subject = 'ASIFBOT password reset code';
  const text = [
    `Your ASIFBOT password reset code is: ${code}`,
    '',
    `This code expires in ${RESET_CODE_TTL_MINUTES} minutes.`,
    'If you did not request this, you can ignore this email.'
  ].join('\r\n');

  try {
    await sendSmtpMail({
      host: SMTP_HOST,
      port: SMTP_PORT,
      user: SMTP_USER,
      pass: SMTP_PASS,
      from: SMTP_FROM,
      to: email,
      subject,
      text
    });
  } catch (error) {
    console.error('Password reset email failed:', error);
    throw httpError(502, 'Password reset email could not be sent. Check server Gmail settings.');
  }
}

function sendSmtpMail(options) {
  const tls = require('tls');
  const socket = tls.connect({
    host: options.host,
    port: options.port,
    servername: options.host
  });
  socket.setEncoding('utf8');

  let buffer = '';
  const lines = [];

  socket.on('data', (chunk) => {
    buffer += chunk;
    const parts = buffer.split(/\r?\n/);
    buffer = parts.pop() || '';
    parts.forEach((line) => {
      if (line) lines.push(line);
    });
  });

  function write(command) {
    socket.write(`${command}\r\n`);
  }

  function waitFor(expectedCode) {
    return new Promise((resolve, reject) => {
      const deadline = Date.now() + 15000;
      const timer = setInterval(() => {
        for (let i = 0; i < lines.length; i++) {
          const line = lines[i];
          if (!/^\d{3}[ -]/.test(line)) continue;
          const code = Number(line.slice(0, 3));
          const complete = line.charAt(3) === ' ';
          if (complete) {
            lines.splice(0, i + 1);
            clearInterval(timer);
            if (code === expectedCode) {
              resolve(line);
            } else {
              reject(new Error(`SMTP expected ${expectedCode}, got ${line}`));
            }
            return;
          }
        }
        if (Date.now() > deadline) {
          clearInterval(timer);
          reject(new Error('SMTP timed out.'));
        }
      }, 50);
    });
  }

  const message = [
    `From: ${formatEmailAddress(options.from)}`,
    `To: ${formatEmailAddress(options.to)}`,
    `Subject: ${encodeMailHeader(options.subject)}`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=UTF-8',
    'Content-Transfer-Encoding: 8bit',
    '',
    options.text,
    ''
  ].join('\r\n').replace(/^\./gm, '..');

  return new Promise((resolve, reject) => {
    socket.once('error', reject);
    socket.once('secureConnect', async () => {
      try {
        await waitFor(220);
        write(`EHLO ${require('os').hostname() || 'localhost'}`);
        await waitFor(250);
        write('AUTH LOGIN');
        await waitFor(334);
        write(Buffer.from(options.user, 'utf8').toString('base64'));
        await waitFor(334);
        write(Buffer.from(options.pass, 'utf8').toString('base64'));
        await waitFor(235);
        write(`MAIL FROM:<${cleanEmailAddress(options.from)}>`);
        await waitFor(250);
        write(`RCPT TO:<${cleanEmailAddress(options.to)}>`);
        await waitFor(250);
        write('DATA');
        await waitFor(354);
        write(`${message}\r\n.`);
        await waitFor(250);
        write('QUIT');
        socket.end();
        resolve();
      } catch (error) {
        socket.destroy();
        reject(error);
      }
    });
  });
}

function cleanEmailAddress(value) {
  const match = String(value || '').match(/<([^>]+)>/);
  return (match ? match[1] : String(value || '')).trim();
}

function formatEmailAddress(value) {
  return cleanEmailAddress(value);
}

function encodeMailHeader(value) {
  return String(value || '').replace(/[\r\n]/g, ' ');
}
