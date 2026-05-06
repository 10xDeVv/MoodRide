import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const routeApiBaseUrl = process.env.ROUTE_API_BASE_URL || 'http://localhost:8080';
const wsBaseUrl = process.env.NOTIFICATION_WS_BASE_URL || 'http://localhost:8084/ws';
const timeoutMs = Number(process.env.PROBE_TIMEOUT_MS || 45000);

const workspaceRoot = path.resolve(process.cwd(), '..', '..');
const logPath = path.join(workspaceRoot, 'docs', 'verification', 'logs', 'phase5-websocket-latency-2026-04-06.log');
const jsonPath = path.join(workspaceRoot, 'docs', 'verification', 'logs', 'phase5-websocket-latency-2026-04-06.json');

function nowIso() {
  return new Date().toISOString();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`POST ${url} failed: ${response.status} ${response.statusText} ${text}`);
  }
  return response.json();
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`GET ${url} failed: ${response.status} ${response.statusText} ${text}`);
  }
  return response.json();
}

async function main() {
  const lines = [];
  lines.push('Phase 5 WebSocket Latency Verification (2026-04-06)');
  lines.push(`routeApiBaseUrl=${routeApiBaseUrl}`);
  lines.push(`wsBaseUrl=${wsBaseUrl}`);
  lines.push(`startUtc=${nowIso()}`);

  const submitBody = {
    userId: crypto.randomUUID(),
    startLatitude: 45.5152,
    startLongitude: -122.6784,
    timeBudgetMinutes: 60,
    vibe: 'coastal'
  };

  const submit = await postJson(`${routeApiBaseUrl}/api/routes`, submitBody);
  const jobId = submit.jobId;
  if (!jobId) {
    throw new Error('Route submit did not return jobId');
  }
  lines.push(`jobId=${jobId}`);

  let wsReceivedAtMs = null;
  let wsPayload = null;
  let completedSeenAtMs = null;
  let finalStatus = null;

  const stompClient = new Client({
    webSocketFactory: () => new SockJS(wsBaseUrl),
    reconnectDelay: 0,
    debug: () => {}
  });

  const wsReady = new Promise((resolve, reject) => {
    stompClient.onConnect = () => {
      stompClient.subscribe(`/topic/job/${jobId}`, (message) => {
        if (wsReceivedAtMs == null) {
          wsReceivedAtMs = Date.now();
          try {
            wsPayload = JSON.parse(message.body);
          } catch {
            wsPayload = { raw: message.body };
          }
          lines.push(`wsMessageAtUtc=${new Date(wsReceivedAtMs).toISOString()}`);
        }
      });
      resolve();
    };
    stompClient.onStompError = (frame) => {
      reject(new Error(`STOMP error: ${frame.headers?.message || 'unknown'}`));
    };
    stompClient.onWebSocketError = (event) => {
      reject(new Error(`WebSocket error: ${String(event)}`));
    };
  });

  stompClient.activate();
  await wsReady;

  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const statusResp = await getJson(`${routeApiBaseUrl}/api/routes/jobs/${jobId}`);
    finalStatus = String(statusResp.status || 'UNKNOWN');

    if (finalStatus === 'COMPLETED' && completedSeenAtMs == null) {
      completedSeenAtMs = Date.now();
      lines.push(`completedSeenAtUtc=${new Date(completedSeenAtMs).toISOString()}`);
    }

    if (wsReceivedAtMs != null && completedSeenAtMs != null) {
      break;
    }

    if (finalStatus === 'FAILED' || finalStatus === 'TIMEOUT') {
      break;
    }

    await sleep(50);
  }

  stompClient.deactivate();

  if (finalStatus !== 'COMPLETED') {
    throw new Error(`Job finished with non-complete status: ${finalStatus}`);
  }
  if (wsReceivedAtMs == null) {
    throw new Error('Did not receive websocket completion message before timeout');
  }
  if (completedSeenAtMs == null) {
    throw new Error('Did not observe completed status before timeout');
  }

  const completionToWsMs = wsReceivedAtMs - completedSeenAtMs;
  const pass = completionToWsMs < 200;

  lines.push(`completionToWsMs=${completionToWsMs}`);
  lines.push(`thresholdMs=200`);
  lines.push(`finalStatus=${finalStatus}`);
  lines.push(`result=${pass ? 'PASS' : 'FAIL'}`);

  if (wsPayload && wsPayload.timestamp) {
    lines.push(`wsPayloadTimestamp=${wsPayload.timestamp}`);
  }

  lines.push('methodNote=completionToWsMs is measured as websocket receipt time minus first observed COMPLETED status (50ms poll cadence), which is a conservative approximation of worker-completion-to-websocket latency.');

  fs.writeFileSync(logPath, `${lines.join('\n')}\n`, 'utf8');

  const result = {
    timestampUtc: nowIso(),
    routeApiBaseUrl,
    wsBaseUrl,
    jobId,
    finalStatus,
    completedSeenAtUtc: new Date(completedSeenAtMs).toISOString(),
    wsReceivedAtUtc: new Date(wsReceivedAtMs).toISOString(),
    completionToWsMs,
    thresholdMs: 200,
    passed: pass,
    method: 'poll-vs-websocket'
  };

  fs.writeFileSync(jsonPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');

  console.log(fs.readFileSync(logPath, 'utf8'));
  console.log(fs.readFileSync(jsonPath, 'utf8'));
}

main().catch((err) => {
  console.error(`ERROR: ${err.message}`);
  process.exit(1);
});
