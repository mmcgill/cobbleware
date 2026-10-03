/// <reference path="./runtime.d.ts" />

import { configFromEnv } from './config.ts';
import { logTokenBucketEntry, translateEvent } from './events.ts';
import { LogRateLimiter } from './limiter.ts';
import { ExecutionLogRelay } from './relay.ts';

const LOG_TOKEN_TELEMETRY_INTERVAL_MS = 1_000;

export async function run(): Promise<void> {
  const config = configFromEnv();
  const relay = new ExecutionLogRelay(config.logsUrl, config.apiKey);
  const limiter = new LogRateLimiter(config.logRatePerSecond);
  relay.start();
  const telemetryTimer = setInterval(() => {
    for (const [playerId, snapshot] of limiter.snapshots()) {
      const entry = logTokenBucketEntry(playerId, snapshot);
      if (entry !== undefined) relay.send(entry);
    }
  }, LOG_TOKEN_TELEMETRY_INTERVAL_MS);

  try {
    const eventManager = new globalThis.EventManager();
    for await (const data of eventManager) {
      const translated = translateEvent(data);
      if (translated === undefined) continue;
      if (translated.isProgramLog) {
        const snapshot = limiter.tryConsume(translated.playerId);
        const telemetry = logTokenBucketEntry(translated.playerId, snapshot);
        if (telemetry !== undefined) relay.send(telemetry);
        if (!snapshot.consumed) continue;
      }
      relay.send(translated.entry);
    }
  } finally {
    clearInterval(telemetryTimer);
    relay.stop();
  }
}

if (import.meta.main) {
  await run();
}
