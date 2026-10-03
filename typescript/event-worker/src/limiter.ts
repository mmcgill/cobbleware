export const LOG_BUCKET_CAPACITY = 100;
const IDLE_BUCKET_LIFETIME_MS = 5 * 60 * 1000;

type Bucket = {
  tokens: number;
  refilledAt: number;
  lastSeenAt: number;
};

export type LogTokenBucketSnapshot = {
  available: number;
  capacity: number;
};

export type LogTokenBucketConsumption = LogTokenBucketSnapshot & {
  consumed: boolean;
};

/** Per-player token buckets used only for program-originated log entries. */
export class LogRateLimiter {
  private readonly buckets = new Map<string, Bucket>();
  private operationsSincePrune = 0;

  constructor(
    private readonly tokensPerSecond: number,
    private readonly now: () => number = Date.now,
  ) {}

  tryConsume(playerId: string): LogTokenBucketConsumption {
    const current = this.now();
    let bucket = this.buckets.get(playerId);
    if (bucket === undefined) {
      bucket = { tokens: LOG_BUCKET_CAPACITY, refilledAt: current, lastSeenAt: current };
      this.buckets.set(playerId, bucket);
    } else {
      const elapsedMs = Math.max(0, current - bucket.refilledAt);
      bucket.tokens = Math.min(
        LOG_BUCKET_CAPACITY,
        bucket.tokens + elapsedMs * this.tokensPerSecond / 1_000,
      );
      bucket.refilledAt = current;
      bucket.lastSeenAt = current;
    }

    this.operationsSincePrune += 1;
    if (this.operationsSincePrune >= LOG_BUCKET_CAPACITY) this.prune(current);
    if (bucket.tokens < 1) {
      return { available: bucket.tokens, capacity: LOG_BUCKET_CAPACITY, consumed: false };
    }
    bucket.tokens -= 1;
    return { available: bucket.tokens, capacity: LOG_BUCKET_CAPACITY, consumed: true };
  }

  /** Returns each recently used bucket after applying its time-based refill. */
  snapshots(): ReadonlyMap<string, LogTokenBucketSnapshot> {
    const current = this.now();
    this.prune(current);
    const snapshots = new Map<string, LogTokenBucketSnapshot>();
    for (const [playerId, bucket] of this.buckets) {
      const elapsedMs = Math.max(0, current - bucket.refilledAt);
      bucket.tokens = Math.min(
        LOG_BUCKET_CAPACITY,
        bucket.tokens + elapsedMs * this.tokensPerSecond / 1_000,
      );
      bucket.refilledAt = current;
      snapshots.set(playerId, {
        available: bucket.tokens,
        capacity: LOG_BUCKET_CAPACITY,
      });
    }
    return snapshots;
  }

  private prune(current: number): void {
    this.operationsSincePrune = 0;
    for (const [playerId, bucket] of this.buckets) {
      if (current - bucket.lastSeenAt > IDLE_BUCKET_LIFETIME_MS) this.buckets.delete(playerId);
    }
  }
}
