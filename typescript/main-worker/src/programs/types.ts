import { z } from 'zod';

export const PROGRAM_ID_PATTERN = /^[A-Z2-7]{52}$/;

export type ProgramMetadata = {
  id: string;
  name: string;
  uploadedAt: string;
  sizeBytes: number;
};

export type ProgramList = {
  programs: ProgramMetadata[];
  quotaBytes: number;
  usedBytes: number;
  remainingBytes: number;
};

export type UploadProgramResult = {
  metadata: ProgramMetadata;
  created: boolean;
};

export type ProgramStore = {
  list(playerUUID: string): Promise<ProgramList>;
  upload(
    playerUUID: string,
    content: Uint8Array,
    name: string,
  ): Promise<UploadProgramResult>;
  delete(playerUUID: string, id: string): Promise<boolean>;
};

const ProgramMetadataSchema = z.strictObject({
  id: z.string().regex(PROGRAM_ID_PATTERN),
  name: z.string(),
  uploadedAt: z.string(),
  sizeBytes: z.number().int().nonnegative(),
});

export const ProgramManifestSchema = z.strictObject({
  version: z.literal(1),
  programs: z.array(ProgramMetadataSchema),
});

export type ProgramManifest = z.infer<typeof ProgramManifestSchema>;

export class ProgramQuotaExceededError extends Error {
  override name = 'ProgramQuotaExceededError';
}

export class ProgramOperationConflictError extends Error {
  override name = 'ProgramOperationConflictError';
}
