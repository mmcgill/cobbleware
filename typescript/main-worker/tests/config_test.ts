import {
  BYTES_PER_MEBIBYTE,
  configFromEnv,
  DEFAULT_PLAYER_MEMORY_QUOTA_BYTES,
} from '../src/config.ts';

Deno.test('Player memory quota defaults to the Edge Runtime memory limit', () => {
  const config = configFromEnv({ apiKey: 'test-key', storageDir: 'test-storage' });
  assertEquals(
    config.playerMemoryQuotaBytes,
    DEFAULT_PLAYER_MEMORY_QUOTA_BYTES,
    'memory quota default changed',
  );
});

Deno.test('Player memory quota accepts whole-MiB overrides', () => {
  const config = configFromEnv({
    apiKey: 'test-key',
    storageDir: 'test-storage',
    playerMemoryQuotaBytes: 3 * BYTES_PER_MEBIBYTE,
  });
  assertEquals(
    config.playerMemoryQuotaBytes,
    3 * BYTES_PER_MEBIBYTE,
    'memory quota override changed',
  );
});

Deno.test('Player memory quota rejects invalid values', () => {
  for (const value of [0, -BYTES_PER_MEBIBYTE, BYTES_PER_MEBIBYTE + 1]) {
    assertThrows(
      () =>
        configFromEnv({
          apiKey: 'test-key',
          storageDir: 'test-storage',
          playerMemoryQuotaBytes: value,
        }),
      'invalid player memory quota was accepted',
    );
  }
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}

function assertThrows(action: () => unknown, message: string): void {
  try {
    action();
  } catch {
    return;
  }
  throw new Error(message);
}
