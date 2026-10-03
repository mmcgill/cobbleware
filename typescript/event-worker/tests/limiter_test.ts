import { LOG_BUCKET_CAPACITY, LogRateLimiter } from '../src/limiter.ts';

Deno.test('Log rate limiter starts full and refills each player independently', () => {
  let time = 0;
  const limiter = new LogRateLimiter(2, () => time);
  for (let index = 0; index < LOG_BUCKET_CAPACITY; index += 1) {
    assertEquals(limiter.tryConsume('one').consumed, true, 'initial token was missing');
  }
  assertEquals(limiter.tryConsume('one').consumed, false, 'empty bucket accepted a log');
  assertEquals(limiter.tryConsume('two').consumed, true, 'players shared a bucket');
  time += 500;
  assertEquals(limiter.tryConsume('one').consumed, true, 'half-second refill did not add a token');
  assertEquals(limiter.tryConsume('one').consumed, false, 'refill added too many tokens');
});

Deno.test('Log rate limiter snapshots the continuously refilled allowance', () => {
  let time = 0;
  const limiter = new LogRateLimiter(10, () => time);
  const consumed = limiter.tryConsume('one');
  assertEquals(consumed.available, 99, 'consuming a log did not reduce the allowance');
  time += 50;
  const snapshot = limiter.snapshots().get('one');
  assertEquals(snapshot?.available, 99.5, 'snapshot did not refill the allowance');
  assertEquals(snapshot?.capacity, LOG_BUCKET_CAPACITY, 'snapshot capacity changed');
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
