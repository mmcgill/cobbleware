/// <reference path="../runtime.d.ts" />

import { BYTES_PER_MEBIBYTE } from '../config.ts';
import { invokeStartStates } from './program-api.ts';
import { workerServicePath } from './store.ts';
import type { StartStates } from './types.ts';

export const PROGRAM_OPERATIONS_WORKER_UUID = '50e4fc86-4c74-47c5-a39b-2c3c9f8f4d03';

const PROGRAM_OPERATIONS_SERVICE_PATH = workerServicePath(
  PROGRAM_OPERATIONS_WORKER_UUID,
  'program-operations',
);

export type LockedProgramOperations = {
  startStates(content: Uint8Array): Promise<StartStates>;
};

export type ProgramOperations = {
  exclusive<T>(operation: (locked: LockedProgramOperations) => Promise<T>): Promise<T>;
};

/**
 * Serializes transient program validation and start-state reads through one Runtime worker pool key.
 */
export class RuntimeProgramOperations implements ProgramOperations {
  private serial = Promise.resolve();

  constructor(private readonly memoryQuotaBytes: number) {}

  async exclusive<T>(operation: (locked: LockedProgramOperations) => Promise<T>): Promise<T> {
    let release: () => void;
    const acquired = new Promise<void>((resolve) => {
      release = resolve;
    });
    const previous = this.serial;
    this.serial = previous.catch(() => {}).then(() => acquired);
    await previous.catch(() => {});
    try {
      return await operation({ startStates: (content) => this.startStates(content) });
    } finally {
      release!();
    }
  }

  private async startStates(content: Uint8Array): Promise<StartStates> {
    let worker: CobblewareUserWorker | undefined;
    try {
      worker = await EdgeRuntime.userWorkers.create({
        servicePath: PROGRAM_OPERATIONS_SERVICE_PATH,
        maybeEszip: content,
        maybeEntrypoint: 'file:///src/index.ts',
        memoryLimitMb: this.memoryQuotaBytes / BYTES_PER_MEBIBYTE,
      });
      return await invokeStartStates(worker);
    } finally {
      if (worker !== undefined) {
        try {
          worker.retire(0);
        } finally {
          await worker.closed;
        }
      }
    }
  }
}
