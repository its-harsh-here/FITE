import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { UploadManager } from './uploadManager';

const mockCreateTransfer = vi.fn();
const mockGetAvailableChunks = vi.fn();
const mockUploadChunk = vi.fn();
const mockCompleteTransfer = vi.fn();

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    createTransfer: (...args: any[]) => mockCreateTransfer(...args),
    getAvailableChunks: (...args: any[]) => mockGetAvailableChunks(...args),
    uploadChunk: (...args: any[]) => mockUploadChunk(...args),
    completeTransfer: (...args: any[]) => mockCompleteTransfer(...args),
  };
});

vi.mock('./crypto', () => ({
  calculateSHA256: async (_blob: Blob) => 'mock-sha256'
}));

describe('UploadManager State Machine', () => {
  let manager: UploadManager;
  let mockFile: File;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();

    mockFile = new File(['chunk0', 'chunk1'], 'test.txt', { type: 'text/plain' });
    // mock File.slice to just return portions for the sake of the test tracking
    mockFile.slice = vi.fn().mockImplementation((_start, _end) => new Blob(['mocked blob content']));

    manager = new UploadManager(1);
  });

  afterEach(() => {
    manager.pause();
    vi.useRealTimers();
  });

  it('pauses and stops scheduling', async () => {
    mockCreateTransfer.mockResolvedValue({
      transferId: 'test-id',
      shareToken: 'token',
      expiresAt: '2099-01-01T00:00:00Z',
      fileName: 'test.txt',
      contentType: 'text/plain',
      fileSize: 12,
      chunkSize: 6, // file size is 12 -> 2 chunks
      totalChunks: 2,
    });
    mockGetAvailableChunks.mockResolvedValue([]);

    // Defer the upload response so we can pause in the middle
    let resolveUpload: any;
    const uploadPromise = new Promise(r => resolveUpload = r);
    mockUploadChunk.mockReturnValue(uploadPromise);

    await manager.start(mockFile);

    // Chunk 0 should be requested
    expect(mockUploadChunk).toHaveBeenCalledTimes(1);

    let status = '';
    manager.onProgress((p) => status = p.status);

    manager.pause();

    // Now resolve chunk 0
    resolveUpload(undefined);
    await vi.runAllTimersAsync();

    // Should NOT schedule chunk 1 because we are paused
    expect(mockUploadChunk).toHaveBeenCalledTimes(1);
    expect(status).toBe('paused');
  });

  it('handles connection loss gracefully', async () => {
    mockCreateTransfer.mockResolvedValue({
      transferId: 'test-id',
      shareToken: 'token',
      expiresAt: '2099-01-01T00:00:00Z',
      fileName: 'test.txt',
      contentType: 'text/plain',
      fileSize: 12,
      chunkSize: 6,
      totalChunks: 2,
    });
    mockGetAvailableChunks.mockResolvedValue([]);

    // Reject the upload with network error
    mockUploadChunk.mockRejectedValue(new TypeError('Failed to fetch'));

    let error: Error | undefined;
    let status = '';
    manager.onProgress((p) => {
      error = p.error;
      status = p.status;
    });

    await manager.start(mockFile);
    await vi.advanceTimersByTimeAsync(10000);

    expect(status).toBe('error');
    expect(error?.message).toContain('Failed to fetch');
    expect(mockUploadChunk).toHaveBeenCalled();
  });

  it('reconciles after lost response (server succeeded but client failed)', async () => {
    const existingTransfer = {
      transferId: 'test-id',
      shareToken: 'token',
      expiresAt: '2099-01-01T00:00:00Z',
      contentType: 'text/plain',
      fileName: 'test.txt',
      fileSize: 12,
      chunkSize: 6,
      totalChunks: 2,
    };

    // Upon start (which acts like resume here because we pass existingTransfer),
    // the server says chunk 0 is already available (even though the client errored previously)
    mockGetAvailableChunks.mockResolvedValue([0]);
    mockUploadChunk.mockResolvedValue(undefined); // next uploads succeed

    let status = '';
    manager.onProgress((p) => status = p.status);

    await manager.start(mockFile, existingTransfer);
    await vi.runAllTimersAsync();

    // Chunk 0 was skipped because of reconcile
    expect(mockUploadChunk).toHaveBeenCalledTimes(1);
    // And it uploaded chunk 1
    expect(mockUploadChunk).toHaveBeenCalledWith('test-id', 1, expect.any(Blob), 'mock-sha256');

    expect(status).toBe('completed');
  });

  it('retries safely and skips persisted chunks automatically', async () => {
    // If somehow a persisted chunk is sent to start(), reconcile catches it
    const existingTransfer = {
      transferId: 'test-id',
      shareToken: 'token',
      expiresAt: '2099-01-01T00:00:00Z',
      contentType: 'text/plain',
      fileName: 'test.txt',
      fileSize: 12,
      chunkSize: 6,
      totalChunks: 2,
    };

    mockGetAvailableChunks.mockResolvedValue([0, 1]); // both are available!

    let status = '';
    manager.onProgress((p) => status = p.status);

    await manager.start(mockFile, existingTransfer);
    await vi.runAllTimersAsync();

    // No uploads should happen
    expect(mockUploadChunk).not.toHaveBeenCalled();
    // And it completes
    expect(status).toBe('completed');
  });
});
