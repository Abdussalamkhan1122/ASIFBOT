'use strict';

// Example VPS/EA bridge client.
//
// This file does not trade. It shows how the VPS bridge should talk to the
// backend. Replace the simulated metrics/trades with live MT4/MT5 EA data.

const API_BASE_URL = process.env.ASIFBOT_API_BASE_URL || 'http://127.0.0.1:8080';
const ACCOUNT_ID = process.env.ASIFBOT_ACCOUNT_ID || '';
const BRIDGE_TOKEN = process.env.ASIFBOT_BRIDGE_TOKEN || '';
const POLL_SECONDS = Number(process.env.ASIFBOT_BRIDGE_POLL_SECONDS || 3);

if (!ACCOUNT_ID || !BRIDGE_TOKEN) {
  console.error('Set ASIFBOT_ACCOUNT_ID and ASIFBOT_BRIDGE_TOKEN before running this bridge example.');
  process.exit(1);
}

console.log('ASIFBOT bridge example started.');
console.log(`Backend: ${API_BASE_URL}`);
console.log(`Account: ${ACCOUNT_ID}`);

setInterval(poll, Math.max(1, POLL_SECONDS) * 1000);
poll();

async function poll() {
  try {
    const response = await post('/bridge/heartbeat', {
      accountId: ACCOUNT_ID,
      botStatus: 'ON',
      metrics: simulatedMetrics(),
      trades: simulatedTrades()
    });

    if (Array.isArray(response.commands) && response.commands.length > 0) {
      for (const command of response.commands) {
        await handleCommand(command);
      }
    }
  } catch (error) {
    console.error(`[bridge] ${error.message}`);
  }
}

async function handleCommand(command) {
  console.log(`[bridge] command received: ${command.type} (${command.id})`);

  // Replace this section with real MetaTrader behavior:
  // - TURN_ON: allow EA to open new trades.
  // - TURN_OFF_CLOSE_TRADES: disable entries, delete pending orders, close ASIFBOT trades.
  // - EMERGENCY_CLOSE: close ASIFBOT trades immediately and retry failures.
  const result = {
    accountId: ACCOUNT_ID,
    success: true,
    message: `Simulated ${command.type} completed`,
    closedTrades: command.type === 'TURN_ON' ? 0 : 2,
    deletedPendingOrders: command.type === 'TURN_ON' ? 0 : 1,
    metrics: simulatedMetrics(),
    trades: command.type === 'TURN_ON' ? simulatedTrades() : []
  };

  await post(`/bridge/commands/${encodeURIComponent(command.id)}/complete`, result);
  console.log(`[bridge] command completed: ${command.id}`);
}

async function post(path, body) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bridge ${BRIDGE_TOKEN}`
    },
    body: JSON.stringify(body)
  });

  const text = await response.text();
  const json = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${JSON.stringify(json)}`);
  }
  return json;
}

function simulatedMetrics() {
  return {
    balance: 50.0,
    equity: 50.84,
    marginLevel: 420.5,
    floatingProfit: 0.84,
    openTradeCount: 2,
    totalLots: 0.04,
    worstTradeLoss: -0.11
  };
}

function simulatedTrades() {
  return [
    {
      ticket: 100001,
      symbol: 'XAUUSDc',
      side: 'BUY',
      lots: 0.01,
      openPrice: 4314.126,
      currentPrice: 4314.826,
      profit: 0.31,
      openTime: '2026-09-27 10:15'
    },
    {
      ticket: 100002,
      symbol: 'XAUUSDc',
      side: 'BUY',
      lots: 0.03,
      openPrice: 4314.326,
      currentPrice: 4314.826,
      profit: 0.53,
      openTime: '2026-09-27 10:17'
    }
  ];
}
