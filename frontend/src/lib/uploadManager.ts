import { calculateSHA256 } from './crypto';
import { createTransfer, getAvailableChunks, uploadChunk, completeTransfer, ApiError } from '../api';
import type { TransferMetadata, TransferStatus, TransferProgress } from '../types';

const SENDER_ACTIVE_TRANSFER_KEY = 'sender_active_transfer_id';
const SENDER_TRANSFER_PREFIX = 'sender_transfer_';
const MAX_CHUNK_RETRIES = 3;

export class UploadManager {
  private file: File | null = null;
  private state: TransferStatus = 'idle';
  private transferDetails: TransferMetadata | null = null;
  
  private completedChunks = new Set<number>();
  private inProgressChunks = new Set<number>();
  private chunkRetryCounts = new Map<number, number>();
  private retryTimers = new Map<number, ReturnType<typeof setTimeout>>();
  
  private activeUploads = 0;
  private maxConcurrency = 1; // Strict bounded sequential upload (no unbounded concurrency)
  private currentOperationId = 0;
  private cancelSource: AbortController | null = null;
  private onProgressCb?: (progress: TransferProgress) => void;
  private error: Error | undefined;

  constructor(maxConcurrency: number = 1) {
    this.maxConcurrency = maxConcurrency;
  }

  onProgress(cb: (progress: TransferProgress) => void) {
    this.onProgressCb = cb;
  }

  private clearRetryTimers() {
    this.retryTimers.forEach(timer => clearTimeout(timer));
    this.retryTimers.clear();
  }

  private cleanupLocalStorage() {
    localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
    if (this.transferDetails) {
      localStorage.removeItem(SENDER_TRANSFER_PREFIX + this.transferDetails.transferId);
    }
  }

  private notify() {
    if (this.onProgressCb && this.file) {
      const uploadedBytes = this.completedChunks.size * (this.transferDetails?.chunkSize || 0);
      const boundedBytes = Math.min(uploadedBytes, this.file.size);
      this.onProgressCb({
        status: this.state,
        progress: this.file.size === 0 ? 100 : Math.round((boundedBytes / this.file.size) * 100),
        transferredBytes: boundedBytes,
        totalBytes: this.file.size,
        metadata: this.transferDetails,
        error: this.error,
      });
    }
  }

  async start(file: File, existingTransfer?: TransferMetadata) {
    if (this.state === 'progressing' || this.state === 'starting') return;
    
    this.clearRetryTimers();
    const opId = ++this.currentOperationId;
    
    if (this.file !== file) {
      this.file = file;
      this.transferDetails = existingTransfer || null;
      this.completedChunks.clear();
      this.inProgressChunks.clear();
      this.chunkRetryCounts.clear();
    }
    
    this.state = 'starting';
    this.error = undefined;
    this.notify();

    try {
      // If no transfer details supplied, check localStorage for matching active transfer
      if (!this.transferDetails) {
        const activeId = localStorage.getItem(SENDER_ACTIVE_TRANSFER_KEY);
        if (activeId) {
          const raw = localStorage.getItem(SENDER_TRANSFER_PREFIX + activeId);
          if (raw) {
            try {
              const saved = JSON.parse(raw) as TransferMetadata;
              if (saved.fileName === file.name && saved.fileSize === file.size) {
                this.transferDetails = saved;
              }
            } catch {
              localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
              localStorage.removeItem(SENDER_TRANSFER_PREFIX + activeId);
            }
          }
        }
      }

      if (!this.transferDetails) {
        const created = await createTransfer({
          fileName: file.name,
          fileSize: file.size,
          contentType: file.type || 'application/octet-stream',
        });
        
        // Check if state or operation changed (e.g. paused) while awaiting server creation
        if (this.currentOperationId !== opId || this.state !== 'starting') return;
        
        this.transferDetails = created;
        localStorage.setItem(SENDER_ACTIVE_TRANSFER_KEY, this.transferDetails.transferId);
        localStorage.setItem(SENDER_TRANSFER_PREFIX + this.transferDetails.transferId, JSON.stringify(this.transferDetails));
      }
      
      await this.reconcile();
      
      // Check if paused or stopped during asynchronous reconciliation
      if (this.currentOperationId !== opId || this.state !== 'starting') return;
      
      this.state = 'progressing';
      this.cancelSource = new AbortController();
      this.notify();
      this.processQueue();
    } catch (e) {
      if (this.currentOperationId === opId && this.state === 'starting') {
        if (e instanceof ApiError && (e.code === 'TRANSFER_EXPIRED' || e.code === 'TRANSFER_NOT_FOUND')) {
          this.cleanupLocalStorage();
        }
        this.state = 'error';
        this.error = e as Error;
        this.notify();
      }
    }
  }

  pause() {
    if (this.state === 'progressing' || this.state === 'starting') {
      this.state = 'paused';
      this.currentOperationId++;
      this.clearRetryTimers();
      this.cancelSource?.abort();
      this.inProgressChunks.clear();
      this.notify();
    }
  }

  async resume() {
    if (this.state === 'paused' || this.state === 'error') {
      this.clearRetryTimers();
      const opId = ++this.currentOperationId;
      this.state = 'starting';
      this.error = undefined;
      this.chunkRetryCounts.clear();
      this.notify();
      try {
        await this.reconcile(); // Reconcile against authoritative backend state on resume
        if (this.currentOperationId !== opId || this.state !== 'starting') return;
        this.state = 'progressing';
        this.cancelSource = new AbortController();
        this.notify();
        this.processQueue();
      } catch (e) {
        if (this.currentOperationId === opId && this.state === 'starting') {
          if (e instanceof ApiError && (e.code === 'TRANSFER_EXPIRED' || e.code === 'TRANSFER_NOT_FOUND')) {
            this.cleanupLocalStorage();
          }
          this.state = 'error';
          this.error = e as Error;
          this.notify();
        }
      }
    }
  }

  private async reconcile() {
    if (!this.transferDetails) return;
    try {
      const available = await getAvailableChunks(this.transferDetails.transferId, this.transferDetails.shareToken || '');
      this.completedChunks = new Set(available);
      // Remove any completed chunks from in-progress to prevent double-scheduling
      available.forEach(idx => this.inProgressChunks.delete(idx));
    } catch (e) {
      console.error("Failed to reconcile state against backend", e);
      throw e;
    }
  }

  private async processQueue() {
    if (this.state !== 'progressing' || !this.file || !this.transferDetails) return;

    if (this.completedChunks.size === this.transferDetails.totalChunks) {
      try {
        this.state = 'starting'; // Show loading state during completion call
        this.notify();
        await completeTransfer(this.transferDetails.transferId);
        this.cleanupLocalStorage();
        this.state = 'completed';
        this.notify();
      } catch (e) {
        this.state = 'error';
        this.error = e as Error;
        this.notify();
      }
      return;
    }

    while (this.activeUploads < this.maxConcurrency && this.state === 'progressing') {
      const nextChunkIndex = this.getNextChunkIndex();
      if (nextChunkIndex === -1) break; // All chunks scheduled or done

      this.activeUploads++;
      this.uploadChunkWrapper(nextChunkIndex).finally(() => {
        this.activeUploads--;
        this.processQueue();
      });
    }
  }

  private getNextChunkIndex(): number {
    if (!this.transferDetails) return -1;
    for (let i = 0; i < this.transferDetails.totalChunks; i++) {
      if (!this.completedChunks.has(i) && !this.inProgressChunks.has(i) && !this.retryTimers.has(i)) {
        this.inProgressChunks.add(i); 
        return i;
      }
    }
    return -1;
  }

  private async uploadChunkWrapper(chunkIndex: number) {
    if (!this.file || !this.transferDetails) return;
    const opId = this.currentOperationId;
    
    // Safety check: skip if reconciled as completed
    if (this.completedChunks.has(chunkIndex)) {
      this.inProgressChunks.delete(chunkIndex);
      return;
    }

    try {
      const start = chunkIndex * this.transferDetails.chunkSize;
      const end = Math.min(start + this.transferDetails.chunkSize, this.file.size);
      const chunkBlob = this.file.slice(start, end); // Memory bounded to active chunk
      
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }

      const checksum = await calculateSHA256(chunkBlob);
      
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }

      await uploadChunk(this.transferDetails.transferId, chunkIndex, chunkBlob, checksum);
      
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }
      
      this.inProgressChunks.delete(chunkIndex);
      this.chunkRetryCounts.delete(chunkIndex);
      this.completedChunks.add(chunkIndex);
      this.notify();
      
    } catch (e) {
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }

      // Check if error is terminal
      if (e instanceof ApiError) {
        if (e.code === 'TRANSFER_EXPIRED' || e.code === 'TRANSFER_NOT_FOUND' || e.code === 'FORBIDDEN') {
          this.inProgressChunks.delete(chunkIndex);
          this.cleanupLocalStorage();
          this.state = 'error';
          this.error = e;
          this.notify();
          return;
        }
        if (e.code === 'CHUNK_CONFLICT') {
          // Reconcile available state on conflict
          try {
            await this.reconcile();
            this.inProgressChunks.delete(chunkIndex);
            if (this.completedChunks.has(chunkIndex)) {
              this.notify();
              this.processQueue();
              return;
            }
          } catch {}
          this.inProgressChunks.delete(chunkIndex);
          this.state = 'error';
          this.error = e;
          this.notify();
          return;
        }
      }

      // Recoverable error: retry with exponential backoff
      const retries = (this.chunkRetryCounts.get(chunkIndex) || 0) + 1;
      this.chunkRetryCounts.set(chunkIndex, retries);

      if (retries <= MAX_CHUNK_RETRIES) {
        const backoffMs = Math.min(500 * Math.pow(2, retries - 1), 3000);
        console.warn(`Chunk ${chunkIndex} upload failed (attempt ${retries}/${MAX_CHUNK_RETRIES}), retrying in ${backoffMs}ms...`, e);
        const timer = setTimeout(() => {
          this.retryTimers.delete(chunkIndex);
          this.inProgressChunks.delete(chunkIndex);
          if (this.currentOperationId === opId && this.state === 'progressing') {
            this.processQueue();
          }
        }, backoffMs);
        this.retryTimers.set(chunkIndex, timer);
      } else {
        this.inProgressChunks.delete(chunkIndex);
        console.error(`Chunk ${chunkIndex} upload failed after ${MAX_CHUNK_RETRIES} retries`, e);
        this.state = 'error';
        this.error = e as Error;
        this.notify();
      }
    }
  }
}
