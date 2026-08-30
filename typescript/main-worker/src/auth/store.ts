import type { AppConfig } from '../config.ts';
import { digest, randomToken } from './crypto.ts';
import {
  normalizeAuthCode,
  normalizePlayerUUID,
  type PersistedAuthState,
  PersistedAuthStateSchema,
} from './validation.ts';
import type {
  AuthStore,
  BindPendingResult,
  ConsumedAuthentication,
  PendingAuthentication,
  PlayerIdentity,
  Session,
} from './types.ts';

type Clock = () => number;

export type AuthStoreOptions = {
  clock?: Clock;
  tokenGenerator?: () => string;
};

export class DurableAuthStore implements AuthStore {
  private readonly pending = new Map<string, PendingAuthentication>();
  private readonly sessions = new Map<string, Session>();
  private readonly clock: Clock;
  private readonly tokenGenerator: () => string;
  private lock: Promise<void> = Promise.resolve();
  private readonly ready: Promise<void>;

  private constructor(
    private readonly config: AppConfig,
    options: AuthStoreOptions,
  ) {
    this.clock = options.clock ?? Date.now;
    this.tokenGenerator = options.tokenGenerator ?? randomToken;
    this.ready = this.load();
  }

  static async open(config: AppConfig, options: AuthStoreOptions = {}): Promise<DurableAuthStore> {
    const store = new DurableAuthStore(config, options);
    await store.ready;
    return store;
  }

  async bindPending(
    code: string,
    identity: PlayerIdentity,
    nowMs = this.clock(),
  ): Promise<BindPendingResult> {
    await this.ready;
    const normalizedCode = normalizeAuthCode(code);
    if (normalizedCode === null) {
      throw new Error('invalid authentication code');
    }

    return this.withLock(() => {
      this.cleanupPending(nowMs);

      return digest(normalizedCode).then((codeDigest) => {
        const existing = this.pending.get(codeDigest);
        if (existing === undefined) {
          this.pending.set(codeDigest, {
            ...identity,
            playerUUID: normalizePlayerUUID(identity.playerUUID),
            codeDigest,
            createdAtMs: nowMs,
            expiresAtMs: nowMs + this.config.pendingAuthCodeLifetimeMs,
          });
          return 'created';
        }

        return existing.playerUUID === normalizePlayerUUID(identity.playerUUID)
          ? 'same-player'
          : 'conflict';
      });
    });
  }

  async consumePending(code: string, nowMs = this.clock()): Promise<ConsumedAuthentication | null> {
    await this.ready;
    const normalizedCode = normalizeAuthCode(code);
    if (normalizedCode === null) {
      return null;
    }

    return this.withLock(async () => {
      this.cleanupPending(nowMs);
      this.cleanupSessions(nowMs);

      const codeDigest = await digest(normalizedCode);
      const pending = this.pending.get(codeDigest);
      if (pending === undefined) {
        return null;
      }

      const sessionToken = this.tokenGenerator();
      const session: Session = {
        playerUUID: pending.playerUUID,
        playerProfileName: pending.playerProfileName,
        tokenDigest: await digest(sessionToken),
        createdAtMs: nowMs,
        expiresAtMs: nowMs + this.config.sessionLifetimeMs,
      };

      this.pending.delete(codeDigest);
      this.sessions.set(session.tokenDigest, session);

      try {
        await this.persist();
      } catch (error) {
        this.sessions.delete(session.tokenDigest);
        this.pending.set(codeDigest, pending);
        throw error;
      }

      return {
        identity: {
          playerUUID: session.playerUUID,
          playerProfileName: session.playerProfileName,
        },
        sessionToken,
      };
    });
  }

  async findSession(sessionToken: string, nowMs = this.clock()): Promise<PlayerIdentity | null> {
    await this.ready;
    return this.withLock(async () => {
      const changed = this.cleanupSessions(nowMs);
      if (changed) {
        await this.persist();
      }

      const tokenDigest = await digest(sessionToken);
      const session = this.sessions.get(tokenDigest);
      if (session === undefined || session.expiresAtMs <= nowMs) {
        return null;
      }

      return {
        playerUUID: session.playerUUID,
        playerProfileName: session.playerProfileName,
      };
    });
  }

  async revokeSession(sessionToken: string, nowMs = this.clock()): Promise<void> {
    await this.ready;
    await this.withLock(async () => {
      let changed = this.cleanupSessions(nowMs);
      const tokenDigest = await digest(sessionToken);
      if (this.sessions.delete(tokenDigest)) {
        changed = true;
      }
      if (changed) {
        await this.persist();
      }
    });
  }

  private async load(): Promise<void> {
    let text: string;
    try {
      text = await Deno.readTextFile(this.authStatePath());
    } catch (error) {
      if (error instanceof Deno.errors.NotFound) {
        return;
      }
      throw error;
    }

    let value: unknown;
    try {
      value = JSON.parse(text);
    } catch {
      throw new Error('auth state is not valid JSON');
    }

    const parsed = PersistedAuthStateSchema.safeParse(value);
    if (!parsed.success) {
      throw new Error('auth state has an invalid shape');
    }

    const state: PersistedAuthState = parsed.data;
    const nowMs = this.clock();
    for (const session of state.sessions) {
      if (session.expiresAtMs > nowMs) {
        this.sessions.set(session.tokenDigest, session);
      }
    }

    if (this.sessions.size !== state.sessions.length) {
      await this.persist();
    }
  }

  private cleanupPending(nowMs: number): boolean {
    let changed = false;
    for (const [codeDigest, pending] of this.pending) {
      if (pending.expiresAtMs <= nowMs) {
        this.pending.delete(codeDigest);
        changed = true;
      }
    }
    return changed;
  }

  private cleanupSessions(nowMs: number): boolean {
    let changed = false;
    for (const [tokenDigest, session] of this.sessions) {
      if (session.expiresAtMs <= nowMs) {
        this.sessions.delete(tokenDigest);
        changed = true;
      }
    }
    return changed;
  }

  private async persist(): Promise<void> {
    const authStatePath = this.authStatePath();
    const directory = authStatePath.replace(/[\\/]auth\.json$/, '');
    await Deno.mkdir(directory, { recursive: true });

    const state: PersistedAuthState = {
      version: 1,
      sessions: [...this.sessions.values()],
    };
    const temporaryPath = `${authStatePath}.${crypto.randomUUID()}.tmp`;
    try {
      await Deno.writeTextFile(temporaryPath, JSON.stringify(state, null, 2));
      await Deno.rename(temporaryPath, authStatePath);
    } catch (error) {
      try {
        await Deno.remove(temporaryPath);
      } catch {
        // Preserve the original persistence error.
      }
      throw error;
    }
  }

  private authStatePath(): string {
    const separator = this.config.storageDir.endsWith('/') || this.config.storageDir.endsWith('\\')
      ? ''
      : '/';
    return `${this.config.storageDir}${separator}auth.json`;
  }

  private async withLock<T>(operation: () => Promise<T>): Promise<T> {
    let release!: () => void;
    const next = new Promise<void>((resolve) => {
      release = resolve;
    });
    const previous = this.lock;
    this.lock = previous.then(() => next);
    await previous;

    try {
      return await operation();
    } finally {
      release();
    }
  }
}
