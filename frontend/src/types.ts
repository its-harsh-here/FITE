export interface TransferMetadata {
  transferId: string;
  shareToken: string;
  transferCode?: string;
  chunkSize: number;
  totalChunks: number;
  createdAt?: string;
  expiresAt: string;
  fileName: string;
  fileSize: number;
  contentType: string;
  status?: 'CREATED' | 'UPLOADING' | 'COMPLETE' | 'EXPIRED' | 'FAILED';
}

export type TransferStatus = 'idle' | 'starting' | 'progressing' | 'paused' | 'error' | 'completed' | 'waiting';

export interface TransferProgress {
  status: TransferStatus;
  progress: number;
  transferredBytes: number;
  totalBytes: number;
  metadata: TransferMetadata | null;
  error?: Error;
}
