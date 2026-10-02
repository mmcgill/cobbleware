import type { AppConfig } from '../config.ts';
import { BYTES_PER_MEBIBYTE } from '../config.ts';
import { bytesToBase32, digestBytes } from '../auth/crypto.ts';
import {
  type ActiveProgram,
  PROGRAM_ID_PATTERN,
  type ProgramList,
  type ProgramManifest,
  ProgramManifestSchema,
  type ProgramMetadata,
  ProgramOperationConflictError,
  ProgramQuotaExceededError,
  type ProgramStore,
  type RunningProgram,
  type RunOptions,
  type UploadProgramResult,
  type UploadProgramValidator,
} from './types.ts';

const MANIFEST_FILENAME = 'programs.json';
const ACTIVE_PROGRAM_READ_ATTEMPTS = 3;

type Clock = () => number;

export type ProgramStoreOptions = {
  clock?: Clock;
};

export function workerServicePath(playerUUID: string, programId: string): string {
  return `${playerWorkerServicePathPrefix(playerUUID)}${programId}`;
}

export function playerWorkerServicePathPrefix(playerUUID: string): string {
  return `cobbleware-${encodeURIComponent(playerUUID)}-`;
}

export class DurableProgramStore implements ProgramStore {
  private readonly lockedPlayers = new Set<string>();
  private readonly clock: Clock;

  constructor(
    private readonly config: AppConfig,
    options: ProgramStoreOptions = {},
  ) {
    this.clock = options.clock ?? Date.now;
  }

  list(playerUUID: string): Promise<ProgramList> {
    return this.withPlayerLock(playerUUID, async () => {
      const manifest = await this.readManifest(playerUUID);
      const activeProgram = newestProgram(manifest.programs);
      const programs = activeProgram === undefined ? [] : [activeProgram];
      const usedBytes = programs.reduce((total, program) => total + program.sizeBytes, 0);

      return {
        programs,
        quotaBytes: this.config.playerProgramQuotaBytes,
        usedBytes,
        remainingBytes: Math.max(0, this.config.playerProgramQuotaBytes - usedBytes),
      };
    });
  }

  async readActive(playerUUID: string): Promise<ActiveProgram | null> {
    for (let attempt = 0; attempt < ACTIVE_PROGRAM_READ_ATTEMPTS; attempt += 1) {
      const manifest = await this.readManifest(playerUUID);
      const metadata = newestProgram(manifest.programs);
      if (metadata === undefined) {
        return null;
      }

      try {
        const content = await Deno.readFile(
          `${this.playerDirectory(playerUUID)}/${metadata.id}.eszip`,
        );
        return { metadata, content };
      } catch (error) {
        if (
          !(error instanceof Deno.errors.NotFound) || attempt === ACTIVE_PROGRAM_READ_ATTEMPTS - 1
        ) {
          throw error;
        }
      }
    }

    throw new Error('active program read attempts exhausted');
  }

  async run(playerUUID: string, opts: RunOptions): Promise<RunningProgram> {
    const program = await this.readActive(playerUUID);
    if (program === null) {
      throw new Error('player has no active program');
    }

    const worker = await EdgeRuntime.userWorkers.create({
      servicePath: workerServicePath(playerUUID, program.metadata.id),
      maybeEszip: program.content,
      maybeEntrypoint: 'file:///src/index.ts',
      memoryLimitMb: opts.memoryQuotaBytes / BYTES_PER_MEBIBYTE,
    });
    return { program, worker };
  }

  upload(
    playerUUID: string,
    content: Uint8Array,
    name: string,
    validate?: UploadProgramValidator,
  ): Promise<UploadProgramResult> {
    return this.withPlayerLock(playerUUID, async () => {
      const manifest = await this.readManifest(playerUUID);
      const id = bytesToBase32(await digestBytes(content));
      if (!PROGRAM_ID_PATTERN.test(id)) {
        throw new Error('generated program id has an invalid format');
      }

      const activeProgram = newestProgram(manifest.programs);
      const existing = activeProgram?.id === id ? activeProgram : undefined;
      if (existing !== undefined) {
        return { metadata: existing, created: false };
      }

      if (content.byteLength > this.config.playerProgramQuotaBytes) {
        throw new ProgramQuotaExceededError('player program quota exceeded');
      }

      await validate?.();

      await this.removeUnreferencedPrograms(playerUUID, manifest);

      const metadata: ProgramMetadata = {
        id,
        name,
        uploadedAt: new Date(this.clock()).toISOString(),
        sizeBytes: content.byteLength,
      };
      const playerDirectory = this.playerDirectory(playerUUID);
      const programPath = `${playerDirectory}/${id}.eszip`;

      await Deno.mkdir(playerDirectory, { recursive: true });
      await writeFileAtomically(programPath, content);

      await this.writeManifest(playerUUID, { version: 1, programs: [metadata] });

      for (const oldProgram of manifest.programs) {
        if (oldProgram.id !== id) {
          await removeIfPresent(`${playerDirectory}/${oldProgram.id}.eszip`);
        }
      }

      return { metadata, created: true };
    });
  }

  private playerDirectory(playerUUID: string): string {
    const separator = this.config.storageDir.endsWith('/') || this.config.storageDir.endsWith('\\')
      ? ''
      : '/';
    return `${this.config.storageDir}${separator}programs/${playerUUID}`;
  }

  private manifestPath(playerUUID: string): string {
    return `${this.playerDirectory(playerUUID)}/${MANIFEST_FILENAME}`;
  }

  private async readManifest(playerUUID: string): Promise<ProgramManifest> {
    try {
      const text = await Deno.readTextFile(this.manifestPath(playerUUID));
      const parsed = ProgramManifestSchema.safeParse(JSON.parse(text));
      if (!parsed.success) {
        throw new Error('program manifest has an invalid shape');
      }
      return parsed.data;
    } catch (error) {
      if (error instanceof Deno.errors.NotFound) {
        return { version: 1, programs: [] };
      }
      if (error instanceof SyntaxError) {
        throw new Error('program manifest is not valid JSON');
      }
      throw error;
    }
  }

  private async writeManifest(playerUUID: string, manifest: ProgramManifest): Promise<void> {
    const playerDirectory = this.playerDirectory(playerUUID);
    await Deno.mkdir(playerDirectory, { recursive: true });
    await writeFileAtomically(
      this.manifestPath(playerUUID),
      new TextEncoder().encode(JSON.stringify(manifest, null, 2)),
    );
  }

  private async removeUnreferencedPrograms(
    playerUUID: string,
    manifest: ProgramManifest,
  ): Promise<void> {
    const referencedFiles = new Set(manifest.programs.map((program) => `${program.id}.eszip`));
    const playerDirectory = this.playerDirectory(playerUUID);
    try {
      for await (const entry of Deno.readDir(playerDirectory)) {
        if (!entry.isFile || !entry.name.endsWith('.eszip') || referencedFiles.has(entry.name)) {
          continue;
        }
        await Deno.remove(`${playerDirectory}/${entry.name}`);
      }
    } catch (error) {
      if (error instanceof Deno.errors.NotFound) {
        return;
      }
      throw error;
    }
  }

  private async withPlayerLock<T>(playerUUID: string, operation: () => Promise<T>): Promise<T> {
    if (this.lockedPlayers.has(playerUUID)) {
      throw new ProgramOperationConflictError('another program operation is in progress');
    }
    this.lockedPlayers.add(playerUUID);

    try {
      return await operation();
    } finally {
      this.lockedPlayers.delete(playerUUID);
    }
  }
}

async function writeFileAtomically(path: string, content: Uint8Array): Promise<void> {
  const temporaryPath = `${path}.${crypto.randomUUID()}.tmp`;
  try {
    await Deno.writeFile(temporaryPath, content, { createNew: true });
    await Deno.rename(temporaryPath, path);
  } catch (error) {
    try {
      await Deno.remove(temporaryPath);
    } catch (cleanupError) {
      console.error(`failed to remove temporary file ${temporaryPath}`, cleanupError);
    }
    throw error;
  }
}

async function removeIfPresent(path: string): Promise<void> {
  try {
    await Deno.remove(path);
  } catch (error) {
    if (!(error instanceof Deno.errors.NotFound)) {
      throw error;
    }
  }
}

function newestProgram(programs: ProgramMetadata[]): ProgramMetadata | undefined {
  return programs.reduce<ProgramMetadata | undefined>((newest, program) => {
    if (newest === undefined) {
      return program;
    }
    const byDate = program.uploadedAt.localeCompare(newest.uploadedAt);
    return byDate > 0 || (byDate === 0 && program.id > newest.id) ? program : newest;
  }, undefined);
}
