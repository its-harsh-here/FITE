import type { TransferMetadata } from './types';

export class ApiError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string, message: string) {
    super(message || code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

async function handleResponseError(response: Response, defaultMessage: string): Promise<never> {
  let errorData: { error?: string; message?: string } = {};
  try {
    errorData = await response.json();
  } catch {
    // Ignore JSON parse errors for non-JSON bodies
  }
  const code = errorData.error || (response.status === 410 ? 'TRANSFER_EXPIRED' : (response.status === 404 ? 'NOT_FOUND' : (response.status === 429 ? 'RATE_LIMITED' : 'ERROR')));
  const message = errorData.message || defaultMessage;
  throw new ApiError(response.status, code, message);
}

export interface CreateTransferRequest {
  fileName: string;
  fileSize: number;
  contentType: string;
}

export async function createTransfer(request: CreateTransferRequest): Promise<TransferMetadata> {
  const response = await fetch('/api/transfers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request)
  });
  if (!response.ok) {
    await handleResponseError(response, 'Failed to create transfer');
  }
  return response.json();
}

export async function uploadChunk(
  transferId: string, 
  chunkIndex: number, 
  chunk: Blob, 
  checksum: string
): Promise<void> {
  const response = await fetch(`/api/transfers/${transferId}/chunks/${chunkIndex}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/octet-stream',
      'Upload-Checksum': checksum,
      'X-Checksum-SHA256': checksum,
      'X-Chunk-Checksum': checksum
    },
    body: chunk
  });
  if (!response.ok) {
    await handleResponseError(response, `Failed to upload chunk ${chunkIndex}`);
  }
}

export async function completeTransfer(transferId: string): Promise<void> {
  const response = await fetch(`/api/transfers/${transferId}/complete`, { method: 'POST' });
  if (!response.ok) {
    await handleResponseError(response, 'Failed to complete transfer');
  }
}

export async function getTransferDetails(transferId: string, token?: string): Promise<TransferMetadata> {
  const cleanToken = token?.trim();
  const url = cleanToken ? `/api/transfers/${transferId}?token=${encodeURIComponent(cleanToken)}` : `/api/transfers/${transferId}`;
  const response = await fetch(url);
  if (!response.ok) {
    await handleResponseError(response, 'Failed to get transfer details');
  }
  return response.json();
}

export async function getTransferByCode(transferCode: string): Promise<TransferMetadata> {
  const cleanCode = encodeURIComponent(transferCode.trim().toUpperCase());
  const response = await fetch(`/api/transfers/code/${cleanCode}`);
  if (!response.ok) {
    await handleResponseError(response, 'Transfer not found');
  }
  return response.json();
}

export async function getAvailableChunks(transferId: string, token?: string): Promise<number[]> {
  const cleanToken = token?.trim();
  const url = cleanToken ? `/api/transfers/${transferId}/chunks?token=${encodeURIComponent(cleanToken)}` : `/api/transfers/${transferId}/chunks`;
  const response = await fetch(url);
  if (!response.ok) {
    await handleResponseError(response, 'Failed to get chunk availability');
  }
  return response.json();
}

export async function downloadChunk(transferId: string, chunkIndex: number, token?: string): Promise<{blob: Blob, checksum: string | null}> {
  const cleanToken = token?.trim();
  const url = cleanToken ? `/api/transfers/${transferId}/chunks/${chunkIndex}?token=${encodeURIComponent(cleanToken)}` : `/api/transfers/${transferId}/chunks/${chunkIndex}`;
  const response = await fetch(url);
  if (!response.ok) {
    await handleResponseError(response, `Failed to download chunk ${chunkIndex}`);
  }
  const checksum = response.headers.get('Upload-Checksum') || response.headers.get('X-Chunk-Checksum') || response.headers.get('X-Checksum-SHA256');
  const blob = await response.blob();
  return { blob, checksum };
}
