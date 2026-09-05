import { getTransferDetails, getAvailableChunks, downloadChunk, getChunkDownloadUrl, ApiError } from '../api';
import { calculateSHA256 } from './crypto';
import type { TransferMetadata, TransferStatus, TransferProgress } from '../types';

// Simple IndexedDB wrapper for local state tracking
const DB_NAME = 'TransferReceiverDB';
const STORE_NAME = 'local_chunks';
const MAX_CHUNK_RETRIES = 3;

export const RECEIVER_ACTIVE_TRANSFER_KEY = 'receiver_active_transfer_id';
export const RECEIVER_TRANSFER_PREFIX = 'receiver_transfer_';

async function initDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function getLocalChunks(transferId: string): Promise<number[]> {
  try {
    const db = await initDB();
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const request = store.get(transferId);
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => resolve([]);
    });
  } catch (e) {
    return []; // Fallback to memory
  }
}

export async function clearLocalChunks(transferId: string): Promise<void> {
  try {
    const db = await initDB();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const req = store.delete(transferId);
      req.onsuccess = () => resolve();
      req.onerror = () => reject(req.error);
    });
  } catch (e) {}
}

async function saveLocalChunk(transferId: string, chunkIndex: number) {
  try {
    const db = await initDB();
    const current = await getLocalChunks(transferId);
    if (!current.includes(chunkIndex)) {
      current.push(chunkIndex);
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        const req = store.put(current, transferId);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    }
  } catch (e) {
    console.error('Failed to save to IndexedDB', e);
  }
}

export class DownloadManager {
  private transferId: string;
  private token: string = '';
  private state: TransferStatus = 'idle';
  private transferDetails: TransferMetadata | null = null;
  private downloadedChunks = new Set<number>();
  private inProgressChunks = new Set<number>();
  private chunkRetryCounts = new Map<number, number>();
  private retryTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private availableChunks: number[] = [];
  private activeDownloads = 0;
  private maxConcurrency = 1; // Start sequentially as per rules
  private currentOperationId = 0;
  private onProgressCb?: (progress: TransferProgress) => void;
  private error: Error | undefined;
  private fileHandle: any = null;
  private writable: any = null;
  private writeLock: Promise<void> = Promise.resolve();
  private cancelSource: AbortController | null = null;

  constructor(transferId: string, maxConcurrency: number = 1) {
    this.transferId = transferId;
    this.maxConcurrency = maxConcurrency;
  }

  onProgress(cb: (progress: TransferProgress) => void) {
    this.onProgressCb = cb;
  }

  private clearRetryTimers() {
    this.retryTimers.forEach(timer => clearTimeout(timer));
    this.retryTimers.clear();
  }

  private notify() {
    if (this.onProgressCb && this.transferDetails) {
      const totalBytes = this.transferDetails.fileSize;
      const totalChunks = this.transferDetails.totalChunks;
      const chunkSize = this.transferDetails.chunkSize;
      
      let downloadedBytes = 0;
      if (this.downloadedChunks.size === totalChunks) {
        downloadedBytes = totalBytes;
      } else {
        for (const idx of this.downloadedChunks) {
          if (idx === totalChunks - 1) {
            const remainder = totalBytes % chunkSize;
            downloadedBytes += remainder === 0 ? chunkSize : remainder;
          } else {
            downloadedBytes += chunkSize;
          }
        }
      }
      const boundedBytes = Math.min(downloadedBytes, totalBytes);
      
      let progressPercent = 0;
      if (totalBytes === 0 || this.state === 'completed' || this.downloadedChunks.size >= totalChunks) {
        progressPercent = 100;
      } else {
        const calculated = Math.floor((boundedBytes / totalBytes) * 100);
        progressPercent = Math.min(99, Math.max(0, calculated));
      }

      this.onProgressCb({
        status: this.state,
        progress: progressPercent,
        transferredBytes: this.state === 'completed' ? totalBytes : boundedBytes,
        totalBytes,
        metadata: this.transferDetails,
        error: this.error
      });
    }
  }

  async start(transferId?: string, token?: string) {
    if (this.state === 'progressing' || this.state === 'starting') return;
    if (transferId) this.transferId = transferId;
    if (token) this.token = token;
    
    this.clearRetryTimers();
    const opId = ++this.currentOperationId;
    this.state = 'starting';
    this.error = undefined;
    this.chunkRetryCounts.clear();
    
    try {
      const details = await getTransferDetails(this.transferId, this.token);
      if (this.currentOperationId !== opId || this.state !== 'starting') return;
      
      this.transferDetails = details;
      this.notify();

      if ('showSaveFilePicker' in window) {
        const ext = this.transferDetails.fileName.includes('.')
          ? '.' + this.transferDetails.fileName.split('.').pop()
          : '';
        const pickerOptions: any = {
          suggestedName: this.transferDetails.fileName
        };
        if (this.transferDetails.contentType && this.transferDetails.contentType !== 'application/octet-stream') {
          pickerOptions.types = [{
            description: this.transferDetails.fileName,
            accept: {
              [this.transferDetails.contentType]: ext ? [ext] : []
            }
          }];
        }
        const handle = await (window as any).showSaveFilePicker(pickerOptions);
        if (this.currentOperationId !== opId || this.state !== 'starting') return;
        
        this.fileHandle = handle;
        this.writable = await this.fileHandle.createWritable();
        if (this.currentOperationId !== opId || this.state !== 'starting') {
          if (this.writable) {
            await this.writable.abort().catch(() => {});
            this.writable = null;
          }
          return;
        }
      } else {
        throw new Error('File System Access API is required for large file downloads in this browser. Please use Chrome or Edge.');
      }
      
      // Reconcile local state
      const local = await getLocalChunks(this.transferId);
      if (this.currentOperationId !== opId || this.state !== 'starting') return;
      this.downloadedChunks = new Set(local);

      localStorage.setItem(RECEIVER_ACTIVE_TRANSFER_KEY, this.transferId);
      localStorage.setItem(RECEIVER_TRANSFER_PREFIX + this.transferId, JSON.stringify({
        transferId: this.transferId,
        token: this.token,
        fileName: this.transferDetails.fileName,
        fileSize: this.transferDetails.fileSize,
        chunkSize: this.transferDetails.chunkSize,
        totalChunks: this.transferDetails.totalChunks,
      }));

      this.state = 'progressing';
      this.cancelSource = new AbortController();
      this.notify();
      
      if (this.downloadedChunks.size === this.transferDetails.totalChunks) {
        this.processQueue();
      } else {
        this.startPolling();
      }
    } catch (e) {
      if ((e as any).name === 'AbortError') {
        this.state = 'idle';
        this.notify();
        return;
      }
      if (this.currentOperationId === opId && this.state === 'starting') {
        this.state = 'error';
        this.error = e as Error;
        this.notify();
      }
    }
  }

  pause() {
    if (this.state === 'progressing' || this.state === 'waiting' || this.state === 'starting') {
      this.state = 'paused';
      this.currentOperationId++;
      this.clearRetryTimers();
      this.cancelSource?.abort();
      this.stopPolling();
      this.inProgressChunks.clear();
      this.notify();
    }
  }

  async resume() {
    if (this.state === 'paused' || this.state === 'error') {
      this.clearRetryTimers();
      this.currentOperationId++;
      this.state = 'progressing';
      this.error = undefined;
      this.chunkRetryCounts.clear();
      this.cancelSource = new AbortController();
      this.notify();
      this.startPolling();
    }
  }

  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private currentPollInterval = 3000;
  private readonly MAX_POLL_INTERVAL = 15000;

  private startPolling() {
    this.currentPollInterval = 3000;
    this.scheduleNextPoll(0); // Poll immediately
  }

  private stopPolling() {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }
  
  private scheduleNextPoll(delayMs: number) {
    this.stopPolling();
    this.pollTimer = setTimeout(() => this.pollAvailability(), delayMs);
  }

  private async pollAvailability() {
    if ((this.state !== 'progressing' && this.state !== 'waiting') || !this.transferDetails) return;
    const opId = this.currentOperationId;
    
    try {
      const beforeCount = this.availableChunks.length;
      const chunks = await getAvailableChunks(this.transferId, this.token);
      if (this.currentOperationId !== opId || (this.state !== 'progressing' && this.state !== 'waiting')) return;
      
      this.availableChunks = chunks;
      
      // Calculate backoff
      if (this.availableChunks.length > beforeCount) {
        this.currentPollInterval = 3000; // Reset backoff when new data arrives
      } else {
        this.currentPollInterval = Math.min(this.currentPollInterval * 1.5, this.MAX_POLL_INTERVAL);
      }
      
      if (this.downloadedChunks.size === this.transferDetails.totalChunks) {
        this.processQueue();
        return;
      }
      
      if (this.availableChunks.length === this.downloadedChunks.size && this.downloadedChunks.size < this.transferDetails.totalChunks) {
         if (this.state !== 'waiting') {
             this.state = 'waiting';
             this.notify();
         }
      } else if (this.availableChunks.length > this.downloadedChunks.size) {
         if (this.state === 'waiting') {
             this.state = 'progressing';
             this.notify();
         }
         this.processQueue();
      }
    } catch (e) {
      if (this.currentOperationId !== opId) return;
      if (e instanceof ApiError && (e.code === 'TRANSFER_EXPIRED' || e.code === 'TRANSFER_NOT_FOUND')) {
        this.state = 'error';
        this.error = e;
        this.stopPolling();
        this.notify();
        return;
      }
      console.error('Failed to poll availability', e);
      this.currentPollInterval = Math.min(this.currentPollInterval * 2, this.MAX_POLL_INTERVAL);
    }
    
    // Schedule next iteration if still active
    if (this.state === 'progressing' || this.state === 'waiting') {
      this.scheduleNextPoll(this.currentPollInterval);
    }
  }

  private async writeChunkToDisk(position: number, data: Blob): Promise<void> {
    const currentWrite = this.writeLock.catch(() => {}).then(async () => {
      if (!this.writable) {
        throw new Error('Writable stream is not available');
      }
      await this.writable.write({ type: 'write', position, data });
    });
    this.writeLock = currentWrite;
    await currentWrite;
  }

  private cleanupLocalStorage() {
    localStorage.removeItem(RECEIVER_ACTIVE_TRANSFER_KEY);
    localStorage.removeItem(RECEIVER_TRANSFER_PREFIX + this.transferId);
    clearLocalChunks(this.transferId);
  }

  private async processQueue() {
    if ((this.state !== 'progressing' && this.state !== 'waiting') || !this.transferDetails) return;

    if (this.downloadedChunks.size === this.transferDetails.totalChunks) {
      if (this.activeDownloads > 0) return;
      this.stopPolling();
      
      try {
        if (this.writable) {
          await this.writeLock;
          await this.writable.close();
          this.writable = null;
        }
        this.cleanupLocalStorage();
        this.state = 'completed';
        this.notify();
      } catch (err) {
        console.error('Failed to finalize downloaded file stream', err);
        this.state = 'error';
        this.error = err instanceof Error ? err : new Error('Failed to finalize downloaded file on disk');
        this.notify();
      }
      return;
    }

    while (this.activeDownloads < this.maxConcurrency && this.state === 'progressing') {
      const nextChunkIndex = this.getNextChunkIndex();
      if (nextChunkIndex === -1) break;

      this.activeDownloads++;
      this.downloadChunkWrapper(nextChunkIndex).finally(() => {
        this.activeDownloads--;
        this.processQueue();
      });
    }
  }

  private getNextChunkIndex(): number {
    for (const chunkIndex of this.availableChunks) {
      if (!this.downloadedChunks.has(chunkIndex) && !this.inProgressChunks.has(chunkIndex) && !this.retryTimers.has(chunkIndex)) {
        this.inProgressChunks.add(chunkIndex);
        return chunkIndex;
      }
    }
    return -1;
  }

  private async downloadChunkWrapper(chunkIndex: number) {
    if (!this.transferDetails || !this.writable) return;
    const opId = this.currentOperationId;
    
    try {
      let blob: Blob;
      let checksum: string | null = null;

      try {
        const urlResp = await getChunkDownloadUrl(this.transferId, chunkIndex, this.token);
        const directResp = await fetch(urlResp.downloadUrl);
        if (!directResp.ok) {
          throw new Error(`Direct B2 download failed with status ${directResp.status}`);
        }
        blob = await directResp.blob();
        checksum = urlResp.checksum;
      } catch (directErr) {
        if (directErr instanceof ApiError && (directErr.status === 400 || directErr.status === 500) && directErr.message.includes('Direct presigned download URLs are only supported with B2 storage')) {
          const res = await downloadChunk(this.transferId, chunkIndex, this.token);
          blob = res.blob;
          checksum = res.checksum;
        } else {
          throw directErr;
        }
      }
      
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }

      if (checksum) {
        const calculated = await calculateSHA256(blob);
        let normalizedExpected = checksum.trim();
        if (normalizedExpected.toLowerCase().startsWith('sha256:')) {
          normalizedExpected = normalizedExpected.substring(7).trim();
        } else if (normalizedExpected.toLowerCase().startsWith('sha-256:')) {
          normalizedExpected = normalizedExpected.substring(9).trim();
        }
        if (calculated.toLowerCase() !== normalizedExpected.toLowerCase()) {
          throw new Error(`Checksum mismatch for chunk ${chunkIndex}`);
        }
      }

      const offset = chunkIndex * this.transferDetails.chunkSize;
      
      // Sequentially serialized write to disk stream at calculated offset
      await this.writeChunkToDisk(offset, blob);
      
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }
      
      // Persist local state AFTER successful disk/storage write
      await saveLocalChunk(this.transferId, chunkIndex);
      
      this.inProgressChunks.delete(chunkIndex);
      this.chunkRetryCounts.delete(chunkIndex);
      this.downloadedChunks.add(chunkIndex);
      if (this.downloadedChunks.size < this.transferDetails.totalChunks) {
        this.notify();
      }
      
    } catch (e) {
      if (this.currentOperationId !== opId || this.state !== 'progressing') {
        this.inProgressChunks.delete(chunkIndex);
        return;
      }

      if (e instanceof ApiError) {
        if (e.code === 'CHUNK_NOT_AVAILABLE') {
          // Expected in progressive download if chunk not yet uploaded by sender
          this.inProgressChunks.delete(chunkIndex);
          if (this.state === 'progressing') {
            this.state = 'waiting';
            this.notify();
          }
          return;
        }
        if (e.code === 'TRANSFER_EXPIRED' || e.code === 'TRANSFER_NOT_FOUND' || e.code === 'FORBIDDEN') {
          this.inProgressChunks.delete(chunkIndex);
          this.cleanupLocalStorage();
          this.state = 'error';
          this.error = e;
          this.stopPolling();
          this.notify();
          return;
        }
      }

      // Recoverable error: retry chunk with exponential backoff
      const retries = (this.chunkRetryCounts.get(chunkIndex) || 0) + 1;
      this.chunkRetryCounts.set(chunkIndex, retries);

      if (retries <= MAX_CHUNK_RETRIES) {
        const backoffMs = Math.min(500 * Math.pow(2, retries - 1), 3000);
        console.warn(`Chunk ${chunkIndex} download failed (attempt ${retries}/${MAX_CHUNK_RETRIES}), retrying in ${backoffMs}ms...`, e);
        const timer = setTimeout(() => {
          this.retryTimers.delete(chunkIndex);
          this.inProgressChunks.delete(chunkIndex);
          if (this.currentOperationId === opId && (this.state === 'progressing' || this.state === 'waiting')) {
            this.processQueue();
          }
        }, backoffMs);
        this.retryTimers.set(chunkIndex, timer);
      } else {
        this.inProgressChunks.delete(chunkIndex);
        console.error(`Chunk ${chunkIndex} download failed after ${MAX_CHUNK_RETRIES} retries`, e);
        this.state = 'error';
        this.error = e as Error;
        this.stopPolling();
        this.notify();
      }
    }
  }
}
