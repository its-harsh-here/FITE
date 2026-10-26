import React, { useState, useEffect, useRef } from 'react';
import { UploadManager } from '../lib/uploadManager';
import { getTransferDetails, getAvailableChunks } from '../api';
import type { TransferProgress, TransferMetadata } from '../types';

const SENDER_ACTIVE_TRANSFER_KEY = 'sender_active_transfer_id';
const SENDER_TRANSFER_PREFIX = 'sender_transfer_';

export const SendPage: React.FC<{ onHome: () => void }> = ({ onHome }) => {
  const [manager] = useState(() => new UploadManager(3));
  const [progress, setProgress] = useState<TransferProgress | null>(null);
  const [resumableTransfer, setResumableTransfer] = useState<{ metadata: TransferMetadata; uploadedBytes: number } | null>(null);
  const [copiedLink, setCopiedLink] = useState(false);
  const [copiedCode, setCopiedCode] = useState(false);
  const [isDragOver, setIsDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    manager.onProgress((p) => {
      setProgress({ ...p });
    });
  }, [manager]);

  // Check for active transfer saved in localStorage upon mount
  useEffect(() => {
    const activeId = localStorage.getItem(SENDER_ACTIVE_TRANSFER_KEY);
    if (activeId) {
      const raw = localStorage.getItem(SENDER_TRANSFER_PREFIX + activeId);
      if (raw) {
        try {
          const meta = JSON.parse(raw) as TransferMetadata;
          getTransferDetails(meta.transferId, meta.shareToken)
            .then(async (details) => {
              if (details.status !== 'EXPIRED' && details.status !== 'FAILED') {
                const chunks = await getAvailableChunks(meta.transferId, meta.shareToken);
                const uploaded = Math.min(chunks.length * details.chunkSize, details.fileSize);
                setResumableTransfer({ metadata: details, uploadedBytes: uploaded });
              } else {
                localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
                localStorage.removeItem(SENDER_TRANSFER_PREFIX + activeId);
              }
            })
            .catch(() => {
              localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
              localStorage.removeItem(SENDER_TRANSFER_PREFIX + activeId);
            });
        } catch {
          localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
          localStorage.removeItem(SENDER_TRANSFER_PREFIX + activeId);
        }
      } else {
        localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
      }
    }
  }, []);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      const file = e.target.files[0];
      const existing = resumableTransfer && resumableTransfer.metadata.fileName === file.name && resumableTransfer.metadata.fileSize === file.size
        ? resumableTransfer.metadata
        : undefined;
      setResumableTransfer(null);
      manager.start(file, existing);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      const file = e.dataTransfer.files[0];
      const existing = resumableTransfer && resumableTransfer.metadata.fileName === file.name && resumableTransfer.metadata.fileSize === file.size
        ? resumableTransfer.metadata
        : undefined;
      setResumableTransfer(null);
      manager.start(file, existing);
    }
  };

  const handleDismissResumable = (e: React.MouseEvent) => {
    e.stopPropagation();
    const activeId = localStorage.getItem(SENDER_ACTIVE_TRANSFER_KEY);
    localStorage.removeItem(SENDER_ACTIVE_TRANSFER_KEY);
    if (activeId) {
      localStorage.removeItem(SENDER_TRANSFER_PREFIX + activeId);
    }
    if (resumableTransfer) {
      localStorage.removeItem(SENDER_TRANSFER_PREFIX + resumableTransfer.metadata.transferId);
    }
    setResumableTransfer(null);
  };

  const formatBytes = (bytes: number, decimals = 2) => {
    if (!+bytes) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(decimals))} ${sizes[i]}`;
  };

  const copyToClipboard = (text: string, type: 'link' | 'code') => {
    navigator.clipboard.writeText(text);
    if (type === 'link') {
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 2000);
    } else {
      setCopiedCode(true);
      setTimeout(() => setCopiedCode(false), 2000);
    }
  };

  const shareUrl = progress?.metadata
    ? `${window.location.origin}/transfer/${progress.metadata.transferId}?token=${progress.metadata.shareToken}`
    : '';

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
            <input type="file" ref={fileInputRef} style={{ display: 'none' }} onChange={handleFileChange} />
            
            {resumableTransfer ? (
              <div className="flex-col" style={{ gap: '16px' }}>
                <div>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--accent)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '6px' }}>
                    Previous upload found
                  </div>
                  <h3 style={{ fontSize: '18px', margin: '0 0 6px 0', wordBreak: 'break-word' }}>
                    {resumableTransfer.metadata.fileName}
                  </h3>
                  <div className="text-mono" style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                    Saved progress: {formatBytes(resumableTransfer.uploadedBytes)} / {formatBytes(resumableTransfer.metadata.fileSize)}
                  </div>
                </div>

                <p style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)', margin: 0 }}>
                  Your upload progress has been saved. Select the same file to continue uploading.
                </p>

                <div style={{ display: 'flex', gap: '10px', paddingTop: '8px' }}>
                  <button className="btn" onClick={() => fileInputRef.current?.click()}>
                    Continue Upload
                  </button>
                  <button className="btn secondary" onClick={handleDismissResumable}>
                    Start New
                  </button>
                </div>
              </div>
            ) : (
              <div className="flex-col" style={{ gap: '20px' }}>
                <h2 style={{ fontSize: '20px', margin: 0, textAlign: 'center' }}>Send File</h2>
                
                <div 
                  className={`drop-zone ${isDragOver ? 'active' : ''}`}
                  onClick={() => fileInputRef.current?.click()}
                  onDragOver={handleDragOver}
                  onDragLeave={handleDragLeave}
                  onDrop={handleDrop}
                >
                  <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" style={{ marginBottom: '14px' }}>
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                    <polyline points="17 8 12 3 7 8" />
                    <line x1="12" y1="3" x2="12" y2="15" />
                  </svg>
                  <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '4px', color: 'var(--text-primary)' }}>
                    Choose a file or drag it here
                  </div>
                  <div className="text-mono" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    Supports files up to 20 GB
                  </div>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="flex-col" style={{ gap: '20px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '16px' }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <h3 style={{ fontSize: '17px', margin: '0 0 4px 0', wordBreak: 'break-word' }}>
                  {progress.metadata?.fileName || 'Uploading...'}
                </h3>
                <div className="text-mono" style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                  {progress.status === 'completed' ? 'Upload complete' : progress.status === 'paused' ? 'Paused' : 'Uploading'}
                  {' • '}
                  {formatBytes(progress.transferredBytes)} / {formatBytes(progress.totalBytes)}
                </div>
              </div>

              <span className={`status-badge ${progress.status === 'error' ? 'error' : progress.status === 'completed' ? 'success' : progress.status === 'paused' ? 'paused' : ''}`}>
                {progress.status}
              </span>
            </div>

            {/* Progress Bar */}
            <div className="progress-bar">
              <div 
                className={`progress-fill ${progress.status === 'error' ? 'error' : progress.status === 'completed' ? 'success' : ''}`}
                style={{ width: `${progress.progress}%` }}
              />
            </div>

            {/* Progress Percentage & Controls */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div className="text-mono" style={{ fontSize: '18px', fontWeight: 600, color: 'var(--accent)' }}>
                {progress.progress}%
              </div>
              <div>
                {(progress.status === 'progressing' || progress.status === 'starting') && (
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

            {/* Share Link & Transfer Code */}
            {progress.metadata && progress.status !== 'error' && (
              <div className="flex-col" style={{ gap: '14px', marginTop: '4px', borderTop: '1px solid var(--border-subtle)', paddingTop: '16px' }}>
                {/* Direct Share Link */}
                <div>
                  <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Share link
                  </div>
                  <div className="copy-box">
                    <a 
                      href={shareUrl} 
                      target="_blank" 
                      rel="noreferrer" 
                      className="text-mono"
                      style={{ 
                        color: 'var(--accent)', 
                        fontSize: '13px', 
                        textDecoration: 'none', 
                        flex: 1, 
                        overflow: 'hidden', 
                        textOverflow: 'ellipsis', 
                        whiteSpace: 'nowrap' 
                      }}
                    >
                      {shareUrl}
                    </a>
                    <button 
                      onClick={() => copyToClipboard(shareUrl, 'link')} 
                      title="Copy share link"
                      className={`copy-btn ${copiedLink ? 'copied' : ''}`}
                    >
                      {copiedLink ? '✓' : '⎘'}
                    </button>
                  </div>
                </div>

                {/* Transfer Code */}
                <div>
                  <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Transfer code
                  </div>
                  <div className="copy-box" style={{ display: 'inline-flex', padding: '6px 12px' }}>
                    <span className="text-mono" style={{ fontSize: '15px', fontWeight: 600, color: 'var(--accent)', letterSpacing: '0.08em' }}>
                      {progress.metadata.transferCode || progress.metadata.transferId}
                    </span>
                    <button 
                      onClick={() => copyToClipboard(progress.metadata!.transferCode || progress.metadata!.transferId, 'code')} 
                      title="Copy transfer code"
                      className={`copy-btn ${copiedCode ? 'copied' : ''}`}
                    >
                      {copiedCode ? '✓' : '⎘'}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Error Message */}
            {progress.status === 'error' && (
              <div style={{ padding: '14px', background: 'var(--error-bg)', border: '1px solid var(--error-border)', borderRadius: '8px', color: 'var(--error)', fontSize: '14px' }}>
                <div style={{ marginBottom: '10px' }}>
                  {progress.error?.message || 'An upload error occurred.'}
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
