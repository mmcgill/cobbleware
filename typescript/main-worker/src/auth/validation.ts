import { z } from 'zod';

export const ModAuthenticationRequestSchema = z.strictObject({
  code: z.string(),
  playerUUID: z.string(),
  playerProfileName: z.string(),
});

export const PlayerAuthenticationRequestSchema = z.object({
  code: z.string(),
});

const PersistedSessionSchema = z.object({
  tokenDigest: z.string().regex(/^[0-9a-f]{64}$/),
  playerUUID: z.string(),
  playerProfileName: z.string(),
  createdAtMs: z.number(),
  expiresAtMs: z.number(),
});

export const PersistedAuthStateSchema = z.object({
  version: z.literal(1),
  sessions: z.array(PersistedSessionSchema),
});

export type ModAuthenticationRequest = z.infer<typeof ModAuthenticationRequestSchema>;
export type PlayerAuthenticationRequest = z.infer<typeof PlayerAuthenticationRequestSchema>;
export type PersistedAuthState = z.infer<typeof PersistedAuthStateSchema>;

// The public API examples use an eight-character uppercase alphanumeric code
// (for example, XAU1-98B1), so accept that documented format as-is.
const AUTH_CODE = /^[A-Z0-9]{8}$/;

export function normalizeAuthCode(value: string): string | null {
  const trimmed = value.trim().toUpperCase();
  const normalized = trimmed.length === 9 && trimmed[4] === '-'
    ? `${trimmed.slice(0, 4)}${trimmed.slice(5)}`
    : trimmed;
  return AUTH_CODE.test(normalized) ? normalized : null;
}

export function normalizePlayerUUID(value: string): string {
  return value.toLowerCase();
}
