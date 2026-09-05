import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { DownloadManager } from './downloadManager';
import { ApiError } from '../api';

const mockGetTransferDetails = vi.fn();
const mockGetAvailableChunks = vi.fn();
const mockDownloadChunk = vi.fn();
const mockGetChunkDownloadUrl = vi.fn();

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    getTransferDetails: (...args: any[]) => mockGetTransferDetails(...args),
    getAvailableChunks: (...args: any[]) => mockGetAvailableChunks(...args),
    downloadChunk: (...args: any[]) => mockDownloadChunk(...args),
    getChunkDownloadUrl: (...args: any[]) => mockGetChunkDownloadUrl(...args),
  };
});

vi.mock('./crypto', () => ({
  calculateSHA256: async (_blob: Blob) => {
    return 'mock-sha256';
  }
}));

describe('DownloadManager State Machine', () => {
  let manager: DownloadManager;
  let mockWritable: any;
  let idbStore: Map<any, any>;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();

    idbStore = new Map();

    const mockDB: any = {
      objectStoreNames: { contains: () => true },
      createObjectStore: vi.fn(),
      transaction: vi.fn().mockImplementation(() => ({
        objectStore: vi.fn().mockImplementation(() => ({
          put: vi.fn().mockImplementation((val, key) => {
            idbStore.set(key, val);
            const req: any = {};
            Promise.resolve().then(() => req.onsuccess?.({ target: req }));
            return req;
          }),
          get: vi.fn().mockImplementation((key) => {
            const req: any = { result: idbStore.get(key) };
            Promise.resolve().then(() => req.onsuccess?.({ target: req }));
            return req;
          }),
          getAllKeys: vi.fn().mockImplementation(() => {
            const req: any = { result: Array.from(idbStore.keys()) };
            Promise.resolve().then(() => req.onsuccess?.({ target: req }));
            return req;
          }),
          clear: vi.fn().mockImplementation(() => {
            idbStore.clear();
            const req: any = {};
            Promise.resolve().then(() => req.onsuccess?.({ target: req }));
            return req;
          })
        }))
      }))
    };

    (globalThis as any).indexedDB = {
      open: vi.fn().mockImplementation(() => {
        const req: any = { result: mockDB };
        Promise.resolve().then(() => req.onsuccess?.({ target: req }));
        return req;
      }),
      deleteDatabase: vi.fn().mockImplementation(() => {
        idbStore.clear();
        return {};
      })
    };

    mockWritable = {
      write: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined)
    };

    (globalThis as any).window = {
      showSaveFilePicker: vi.fn().mockResolvedValue({
        createWritable: vi.fn().mockResolvedValue(mockWritable)
      })
    };

    (globalThis as any).fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob(['chunk data'])
    });

    mockGetChunkDownloadUrl.mockResolvedValue({
      chunkIndex: 0,
      size: 50,
      checksum: 'mock-sha256',
      downloadUrl: 'https://s3.example.com/get-chunk',
      expiresAt: '2099-01-01T00:00:00Z'
    });

    manager = new DownloadManager('test-id', 1);
  });

  afterEach(() => {
    manager.pause();
    vi.useRealTimers();
  });

  it('starts successfully and processes available chunks sequentially', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 50,
      totalChunks: 2
    });

    mockGetAvailableChunks.mockResolvedValue([0]);

    const progressLogs: string[] = [];
    manager.onProgress((p) => {
      progressLogs.push(p.status);
    });

    await manager.start('test-id', 'token123');
    await vi.advanceTimersByTimeAsync(100);

    expect(mockGetTransferDetails).toHaveBeenCalledWith('test-id', 'token123');
    expect(mockGetAvailableChunks).toHaveBeenCalledWith('test-id', 'token123');
    expect(mockGetChunkDownloadUrl).toHaveBeenCalledWith('test-id', 0, 'token123');
    expect(mockWritable.write).toHaveBeenCalledWith({ type: 'write', position: 0, data: expect.any(Blob) });

    // Server now says chunk 0 and 1 are available
    mockGetAvailableChunks.mockResolvedValue([0, 1]);
    mockGetChunkDownloadUrl.mockResolvedValue({
      chunkIndex: 1,
      size: 50,
      checksum: 'mock-sha256',
      downloadUrl: 'https://s3.example.com/get-chunk-1',
      expiresAt: '2099-01-01T00:00:00Z'
    });

    // Fast forward next poll
    await vi.advanceTimersByTimeAsync(3000);

    expect(mockGetChunkDownloadUrl).toHaveBeenCalledWith('test-id', 1, 'token123');
    expect(mockWritable.write).toHaveBeenCalledWith({ type: 'write', position: 50, data: expect.any(Blob) });

    // Transfer completes
    expect(progressLogs).toContain('completed');
  });

  it('pauses and stops scheduling', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 50,
      totalChunks: 2
    });

    mockGetAvailableChunks.mockResolvedValue([0, 1]);

    let resolveDownload: any;
    const downloadPromise = new Promise(r => resolveDownload = r);
    (globalThis as any).fetch = vi.fn().mockReturnValue(downloadPromise);

    let status = '';
    manager.onProgress((p) => status = p.status);

    manager.start('test-id', 'token123');
    await vi.advanceTimersByTimeAsync(100);

    expect(mockGetChunkDownloadUrl).toHaveBeenCalledTimes(1);

    manager.pause();

    // Now resolve chunk 0
    resolveDownload({
      ok: true,
      status: 200,
      blob: async () => new Blob(['chunk0'])
    });
    await vi.advanceTimersByTimeAsync(100);

    // Should NOT schedule chunk 1 because we are paused
    expect(mockGetChunkDownloadUrl).toHaveBeenCalledTimes(1);
    expect(status).toBe('paused');
  });

  it('reconciles local state across restarts', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 50,
      totalChunks: 2
    });

    mockGetAvailableChunks.mockResolvedValue([0]);

    await manager.start('test-id', 'token123');
    await vi.advanceTimersByTimeAsync(100);

    expect(mockGetChunkDownloadUrl).toHaveBeenCalledWith('test-id', 0, 'token123');
    manager.pause();

    // Create a new manager instance simulating page reload
    const manager2 = new DownloadManager('test-id', 1);

    mockGetAvailableChunks.mockResolvedValue([0, 1]); // Server has both
    mockGetChunkDownloadUrl.mockResolvedValue({
      chunkIndex: 1,
      size: 50,
      checksum: 'mock-sha256',
      downloadUrl: 'https://s3.example.com/get-chunk-1',
      expiresAt: '2099-01-01T00:00:00Z'
    });

    // It should skip chunk 0 because it's in IndexedDB
    await manager2.start('test-id', 'token123');
    await vi.advanceTimersByTimeAsync(100);

    // Chunk 1 should be requested, not Chunk 0
    expect(mockGetChunkDownloadUrl).toHaveBeenCalledWith('test-id', 1, 'token123');
    expect(mockGetChunkDownloadUrl).toHaveBeenCalledTimes(2);

    manager2.pause();
  });

  it('fails on checksum mismatch and does not write to disk', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 100,
      totalChunks: 1
    });

    mockGetAvailableChunks.mockResolvedValue([0]);
    mockGetChunkDownloadUrl.mockResolvedValue({
      chunkIndex: 0,
      size: 100,
      checksum: 'different-expected-checksum', // Mismatch with mock-sha256
      downloadUrl: 'https://s3.example.com/get-chunk',
      expiresAt: '2099-01-01T00:00:00Z'
    });

    let error: any;
    manager.onProgress((p) => error = p.error);

    await manager.start('test-id');
    await vi.runAllTimersAsync();

    expect(mockWritable.write).not.toHaveBeenCalled();
    expect(error?.message ?? '').toContain('Checksum mismatch');
  });

  it('stops polling and completes locally when all chunks are downloaded, even if server is UPLOADING (Abandoned Sender)', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 50,
      totalChunks: 2,
      status: 'UPLOADING'
    });

    mockGetAvailableChunks.mockResolvedValue([0, 1]);

    let status = '';
    manager.onProgress((p) => status = p.status);

    await manager.start('test-id');
    await vi.runAllTimersAsync();

    expect(status).toBe('completed');

    // Fast forward to ensure no more polling happens
    mockGetAvailableChunks.mockClear();
    await vi.advanceTimersByTimeAsync(15000);
    expect(mockGetAvailableChunks).not.toHaveBeenCalled();
  });

  it('handles TRANSFER_EXPIRED cleanly during polling backstop', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 100,
      totalChunks: 1,
      status: 'UPLOADING'
    });

    let state = '';
    let errorMsg = '';
    manager.onProgress((p) => {
      state = p.status;
      if (p.error) errorMsg = p.error.message;
    });

    mockGetAvailableChunks.mockResolvedValue([]);
    await manager.start('test-id');

    mockGetAvailableChunks.mockRejectedValue(new ApiError(410, 'TRANSFER_EXPIRED', 'TRANSFER_EXPIRED'));
    await vi.advanceTimersByTimeAsync(3000);

    expect(state).toBe('error');
    expect(errorMsg).toBe('TRANSFER_EXPIRED');

    mockGetAvailableChunks.mockClear();
    await vi.advanceTimersByTimeAsync(15000);
    expect(mockGetAvailableChunks).not.toHaveBeenCalled();
  });

  it('handles connection loss gracefully during chunk download', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 50,
      totalChunks: 2,
    });

    mockGetAvailableChunks.mockResolvedValue([0]);

    // Reject download with network error
    (globalThis as any).fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    let error: any;
    let status = '';
    manager.onProgress((p) => {
      error = p.error;
      status = p.status;
    });

    await manager.start('test-id');
    await vi.runAllTimersAsync();

    expect(mockWritable.write).not.toHaveBeenCalled();
    expect(status).toBe('error');
    expect(error?.message).toContain('Failed to fetch');
  });

  it('resumes properly after connection loss using reconciliation', async () => {
    mockGetTransferDetails.mockResolvedValue({
      transferId: 'test-id',
      fileName: 'test.txt',
      fileSize: 100,
      chunkSize: 50,
      totalChunks: 2,
    });

    mockGetAvailableChunks.mockResolvedValue([0, 1]);

    (globalThis as any).fetch = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        blob: async () => new Blob(['chunk0'])
      })
      .mockRejectedValueOnce(new TypeError('Network Error'));

    let state = '';
    manager.onProgress((p) => state = p.status);

    await manager.start('test-id');
    await vi.runAllTimersAsync();

    expect(mockWritable.write).toHaveBeenCalledTimes(1);
    expect(state).toBe('error');

    // Fix the network for chunk 1
    (globalThis as any).fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob(['chunk1'])
    });

    mockGetChunkDownloadUrl.mockResolvedValue({
      chunkIndex: 1,
      size: 50,
      checksum: 'mock-sha256',
      downloadUrl: 'https://s3.example.com/get-chunk-1',
      expiresAt: '2099-01-01T00:00:00Z'
    });

    // Resume!
    await manager.resume();
    await vi.runAllTimersAsync();

    // Chunk 1 should be written on resume
    expect(mockGetChunkDownloadUrl).toHaveBeenCalledWith('test-id', 1, '');
    expect(mockWritable.write).toHaveBeenCalledTimes(2);

    expect(state).toBe('completed');
  });
});
