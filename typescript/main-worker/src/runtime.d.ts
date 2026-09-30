interface CobblewareUserWorkerClosed {
  // Reserved for future worker-termination metadata.
}

interface CobblewareUserWorker {
  readonly closed: Promise<CobblewareUserWorkerClosed>;
  fetch(request: Request): Promise<Response>;
}

interface CobblewareUserWorkerOptions {
  servicePath: string;
  maybeEszip: Uint8Array;
  maybeEntrypoint: string;
  memoryLimitMb: number;
}

interface CobblewareHeapStatistics {
  usedHeapSize: number;
  externalMemory: number;
}

interface CobblewareWorkerHeapStatisticsWithServicePath {
  servicePath: string;
  stats?: CobblewareHeapStatistics;
}

declare const EdgeRuntime: {
  applySupabaseTag(source: Request, destination: Request): void;
  userWorkers: {
    create(options: CobblewareUserWorkerOptions): Promise<CobblewareUserWorker>;
    memStats(): Promise<
      | Map<string, CobblewareWorkerHeapStatisticsWithServicePath>
      | Record<string, CobblewareWorkerHeapStatisticsWithServicePath>
    >;
  };
};
