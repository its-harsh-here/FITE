import React, { useState, useEffect } from 'react';
import { DownloadManager, getLocalChunks, clearLocalChunks, RECEIVER_ACTIVE_TRANSFER_KEY, RECEIVER_TRANSFER_PREFIX } from '../lib/downloadManager';
import { getTransferDetails, getTransferByCode, getAvailableChunks, ApiError } from '../api';
import type { TransferProgress, TransferMetadata } from '../types';

export const ReceivePage: React.FC<{ onHome: () => void }> = ({ onHome }) => {
  const [manager] = useState(() => new DownloadManager('temp', 3));
  const [progress, setProgress] = useState<TransferProgress | null>(null);
  
  // State for manual lookup or direct link preview before download starts
  const [inputCode, setInputCode] = useState('');
  const [transferId, setTransferId] = useState('');
  const [token, setToken] = useState('');
  const [metadata, setMetadata] = useState<TransferMetadata | null>(null);
  const [availableChunksCount, setAvailableChunksCount] = useState<number | null>(null);
  const [loadingMetadata, setLoadingMetadata] = useState(false);
  const [lookupError, setLookupError] = useState<string | null>(null);
  
  // State for refresh recovery
  const [resumableTransfer, setResumableTransfer] = useState<{
    transferId: string;
    token: string;
    fileName: string;
    fileSize: number;
    downloadedBytes: number;
  } | null>(null);

  useEffect(() => {
    manager.onProgress((p) => {
      setProgress({ ...p });
    });
  }, [manager]);

  const parseTransferCodeOrUrl = (raw: string) => {
    let text = raw.trim();
    if (!text) return { parsedId: '', parsedToken: '' };

    let parsedToken = '';
    if (text.includes('?')) {
      const qIndex = text.indexOf('?');
      const queryString = text.substring(qIndex + 1);
      text = text.substring(0, qIndex);
      const searchParams = new URLSearchParams(queryString);
      parsedToken = searchParams.get('token') || '';
    }

    if (text.includes('/')) {
      const parts = text.split('/').filter(Boolean);
      text = parts[parts.length - 1] || '';
    }

    return { parsedId: text.trim(), parsedToken: parsedToken.trim() };
  };

  const fetchTransferInfo = async (id: string, tok: string) => {
    setLoadingMetadata(true);
    setLookupError(null);
    try {
      const cleanToken = tok.trim();
      const details = await getTransferDetails(id, cleanToken || undefined);
      const available = await getAvailableChunks(id, cleanToken || undefined);
      const effectiveToken = cleanToken || details.shareToken || '';
      setTransferId(id);
      setToken(effectiveToken);
      setMetadata(details);
      setAvailableChunksCount(available.length);
      return details;
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'TRANSFER_EXPIRED') {
          setLookupError('This transfer has expired.');
        } else if (err.code === 'FORBIDDEN') {
          setLookupError('Invalid share token.');
        } else {
          setLookupError('Transfer not found. Please check your code.');
        }
      } else {
        setLookupError('Failed to fetch transfer. Please check your connection.');
      }
      return null;
    } finally {
      setLoadingMetadata(false);
    }
  };

  // Check URL on mount for direct share link or localStorage for resumable download
  useEffect(() => {
    const pathname = window.location.pathname;
    const params = new URLSearchParams(window.location.search);
    const urlToken = (params.get('token') || '').trim();

    let urlId = '';
    if (pathname.startsWith('/transfer/')) {
      urlId = pathname.replace('/transfer/', '').split('/')[0];
    } else if (pathname.startsWith('/download/')) {
      urlId = pathname.replace('/download/', '').split('/')[0];
    } else if (pathname.startsWith('/receive/')) {
      urlId = pathname.replace('/receive/', '').split('/')[0];
    }
    urlId = urlId.trim();

    const activeId = (localStorage.getItem(RECEIVER_ACTIVE_TRANSFER_KEY) || '').trim();
    const candidateId = urlId || activeId;

    if (candidateId) {
      let savedToken = urlToken;
      const raw = localStorage.getItem(RECEIVER_TRANSFER_PREFIX + candidateId);
      if (raw) {
        try {
          const parsed = JSON.parse(raw);
          savedToken = savedToken || parsed.token || '';
        } catch {}
      }

      getLocalChunks(candidateId).then(async (localChunks) => {
        if (localChunks.length > 0) {
          try {
            const details = await getTransferDetails(candidateId, savedToken || undefined);
            if (details.status !== 'EXPIRED' && details.status !== 'FAILED') {
              if (localChunks.length < details.totalChunks) {
                const downloaded = Math.min(localChunks.length * details.chunkSize, details.fileSize);
                setResumableTransfer({
                  transferId: candidateId,
                  token: savedToken || details.shareToken || '',
                  fileName: details.fileName,
                  fileSize: details.fileSize,
                  downloadedBytes: downloaded
                });
                return;
              }
            }
          } catch {}
        }

        // If no active incomplete recovery transfer, handle direct link view
        if (urlId) {
          fetchTransferInfo(urlId, urlToken);
        }
      });
    }
  }, []);

  const handleManualFetch = async (e: React.FormEvent) => {
    e.preventDefault();
    const raw = inputCode.trim();
    if (!raw) return;

    setLoadingMetadata(true);
    setLookupError(null);

    const { parsedId, parsedToken } = parseTransferCodeOrUrl(raw);

    // If direct link, relative path, or has token/tf_ prefix
    if (parsedToken || parsedId.startsWith('tf_') || raw.includes('/') || raw.includes('?')) {
      await fetchTransferInfo(parsedId, parsedToken);
      setLoadingMetadata(false);
      return;
    }

    // Otherwise, treat as short human-readable transferCode (e.g. ABC7K9)
    try {
      const details = await getTransferByCode(raw);
      const available = await getAvailableChunks(details.transferId, details.shareToken);
      setTransferId(details.transferId);
      setToken(details.shareToken);
      setMetadata(details);
      setAvailableChunksCount(available.length);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'TRANSFER_EXPIRED') {
          setLookupError('This transfer has expired.');
        } else {
          setLookupError('Transfer not found. Please check your code.');
        }
      } else {
        setLookupError('Failed to fetch transfer. Please check your connection.');
      }
    } finally {
      setLoadingMetadata(false);
    }
  };

  const handleStartDownload = () => {
    if (!transferId) return;
    setResumableTransfer(null);
    manager.start(transferId, token);
  };

  const handleResumeReceive = () => {
    if (!resumableTransfer) return;
    const id = resumableTransfer.transferId;
    const tok = resumableTransfer.token;
    setResumableTransfer(null);
    manager.start(id, tok);
  };

  const handleDismissResumable = () => {
    if (resumableTransfer) {
      localStorage.removeItem(RECEIVER_ACTIVE_TRANSFER_KEY);
      localStorage.removeItem(RECEIVER_TRANSFER_PREFIX + resumableTransfer.transferId);
      clearLocalChunks(resumableTransfer.transferId);
      setResumableTransfer(null);
    }
  };

  const formatBytes = (bytes: number, decimals = 2) => {
    if (!+bytes) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(decimals))} ${sizes[i]}`;
  };

  return (
    <div className="flex-col" style={{ flex: 1, alignItems: 'center', maxWidth: '580px', margin: '0 auto', width: '100%' }}>
      <div style={{ width: '100%', marginBottom: '20px' }}>
        <button className="btn secondary btn-sm" onClick={onHome}>
          ← Back
        </button>
      </div>

      <div className="panel" style={{ width: '100%' }}>
        {!progress || progress.status === 'idle' ? (
          <div>
            {/* Receiver Refresh Recovery Card */}
            {resumableTransfer ? (
              <div className="flex-col" style={{ gap: '16px' }}>
                <div>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--accent)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '6px' }}>
                    Continue Download
                  </div>
                  <h3 style={{ fontSize: '18px', margin: '0 0 6px 0', wordBreak: 'break-word' }}>
                    {resumableTransfer.fileName}
                  </h3>
                  <div className="text-mono" style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                    Saved download progress: {formatBytes(resumableTransfer.downloadedBytes)} / {formatBytes(resumableTransfer.fileSize)}
                  </div>
                </div>

                <p style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)', margin: 0 }}>
                  Your download progress has been saved.
                </p>

                <div style={{ display: 'flex', gap: '10px', paddingTop: '8px' }}>
                  <button className="btn" onClick={handleResumeReceive}>
                    Resume Receive
                  </button>
                  <button className="btn secondary" onClick={handleDismissResumable}>
                    Start New
                  </button>
                </div>
              </div>
            ) : metadata ? (
              /* Direct Share Link / Preview Card Ready to Download */
              <div className="flex-col" style={{ gap: '20px' }}>
                <h2 style={{ fontSize: '20px', margin: 0, textAlign: 'center' }}>Receive File</h2>
                
                <div style={{ 
                  padding: '20px', 
                  background: 'rgba(0, 0, 0, 0.3)', 
                  border: '1px solid var(--border-subtle)', 
                  borderRadius: '10px' 
                }}>
                  <div style={{ fontSize: '17px', fontWeight: 600, marginBottom: '4px', wordBreak: 'break-word' }}>
                    {metadata.fileName}
                  </div>
                  <div className="text-mono" style={{ fontSize: '13px', color: 'var(--text-secondary)', marginBottom: '14px' }}>
                    {formatBytes(metadata.fileSize)}
                  </div>
                  
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: 'var(--accent)' }}>
                    <span style={{ fontSize: '10px' }}>●</span>
                    <span>
                      {availableChunksCount === metadata.totalChunks 
                        ? 'Ready to download' 
                        : availableChunksCount && availableChunksCount > 0
                        ? `${Math.round((availableChunksCount / metadata.totalChunks) * 100)}% currently available`
                        : 'Waiting for sender to upload data...'}
                    </span>
                  </div>
                </div>

                <button 
                  className="btn btn-lg" 
                  style={{ width: '100%' }}
                  onClick={handleStartDownload}
                >
                  Start Download
                </button>
              </div>
            ) : (
              /* Path B: Manual Transfer Code Entry */
              <form onSubmit={handleManualFetch} className="flex-col" style={{ gap: '20px' }}>
                <div style={{ textAlign: 'center' }}>
                  <h2 style={{ fontSize: '20px', margin: '0 0 6px 0' }}>Receive File</h2>
                  <p style={{ color: 'var(--text-secondary)', margin: 0, fontSize: '13px' }}>
                    Enter transfer code or paste share link
                  </p>
                </div>
                
                <input 
                  className="input" 
                  placeholder="e.g. ABC7K9 or https://..." 
                  value={inputCode} 
                  onChange={e => setInputCode(e.target.value)}
                  autoFocus
                />

                {lookupError && (
                  <div style={{ padding: '12px 14px', background: 'var(--error-bg)', border: '1px solid var(--error-border)', color: 'var(--error)', borderRadius: '8px', fontSize: '13px' }}>
                    {lookupError}
                  </div>
                )}
                
                <button className="btn" type="submit" disabled={loadingMetadata} style={{ width: '100%', padding: '12px' }}>
                  {loadingMetadata ? 'Fetching...' : 'Fetch Transfer'}
                </button>
              </form>
            )}
          </div>
        ) : progress.status === 'completed' ? (
          /* Completed State Presentation */
          <div className="flex-col flex-center" style={{ gap: '16px', textAlign: 'center', padding: '16px 0' }}>
            <div style={{ 
              width: '48px', 
              height: '48px', 
              borderRadius: '50%', 
              background: 'var(--success-bg)', 
              border: '1px solid var(--success-border)',
              display: 'flex', 
              alignItems: 'center', 
              justifyContent: 'center',
              fontSize: '22px', 
              color: 'var(--success)' 
            }}>
              ✓
            </div>
            <div>
              <h2 style={{ fontSize: '20px', margin: '0 0 6px 0' }}>Download complete</h2>
              <div style={{ fontSize: '16px', fontWeight: 600, wordBreak: 'break-word', marginBottom: '4px' }}>
                {progress.metadata?.fileName || metadata?.fileName}
              </div>
              <div className="text-mono" style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                {formatBytes(progress.totalBytes)}
              </div>
            </div>
          </div>
        ) : (
          /* Progressive Download State Presentation (Downloading / Paused / Waiting / Error) */
          <div className="flex-col" style={{ gap: '20px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '16px' }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <h3 style={{ fontSize: '17px', margin: '0 0 4px 0', wordBreak: 'break-word' }}>
                  {progress.metadata?.fileName || metadata?.fileName || 'Downloading...'}
                </h3>
                <div className="text-mono" style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                  {progress.status === 'paused' ? 'Download paused' : progress.status === 'waiting' ? 'Waiting for sender' : 'Downloading'}
                  {progress.totalBytes ? ` • ${formatBytes(progress.transferredBytes)} / ${formatBytes(progress.totalBytes)}` : ''}
                </div>
              </div>

              <span className={`status-badge ${progress.status === 'error' ? 'error' : progress.status === 'paused' ? 'paused' : progress.status === 'waiting' ? 'warning' : ''}`}>
                {progress.status}
              </span>
            </div>

            {/* Progress Bar */}
            <div className="progress-bar">
              <div 
                className={`progress-fill ${progress.status === 'error' ? 'error' : progress.status === 'waiting' ? 'warning' : ''}`}
                style={{ width: `${progress.progress}%` }}
              />
            </div>

            {/* Progress Percentage & Controls */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div className="text-mono" style={{ fontSize: '18px', fontWeight: 600, color: 'var(--accent)' }}>
                {progress.progress}%
              </div>
              <div>
                {(progress.status === 'progressing' || progress.status === 'starting' || progress.status === 'waiting') && (
                  <button className="btn secondary btn-sm" onClick={() => manager.pause()}>
                    Pause
                  </button>
                )}
                {progress.status === 'paused' && (
                  <button className="btn btn-sm" onClick={() => manager.resume()}>
                    Resume
                  </button>
                )}
              </div>
            </div>

            {progress.status === 'waiting' && (
              <div style={{ padding: '10px 14px', background: 'var(--warning-bg)', border: '1px solid var(--warning-border)', color: 'var(--warning)', borderRadius: '8px', fontSize: '13px' }}>
                Waiting for sender to upload more data...
              </div>
            )}

            {progress.status === 'error' && (
              <div style={{ padding: '14px', background: 'var(--error-bg)', border: '1px solid var(--error-border)', color: 'var(--error)', borderRadius: '8px', fontSize: '13px' }}>
                <div style={{ marginBottom: '10px' }}>
                  {progress.error?.message || 'A download error occurred.'}
                </div>
                <button className="btn btn-sm" onClick={() => manager.resume()}>
                  Retry
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
