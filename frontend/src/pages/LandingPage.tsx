import React from 'react';

export const LandingPage: React.FC<{ navigate: (path: string) => void }> = ({ navigate }) => {
  return (
    <div className="flex-col" style={{ flex: 1, justifyContent: 'space-between', gap: '56px', maxWidth: '840px', margin: '0 auto', width: '100%' }}>
      {/* Hero Section */}
      <div className="flex-col flex-center" style={{ textAlign: 'center', paddingTop: '32px' }}>
        <div style={{ 
          display: 'inline-flex', 
          alignItems: 'center', 
          gap: '8px', 
          padding: '6px 14px', 
          background: 'var(--accent-dim)', 
          border: '1px solid var(--accent-border)', 
          borderRadius: '999px',
          fontSize: '12px',
          fontWeight: 600,
          letterSpacing: '0.05em',
          color: 'var(--accent)',
          textTransform: 'uppercase',
          marginBottom: '20px'
        }}>
          FITE — File Transfer
        </div>

        <h1 style={{ fontSize: '40px', fontWeight: 700, lineHeight: 1.2, margin: '0 0 16px 0', color: 'var(--text-primary)' }}>
          Secure progressive file sharing
        </h1>

        <p style={{ fontSize: '17px', lineHeight: 1.6, color: 'var(--text-secondary)', maxWidth: '620px', margin: '0 0 36px 0' }}>
          Transfer large files reliably with verified chunk integrity, pause &amp; resume support, and progressive downloads while uploading is still in progress.
        </p>

        <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap', justifyContent: 'center' }}>
          <button 
            className="btn btn-lg" 
            style={{ minWidth: '160px' }} 
            onClick={() => navigate('/send')}
          >
            Send File
          </button>

          <button 
            className="btn secondary btn-lg" 
            style={{ minWidth: '160px' }} 
            onClick={() => navigate('/receive')}
          >
            Receive File
          </button>
        </div>
      </div>

      {/* About Section */}
      <div className="flex-col" style={{ gap: '24px', borderTop: '1px solid var(--border-subtle)', paddingTop: '40px' }}>
        <div>
          <h2 style={{ fontSize: '20px', fontWeight: 600, marginBottom: '8px' }}>
            About FITE - File Sharing
          </h2>
          <p style={{ fontSize: '14px', lineHeight: 1.6, color: 'var(--text-secondary)', margin: 0 }}>
            FITE is a focused, high-reliability file transfer utility designed for direct, friction-free file exchange without requiring user accounts or software installation.
          </p>
        </div>

        {/* Feature Grid */}
        <div style={{ 
          display: 'grid', 
          gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', 
          gap: '16px' 
        }}>
          <div className="panel panel-interactive" style={{ padding: '20px' }}>
            <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '6px', color: 'var(--text-primary)' }}>
              Progressive Receiving
            </div>
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Download available portions immediately while the sender is still actively uploading.
            </div>
          </div>

          <div className="panel panel-interactive" style={{ padding: '20px' }}>
            <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '6px', color: 'var(--text-primary)' }}>
              Large File Handling
            </div>
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Chunk-based streaming architecture engineered to transfer files of any size reliably.
            </div>
          </div>

          <div className="panel panel-interactive" style={{ padding: '20px' }}>
            <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '6px', color: 'var(--text-primary)' }}>
              Pause &amp; Resume
            </div>
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Seamlessly pause and resume active uploads and downloads at any point.
            </div>
          </div>

          <div className="panel panel-interactive" style={{ padding: '20px' }}>
            <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '6px', color: 'var(--text-primary)' }}>
              Interruption Recovery
            </div>
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Local state preservation automatically recovers transfers across browser refreshes.
            </div>
          </div>

          <div className="panel panel-interactive" style={{ padding: '20px' }}>
            <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '6px', color: 'var(--text-primary)' }}>
              Verified Integrity
            </div>
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Chunk-level cryptographic SHA-256 validation guarantees byte-for-byte fidelity.
            </div>
          </div>

          <div className="panel panel-interactive" style={{ padding: '20px' }}>
            <div style={{ fontSize: '15px', fontWeight: 600, marginBottom: '6px', color: 'var(--text-primary)' }}>
              Ephemeral &amp; Accountless
            </div>
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Share instantly using direct capability links or short in-person transfer codes.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
