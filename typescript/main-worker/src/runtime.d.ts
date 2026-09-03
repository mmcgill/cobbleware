interface CobblewareUserWorker {
  fetch(request: Request): Promise<Response>;
}

interface CobblewareUserWorkerOptions {
  servicePath: string;
  maybeEszip: Uint8Array;
  maybeEntrypoint: string;
}

declare const EdgeRuntime: {
  applySupabaseTag(source: Request, destination: Request): void;
  userWorkers: {
    create(options: CobblewareUserWorkerOptions): Promise<CobblewareUserWorker>;
  };
};
