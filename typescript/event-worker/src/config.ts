export const DEFAULT_MAIN_WORKER_URL = 'http://127.0.0.1:9000';
export const DEFAULT_LOG_RATE_PER_SECOND = 10;

export type EventWorkerConfig = {
  apiKey: string;
  logsUrl: string;
  logStatsUrl: string;
  logRatePerSecond: number;
};

export type EventWorkerConfigOverrides = Partial<EventWorkerConfig> & {
  mainWorkerUrl?: string;
};

export function configFromEnv(overrides: EventWorkerConfigOverrides = {}): EventWorkerConfig {
  const apiKey = overrides.apiKey ?? Deno.env.get('COBBLEWARE_API_KEY') ?? '';
  if (apiKey === '') {
    throw new Error('COBBLEWARE_API_KEY must be configured for the Event Worker');
  }

  const logsUrl = overrides.logsUrl ?? logsUrlFromBase(
    overrides.mainWorkerUrl ?? Deno.env.get('COBBLEWARE_MAIN_WORKER_URL') ??
      DEFAULT_MAIN_WORKER_URL,
  );
  const logStatsUrl = overrides.logStatsUrl ?? logStatsUrlFromBase(
    overrides.mainWorkerUrl ?? Deno.env.get('COBBLEWARE_MAIN_WORKER_URL') ??
      DEFAULT_MAIN_WORKER_URL,
  );
  const logRatePerSecond = overrides.logRatePerSecond ?? readLogRateFromEnv();
  if (!Number.isFinite(logRatePerSecond) || logRatePerSecond < 0) {
    throw new Error('COBBLEWARE_LOG_RATE_PER_SECOND must be a non-negative finite number');
  }

  return { apiKey, logsUrl, logStatsUrl, logRatePerSecond };
}

export function logsUrlFromBase(value: string): string {
  return webSocketUrlFromBase(value, 'modapi/v1/logs');
}

export function logStatsUrlFromBase(value: string): string {
  return webSocketUrlFromBase(value, 'modapi/v1/log-stats');
}

function webSocketUrlFromBase(value: string, path: string): string {
  let base: URL;
  try {
    base = new URL(value);
  } catch {
    throw new Error('COBBLEWARE_MAIN_WORKER_URL must be an absolute HTTP or HTTPS URL');
  }
  if (base.protocol !== 'http:' && base.protocol !== 'https:') {
    throw new Error('COBBLEWARE_MAIN_WORKER_URL must use HTTP or HTTPS');
  }
  if (!base.pathname.endsWith('/')) base.pathname += '/';
  const endpoint = new URL(path, base);
  endpoint.protocol = endpoint.protocol === 'https:' ? 'wss:' : 'ws:';
  return endpoint.href;
}

function readLogRateFromEnv(): number {
  const value = Deno.env.get('COBBLEWARE_LOG_RATE_PER_SECOND');
  return value === undefined || value.trim() === '' ? DEFAULT_LOG_RATE_PER_SECOND : Number(value);
}
