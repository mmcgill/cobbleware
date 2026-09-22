import { Common, create } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import { eventWorkerProtocol, ExecutionLogRelay } from '../src/relay.ts';

Deno.test('Execution log relay authenticates and sends only on an open socket', () => {
  const socket = new FakeSocket();
  let url = '';
  let protocols = '';
  const relay = new ExecutionLogRelay(
    'ws://127.0.0.1:9000/modapi/v1/logs',
    'secret',
    (actualUrl, actualProtocols) => {
      url = actualUrl;
      protocols = actualProtocols;
      return socket as unknown as WebSocket;
    },
  );

  relay.start();
  relay.send(entry());
  assertEquals(socket.sent.length, 0, 'connecting socket accepted a log');
  socket.readyState = WebSocket.OPEN;
  socket.onopen?.(new Event('open'));
  relay.send(entry());
  assertEquals(url, 'ws://127.0.0.1:9000/modapi/v1/logs', 'relay URL changed');
  assertEquals(protocols, eventWorkerProtocol('secret'), 'relay omitted Event Worker credential');
  assertEquals(socket.sent.length, 1, 'open socket did not receive a log');
  relay.stop();
});

class FakeSocket {
  readyState = WebSocket.CONNECTING;
  readonly sent: unknown[] = [];
  onopen: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;

  send(value: unknown): void {
    this.sent.push(value);
  }

  close(): void {
    this.readyState = WebSocket.CLOSED;
  }
}

function entry(): Internal.ExecutionLogEntry {
  return create(Internal.ExecutionLogEntrySchema, {
    playerId: create(Common.UuidSchema, { high: 1n, low: 2n }),
    inner: {
      case: 'exception',
      value: create(Internal.ExceptionLogEntrySchema, { msg: 'boom' }),
    },
  });
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
