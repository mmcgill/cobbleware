import type { AppConfig } from '../config.ts';
import { bytesToBase32, digestBytes } from '../auth/crypto.ts';
import {
  PROGRAM_ID_PATTERN,
  type ProgramList,
  type ProgramManifest,
  ProgramManifestSchema,
  type ProgramMetadata,
  ProgramOperationConflictError,
  ProgramQuotaExceededError,
  type ProgramStore,
  type UploadProgramResult,
} from './types.ts';

const MANIFEST_FILENAME = 'programs.json';

type Clock = () => number;

export type ProgramStoreOptions = {
  clock?: Clock;
};

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
      const programs = [...manifest.programs].sort((left, right) => {
        const byDate = right.uploadedAt.localeCompare(left.uploadedAt);
        return byDate === 0 ? left.id.localeCompare(right.id) : byDate;
      });
      const usedBytes = programs.reduce((total, program) => total + program.sizeBytes, 0);

      return {
        programs,
        quotaBytes: this.config.playerProgramQuotaBytes,
        usedBytes,
        remainingBytes: Math.max(0, this.config.playerProgramQuotaBytes - usedBytes),
      };
    });
  }

  upload(
    playerUUID: string,
    content: Uint8Array,
    name: string,
  ): Promise<UploadProgramResult> {
    return this.withPlayerLock(playerUUID, async () => {
      const manifest = await this.readManifest(playerUUID);
      const id = bytesToBase32(await digestBytes(content));
      if (!PROGRAM_ID_PATTERN.test(id)) {
        throw new Error('generated program id has an invalid format');
      }

      const existing = manifest.programs.find((program) => program.id === id);
      if (existing !== undefined) {
        return { metadata: existing, created: false };
      }

      const usedBytes = manifest.programs.reduce((total, program) => total + program.sizeBytes, 0);
      if (usedBytes + content.byteLength > this.config.playerProgramQuotaBytes) {
        throw new ProgramQuotaExceededError('player program quota exceeded');
      }

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

      try {
        await this.writeManifest(playerUUID, {
          version: 1,
          programs: [...manifest.programs, metadata],
        });
      } catch (error) {
        try {
          await Deno.remove(programPath);
        } catch (cleanupError) {
          console.error(`failed to remove partially uploaded program ${programPath}`, cleanupError);
        }
        throw error;
      }

      return { metadata, created: true };
    });
  }

  delete(playerUUID: string, id: string): Promise<boolean> {
    if (!PROGRAM_ID_PATTERN.test(id)) {
      return Promise.resolve(false);
    }

    return this.withPlayerLock(playerUUID, async () => {
      const manifest = await this.readManifest(playerUUID);
      const index = manifest.programs.findIndex((program) => program.id === id);
      if (index < 0) {
        return false;
      }

      const programPath = `${this.playerDirectory(playerUUID)}/${id}.eszip`;
      try {
        await Deno.remove(programPath);
      } catch (error) {
        if (!(error instanceof Deno.errors.NotFound)) {
          throw error;
        }
      }

      const programs = [...manifest.programs];
      programs.splice(index, 1);
      await this.writeManifest(playerUUID, { version: 1, programs });
      return true;
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
