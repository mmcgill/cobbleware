/// <reference path="./runtime.d.ts" />

import { configFromEnv } from './config.ts';
import { translateEvent } from './events.ts';
import { LogRateLimiter } from './limiter.ts';
import { ExecutionLogRelay } from './relay.ts';

export async function run(): Promise<void> {
  const config = configFromEnv();
  const relay = new ExecutionLogRelay(config.logsUrl, config.apiKey);
  const limiter = new LogRateLimiter(config.logRatePerSecond);
  relay.start();

  try {
    const eventManager = new globalThis.EventManager();
    for await (const data of eventManager) {
      const translated = translateEvent(data);
      if (translated === undefined) continue;
      if (translated.isProgramLog && !limiter.tryConsume(translated.playerId)) continue;
      relay.send(translated.entry);
    }
  } finally {
    relay.stop();
  }
}

if (import.meta.main) {
  await run();
}
