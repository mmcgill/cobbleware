export function workerServicePath(playerUUID: string, programId: string): string {
  return `${playerWorkerServicePathPrefix(playerUUID)}${programId}`;
}

export function playerWorkerServicePathPrefix(playerUUID: string): string {
  return `cobbleware-${encodeURIComponent(playerUUID)}-`;
}
