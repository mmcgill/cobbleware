export type PlayerIdentity = {
  playerUUID: string;
  playerProfileName: string;
};

export type PendingAuthentication = PlayerIdentity & {
  codeDigest: string;
  createdAtMs: number;
  expiresAtMs: number;
};

export type Session = PlayerIdentity & {
  tokenDigest: string;
  createdAtMs: number;
  expiresAtMs: number;
};

export type ConsumedAuthentication = {
  identity: PlayerIdentity;
  sessionToken: string;
};

export type BindPendingResult = 'created' | 'same-player' | 'conflict';

export type AuthStore = {
  bindPending(
    code: string,
    identity: PlayerIdentity,
    nowMs?: number,
  ): Promise<BindPendingResult>;
  consumePending(code: string, nowMs?: number): Promise<ConsumedAuthentication | null>;
  findSession(sessionToken: string, nowMs?: number): Promise<PlayerIdentity | null>;
};
