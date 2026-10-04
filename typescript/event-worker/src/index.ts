/// <reference path="./runtime.d.ts" />

import { configFromEnv } from './config.ts';
import { logStats, translateEvent } from './events.ts';
import { LogRateLimiter } from './limiter.ts';
import { ExecutionLogRelay, LogStatsRelay } from './relay.ts';

const LOG_TOKEN_TELEMETRY_INTERVAL_MS = 1_000;

export async function run(): Promise<void> {
  const config = configFromEnv();
  const relay = new ExecutionLogRelay(config.logsUrl, config.apiKey);
  const logStatsRelay = new LogStatsRelay(config.logStatsUrl, config.apiKey);
  const limiter = new LogRateLimiter(config.logRatePerSecond);
  relay.start();
  logStatsRelay.start();
  const telemetryTimer = setInterval(() => {
    for (const [playerId, snapshot] of limiter.snapshots()) {
      const entry = logStats(playerId, snapshot);
      if (entry !== undefined) logStatsRelay.send(entry);
    }
  }, LOG_TOKEN_TELEMETRY_INTERVAL_MS);

  try {
    const eventManager = new globalThis.EventManager();
    for await (const data of eventManager) {
      const translated = translateEvent(data);
      if (translated === undefined) continue;
      if (translated.isProgramLog) {
        const snapshot = limiter.tryConsume(translated.playerId);
        const telemetry = logStats(translated.playerId, snapshot);
        if (telemetry !== undefined) logStatsRelay.send(telemetry);
        if (!snapshot.consumed) continue;
      }
      relay.send(translated.entry);
    }
  } finally {
    clearInterval(telemetryTimer);
    relay.stop();
    logStatsRelay.stop();
  }
}

if (import.meta.main) {
  await run();
}
