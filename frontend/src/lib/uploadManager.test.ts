import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { UploadManager } from './uploadManager';

const mockCreateTransfer = vi.fn();
const mockGetAvailableChunks = vi.fn();
const mockUploadChunk = vi.fn();
const mockGetChunkUploadUrl = vi.fn();
const mockCommitChunk = vi.fn();
const mockCompleteTransfer = vi.fn();

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    createTransfer: (...args: any[]) => mockCreateTransfer(...args),
    getAvailableChunks: (...args: any[]) => mockGetAvailableChunks(...args),
    uploadChunk: (...args: any[]) => mockUploadChunk(...args),
    getChunkUploadUrl: (...args: any[]) => mockGetChunkUploadUrl(...args),
    commitChunk: (...args: any[]) => mockCommitChunk(...args),
    completeTransfer: (...args: any[]) => mockCompleteTransfer(...args),
  };
});

vi.mock('./crypto', () => ({
  calculateSHA256: async (_blob: Blob) => 'mock-sha256',
  calculateMD5: async (_blob: Blob) => ({ hex: 'mock-md5-hex', base64: 'mock-md5-base64' })
}));

describe('UploadManager State Machine', () => {
  let manager: UploadManager;
  let mockFile: File;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();

    mockFile = new File(['chunk0', 'chunk1'], 'test.txt', { type: 'text/plain' });
    mockFile.slice = vi.fn().mockImplementation((_start, _end) => new Blob(['mocked blob content']));

    // Mock global fetch for direct-to-B2 PUT
    (globalThis as any).fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({})
    });

    mockGetChunkUploadUrl.mockResolvedValue({
      uploadUrl: 'https://s3.example.com/put-chunk',
      storageKey: 'key',
      headers: { 'Content-MD5': 'mock-md5-base64' },
      expiresAt: '2099-01-01T00:00:00Z'
    });
    mockCommitChunk.mockResolvedValue(undefined);

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
    (globalThis as any).fetch = vi.fn().mockReturnValue(uploadPromise);

    await manager.start(mockFile);
    await vi.waitFor(() => expect(mockGetChunkUploadUrl).toHaveBeenCalledTimes(1));

    let status = '';
    manager.onProgress((p) => status = p.status);

    manager.pause();

    // Now resolve chunk 0
    resolveUpload({ ok: true, status: 200 });
    await vi.runAllTimersAsync();

    // Should NOT schedule chunk 1 because we are paused
    expect(mockGetChunkUploadUrl).toHaveBeenCalledTimes(1);
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
    (globalThis as any).fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

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
    expect(mockGetChunkUploadUrl).toHaveBeenCalled();
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

    mockGetAvailableChunks.mockResolvedValue([0]);

    let status = '';
    manager.onProgress((p) => status = p.status);

    await manager.start(mockFile, existingTransfer);
    await vi.runAllTimersAsync();

    // Chunk 0 was skipped because of reconcile
    expect(mockGetChunkUploadUrl).toHaveBeenCalledTimes(1);
    expect(mockGetChunkUploadUrl).toHaveBeenCalledWith('test-id', 1, 'mock-sha256', expect.any(Number), 'mock-md5-base64');
    expect(mockCommitChunk).toHaveBeenCalledWith('test-id', 1, 'mock-sha256', expect.any(Number), 'mock-md5-hex');

    expect(status).toBe('completed');
  });

  it('retries safely and skips persisted chunks automatically', async () => {
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
    expect(mockGetChunkUploadUrl).not.toHaveBeenCalled();
    expect(status).toBe('completed');
  });
});
