import { toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';

const INITIAL_RECONNECT_MS = 100;
const MAX_RECONNECT_MS = 5_000;

export type WebSocketFactory = (url: string, protocols: string) => WebSocket;

/** Best-effort WebSocket producer; callers never wait for connection or send completion. */
class ProtobufRelay<T> {
  private socket: WebSocket | undefined;
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  private reconnectDelayMs = INITIAL_RECONNECT_MS;
  private stopped = false;

  constructor(
    private readonly url: string,
    private readonly apiKey: string,
    private readonly encode: (value: T) => Uint8Array,
    private readonly createSocket: WebSocketFactory = (url, protocols) =>
      new WebSocket(url, protocols),
  ) {}

  start(): void {
    this.connect();
  }

  send(entry: T): void {
    const socket = this.socket;
    if (socket === undefined) {
      this.connect();
      return;
    }
    if (socket.readyState !== WebSocket.OPEN) {
      this.replace(socket);
      return;
    }
    try {
      socket.send(this.encode(entry));
    } catch {
      this.disconnected(socket);
    }
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== undefined) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    const socket = this.socket;
    this.socket = undefined;
    try {
      socket?.close(1000, 'event worker stopping');
    } catch {
      // The socket has already closed.
    }
  }

  private connect(): void {
    if (this.stopped || this.socket !== undefined) return;
    let socket: WebSocket;
    try {
      socket = this.createSocket(this.url, eventWorkerProtocol(this.apiKey));
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      if (this.socket === socket) this.reconnectDelayMs = INITIAL_RECONNECT_MS;
    };
    socket.onclose = () => this.disconnected(socket);
    socket.onerror = () => this.disconnected(socket);
  }

  private replace(socket: WebSocket): void {
    if (this.socket !== socket) return;
    this.socket = undefined;
    try {
      socket.close();
    } catch {
      // The socket has already closed.
    }
    this.connect();
  }

  private disconnected(socket: WebSocket): void {
    if (this.socket !== socket) return;
    this.socket = undefined;
    try {
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
    } catch {
      // The socket has already closed.
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer !== undefined) return;
    const delay = this.reconnectDelayMs;
    this.reconnectDelayMs = Math.min(MAX_RECONNECT_MS, this.reconnectDelayMs * 2);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }
}

export class ExecutionLogRelay extends ProtobufRelay<Internal.ExecutionLogEntry> {
  constructor(
    url: string,
    apiKey: string,
    createSocket?: WebSocketFactory,
  ) {
    super(url, apiKey, (entry) => toBinary(Internal.ExecutionLogEntrySchema, entry), createSocket);
  }
}

export class LogStatsRelay extends ProtobufRelay<Internal.LogStats> {
  constructor(
    url: string,
    apiKey: string,
    createSocket?: WebSocketFactory,
  ) {
    super(url, apiKey, (entry) => toBinary(Internal.LogStatsSchema, entry), createSocket);
  }
}

export function eventWorkerProtocol(apiKey: string): string {
  const bytes = new TextEncoder().encode(apiKey);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return `cobbleware.bearer.${
    btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
  }`;
}
