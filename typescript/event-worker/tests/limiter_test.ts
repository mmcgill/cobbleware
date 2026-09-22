import { LOG_BUCKET_CAPACITY, LogRateLimiter } from '../src/limiter.ts';

Deno.test('Log rate limiter starts full and refills each player independently', () => {
  let time = 0;
  const limiter = new LogRateLimiter(2, () => time);
  for (let index = 0; index < LOG_BUCKET_CAPACITY; index += 1) {
    assertEquals(limiter.tryConsume('one'), true, 'initial token was missing');
  }
  assertEquals(limiter.tryConsume('one'), false, 'empty bucket accepted a log');
  assertEquals(limiter.tryConsume('two'), true, 'players shared a bucket');
  time += 500;
  assertEquals(limiter.tryConsume('one'), true, 'half-second refill did not add a token');
  assertEquals(limiter.tryConsume('one'), false, 'refill added too many tokens');
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
