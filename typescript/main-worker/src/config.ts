export const PENDING_AUTH_CODE_LIFETIME_MS = 5 * 60 * 1000;
export const SESSION_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;
export const SESSION_COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;
export const DEFAULT_COOKIE_NAME = 'cobbleware_session';
export const MAX_JSON_BODY_BYTES = 16 * 1024;
export const DEFAULT_PLAYER_PROGRAM_QUOTA_BYTES = 10 * 1024 * 1024;

export type AppConfig = {
  apiKey: string | undefined;
  storageDir: string;
  cookieName: string;
  pendingAuthCodeLifetimeMs: number;
  sessionLifetimeMs: number;
  sessionCookieMaxAgeSeconds: number;
  maxJsonBodyBytes: number;
  playerProgramQuotaBytes: number;
};

export type ConfigOverrides = Partial<AppConfig> & {
  apiKey?: string | undefined;
};

function defaultStorageDir(gameDir: string): string {
  const separator = gameDir.endsWith('\\') || gameDir.endsWith('/') ? '' : '/';
  return `${gameDir}${separator}cobbleware-data`;
}

export function configFromEnv(overrides: ConfigOverrides = {}): AppConfig {
  const gameDir = Deno.env.get('COBBLEWARE_GAME_DIR') ?? Deno.cwd();
  const apiKey = overrides.apiKey ?? Deno.env.get('COBBLEWARE_API_KEY');

  return {
    apiKey,
    storageDir: overrides.storageDir ?? defaultStorageDir(gameDir),
    cookieName: overrides.cookieName ?? DEFAULT_COOKIE_NAME,
    pendingAuthCodeLifetimeMs: overrides.pendingAuthCodeLifetimeMs ?? PENDING_AUTH_CODE_LIFETIME_MS,
    sessionLifetimeMs: overrides.sessionLifetimeMs ?? SESSION_LIFETIME_MS,
    sessionCookieMaxAgeSeconds: overrides.sessionCookieMaxAgeSeconds ??
      SESSION_COOKIE_MAX_AGE_SECONDS,
    maxJsonBodyBytes: overrides.maxJsonBodyBytes ?? MAX_JSON_BODY_BYTES,
    playerProgramQuotaBytes: overrides.playerProgramQuotaBytes ?? readProgramQuotaFromEnv(),
  };
}

function readProgramQuotaFromEnv(): number {
  const value = Deno.env.get('COBBLEWARE_PLAYER_PROGRAM_QUOTA_BYTES') ?? '';
  if (value.trim() === '') {
    return DEFAULT_PLAYER_PROGRAM_QUOTA_BYTES;
  }

  const quota = Number(value);
  if (!Number.isSafeInteger(quota) || quota < 0) {
    throw new Error('COBBLEWARE_PLAYER_PROGRAM_QUOTA_BYTES must be a non-negative integer');
  }
  return quota;
}
